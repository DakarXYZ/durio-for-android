package chat.stoat.fork.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import chat.stoat.BuildConfig
import chat.stoat.api.StoatHttp
import chat.stoat.api.StoatJson
import chat.stoat.api.buildUserAgent
import chat.stoat.persistence.KVStorage
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import java.io.File
import java.io.IOException

/**
 * In-app updates from our GitHub Releases (plan AN-18, O2): never from a chat server (Q62).
 * Nothing is downloaded or installed without the user asking for it, and the system installer
 * asks once more; an APK signed with another key does not install over ours.
 */
object AppUpdates {
    const val RELEASES_LATEST = "https://api.github.com/repos/DakarXYZ/durio-for-android/releases/latest"
    private const val LAST_CHECK = "durio/updateCheckedAt"

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val update: AvailableUpdate) : State
        data class Downloading(val update: AvailableUpdate) : State
        data class Installing(val update: AvailableUpdate) : State
        data class Failed(val reason: Reason, val update: AvailableUpdate?) : State
    }

    enum class Reason { Network, Checksum, Install }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The startup prompt was dismissed; the About screen still shows the update. */
    private val _dismissed = MutableStateFlow(false)
    val dismissed: StateFlow<Boolean> = _dismissed.asStateFlow()

    val enabled: Boolean
        get() = BuildConfig.FORK_UPDATE_CHECK

    // A client of its own: no session token, no API base URL (AN-15)
    private fun client() = HttpClient(StoatHttp.engine) {
        expectSuccess = false
    }

    /** At app start: at most once a day. */
    suspend fun checkIfDue(context: Context) {
        if (!enabled) return
        val kv = KVStorage(context)
        val now = System.currentTimeMillis()
        if (!UpdateRules.isDue(kv.get(LAST_CHECK)?.toLongOrNull(), now)) return
        if (check()) kv.set(LAST_CHECK, now.toString())
    }

    /** From the About screen. True if GitHub answered. */
    suspend fun check(): Boolean {
        if (!enabled) return false
        if (_state.value is State.Downloading || _state.value is State.Installing) return false
        _state.value = State.Checking
        return try {
            val update = withContext(Dispatchers.IO) { fetchUpdate() }
            _state.value = if (update != null) State.Available(update) else State.UpToDate
            if (update != null) _dismissed.value = false
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "Update check failed: ${e.asLog()}" }
            _state.value = State.Failed(Reason.Network, null)
            false
        }
    }

    private suspend fun fetchUpdate(): AvailableUpdate? = client().use { http ->
        val releaseResponse = http.get(RELEASES_LATEST) {
            header("Accept", "application/vnd.github+json")
            header("User-Agent", buildUserAgent())
        }
        // 404: no release published yet
        if (!releaseResponse.status.isSuccess()) {
            if (releaseResponse.status.value == 404) return null
            throw IOException("GitHub answered ${releaseResponse.status}")
        }
        val release = StoatJson.decodeFromString(GithubRelease.serializer(), releaseResponse.bodyAsText())
        val manifestUrl = UpdateRules.manifestUrl(release) ?: return null
        val manifestResponse = http.get(manifestUrl)
        if (!manifestResponse.status.isSuccess()) throw IOException("Manifest: ${manifestResponse.status}")
        val manifest = StoatJson.decodeFromString(UpdateManifest.serializer(), manifestResponse.bodyAsText())
        UpdateRules.availableUpdate(release, manifest, BuildConfig.VERSION_CODE, Build.SUPPORTED_ABIS.toList())
    }

    fun dismiss() {
        _dismissed.value = true
    }

    /** The user chose to install: download, check the hash, hand over to the system installer. */
    suspend fun downloadAndInstall(context: Context, update: AvailableUpdate) {
        _state.value = State.Downloading(update)
        val file = try {
            withContext(Dispatchers.IO) { download(context, update) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "Update download failed: ${e.asLog()}" }
            _state.value = State.Failed(Reason.Network, update)
            return
        }
        if (!withContext(Dispatchers.IO) { UpdateRules.matches(file, update.apk.sha256) }) {
            file.delete()
            _state.value = State.Failed(Reason.Checksum, update)
            return
        }
        _state.value = State.Installing(update)
        try {
            withContext(Dispatchers.IO) { install(context, file) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logcat(LogPriority.WARN) { "Update install failed: ${e.asLog()}" }
            _state.value = State.Failed(Reason.Install, update)
        }
    }

    private suspend fun download(context: Context, update: AvailableUpdate): File {
        val dir = File(context.cacheDir, "updates")
        // older downloads are of no use any more
        dir.deleteRecursively()
        dir.mkdirs()
        val target = File(dir, update.apk.file)
        client().use { http ->
            val response = http.get(update.downloadUrl)
            if (!response.status.isSuccess()) throw IOException("Download: ${response.status}")
            response.bodyAsChannel().toInputStream().use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
        return target
    }

    private fun install(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val status = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, UpdateInstallReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            session.commit(status.intentSender)
        }
    }

    internal fun onInstallResult(success: Boolean) {
        val current = _state.value
        if (current is State.Installing) {
            _state.value = if (success) State.Idle else State.Failed(Reason.Install, current.update)
        }
    }
}

/** Status of the install session: shows the system's confirmation, reports failures. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> AppUpdates.onInstallResult(success = true)
            else -> {
                logcat(LogPriority.WARN) {
                    "Update not installed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}"
                }
                AppUpdates.onInstallResult(success = false)
            }
        }
    }
}
