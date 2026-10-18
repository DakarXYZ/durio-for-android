package chat.stoat.fork.e2ee

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.core.content.edit
import chat.stoat.api.fork.e2ee.E2eeBackup
import chat.stoat.api.fork.e2ee.E2eeException
import chat.stoat.api.fork.e2ee.E2eeMessages
import chat.stoat.api.fork.e2ee.E2eeRequestException
import chat.stoat.api.fork.e2ee.E2eeRestoreProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.logcat

/** Why a recovery step failed, for the UI (AN-32). */
enum class RecoveryFailure {
    /** Shaped like a recovery key, with a typo in it. */
    Typo,
    WrongKey,

    /** No backup on the server. */
    NoBackup,

    /** The server's backup does not fit its recovery data: never restored from. */
    Mismatch,
    Network,
    Other,
}

fun recoveryFailure(e: Exception): RecoveryFailure = when (e) {
    is E2eeException.RecoveryKeyTypo -> RecoveryFailure.Typo
    is E2eeException.WrongRecoveryKey -> RecoveryFailure.WrongKey
    is E2eeException.NoBackup -> RecoveryFailure.NoBackup
    is E2eeException.BackupKeyMismatch -> RecoveryFailure.Mismatch
    is E2eeRequestException -> RecoveryFailure.Network
    else -> RecoveryFailure.Other
}

/**
 * The key backup of the active account (plan AN-32) for the screens: the restore runs in the
 * process scope, so leaving the screen does not stop it (an interrupted one can run again, an
 * import never makes a key worse); then every message waiting for a key is decrypted again.
 */
object E2eeRecovery {
    sealed interface Restore {
        data object Idle : Restore
        data class Running(val progress: E2eeRestoreProgress?) : Restore
        data class Done(val imported: Long, val failed: Long) : Restore
        data class Failed(val reason: RecoveryFailure) : Restore
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _restore = MutableStateFlow<Restore>(Restore.Idle)
    val restore: StateFlow<Restore> = _restore.asStateFlow()

    /** The backup of the active account; null without an E2EE device. */
    fun backup(): E2eeBackup? = E2eeManager.active?.let { E2eeBackup(it.core, it.sync, it.http) }

    /**
     * Unlocks with the recovery key or passphrase ([input], UTF-8; wiped) and starts the
     * restore. Returns null once the restore runs, else why it could not start.
     */
    suspend fun unlockAndRestore(input: ByteArray): RecoveryFailure? {
        val backup = backup() ?: run {
            input.fill(0)
            return RecoveryFailure.Other
        }
        val version = try {
            backup.unlock(input)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "Backup not unlocked: ${e::class.simpleName}" }
            return recoveryFailure(e)
        }
        start(backup, version)
        return null
    }

    private fun start(backup: E2eeBackup, version: String) {
        job?.cancel()
        _restore.value = Restore.Running(null)
        job = scope.launch {
            _restore.value = try {
                val done = backup.restore(version) { _restore.value = Restore.Running(it) }
                E2eeMessages.retryWaiting()
                Restore.Done(done.imported, done.failed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "Backup not restored: ${e::class.simpleName}" }
                Restore.Failed(recoveryFailure(e))
            }
        }
    }

    fun dismissRestore() {
        if (_restore.value !is Restore.Running) _restore.value = Restore.Idle
    }

    /** Account switch: a restore of the old account stops with it. */
    fun reset() {
        job?.cancel()
        _restore.value = Restore.Idle
    }
}

/**
 * Copying the recovery key (AN-32): marked sensitive (no preview, API 33+; keyboards with a
 * clipboard history are asked not to keep it) and cleared after [CLEAR_AFTER_MS]. The UI warns
 * before it copies: other apps and keyboards may still read the clipboard meanwhile.
 */
object SensitiveClipboard {
    const val CLEAR_AFTER_MS = 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var clear: Job? = null

    fun copy(context: Context, text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText("", text).apply {
            description.extras = PersistableBundle().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                } else {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
            }
        }
        clipboard.setPrimaryClip(clip)
        clear?.cancel()
        clear = scope.launch {
            delay(CLEAR_AFTER_MS)
            withContext(Dispatchers.Main) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        clipboard.clearPrimaryClip()
                    } else {
                        clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                    }
                } catch (e: Exception) {
                    // the clipboard is not ours to clear any more
                }
            }
        }
    }
}

/**
 * The first prompt of a device (AN-31, AN-32), once per E2EE device (= session): a new device
 * of a user with other devices or a backup is offered to verify or restore (T7); the first
 * device of a user without a backup is offered to set one up.
 */
object E2eeOnboarding {
    enum class Prompt { NewDevice, SetUpRecovery }

    private const val PREFERENCES = "durio_e2ee_onboarding"
    private val _prompt = MutableStateFlow<Prompt?>(null)
    val prompt: StateFlow<Prompt?> = _prompt.asStateFlow()

    @Volatile
    private var checked: String? = null

    /** After the active account's device became ready. */
    suspend fun check(context: Context) {
        val active = E2eeManager.active ?: return
        val device = active.account.sessionId
        if (checked == device) return
        checked = device
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (prefs.getBoolean(device, false)) return
        _prompt.value = try {
            decide(
                ownDevices = active.core.devices(active.account.userId).count { !it.deleted && it.deviceId != device },
                backupOnThisDevice = active.core.backupStatus().enabled,
                backupOnServer = E2eeBackup(active.core, active.sync, active.http).exists(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // offline: ask on the next start
            checked = null
            return
        }
        if (_prompt.value == null) prefs.edit { putBoolean(device, true) }
    }

    fun decide(ownDevices: Int, backupOnThisDevice: Boolean, backupOnServer: Boolean): Prompt? = when {
        backupOnThisDevice -> null
        ownDevices > 0 || backupOnServer -> Prompt.NewDevice
        else -> Prompt.SetUpRecovery
    }

    fun dismiss(context: Context) {
        _prompt.value = null
        val device = E2eeManager.active?.account?.sessionId ?: return
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit { putBoolean(device, true) }
    }
}
