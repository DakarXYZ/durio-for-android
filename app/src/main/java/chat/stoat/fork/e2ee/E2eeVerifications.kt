package chat.stoat.fork.e2ee

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import chat.stoat.R
import chat.stoat.activities.MainActivity
import chat.stoat.api.fork.e2ee.E2eeCore
import chat.stoat.api.fork.e2ee.E2eeSas
import chat.stoat.api.fork.e2ee.E2eeSasState
import chat.stoat.api.fork.e2ee.E2eeSyncEvent
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.durio_verify_notification_channel
import chat.stoat.shared.resources.durio_verify_notification_text
import chat.stoat.shared.resources.durio_verify_notification_title
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import logcat.logcat
import org.jetbrains.compose.resources.getString

/** The core and the request pass of the active account, for one verification. */
class VerificationDevice(val core: E2eeCore, val pass: () -> Unit)

/** What the verification dialog shows (AN-31). */
data class VerificationShown(
    val flowId: String,
    val otherUserId: String,
    val otherDeviceId: String?,
    val self: Boolean,
    val state: E2eeSasState,
    val busy: Boolean = false,
    /** The last action failed (the flow went away, the store refused). */
    val failed: Boolean = false,
)

/**
 * One SAS flow at a time, on top of every screen (plan AN-31). Started from the devices screen
 * or a profile ([start]), or by the other device: [onFlows] gets the flow ids of a mailbox pass
 * ([E2eeSyncEvent.Verifications]). The core keeps the state and has no callbacks, so while a
 * flow is shown its state is re-read every [pollMs], and every action is followed by a request
 * pass (the messages leave with it). Timeouts are the core's (10 min request, 5 min SAS).
 *
 * The side that asked starts the comparison as soon as the other accepted; the other side's
 * core accepts that start by itself — the user only accepts, compares and confirms.
 */
class VerificationController(
    private val scope: CoroutineScope,
    private val device: () -> VerificationDevice?,
    private val pollMs: Long = 500,
    private val isForeground: () -> Boolean = { true },
    private val onIncomingInBackground: (VerificationShown) -> Unit = {},
) {
    private val mutex = Mutex()
    private var sas: E2eeSas? = null
    private var pass: () -> Unit = {}
    private var weAsked = false
    private var started = false
    private var poller: Job? = null

    private val _shown = MutableStateFlow<VerificationShown?>(null)
    val shown: StateFlow<VerificationShown?> = _shown.asStateFlow()

    /**
     * Asks [deviceId] of [userId] to verify. Returns false without an E2EE device or if the
     * core does not know that device (yet).
     */
    suspend fun start(userId: String, deviceId: String): Boolean = mutex.withLock {
        val device = device() ?: return@withLock false
        val flow = try {
            device.core.requestVerification(userId, deviceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "Verification not started: ${e::class.simpleName}" }
            return@withLock false
        }
        replace(flow, device.pass, asked = true)
        device.pass()
        true
    }

    /** Flows a mailbox pass touched: an incoming request is shown, the current one refreshed. */
    suspend fun onFlows(flowIds: List<String>) = mutex.withLock {
        val device = device() ?: return@withLock
        for (id in flowIds) {
            if (id == sas?.flowId) {
                refreshLocked()
                continue
            }
            val flow = try {
                device.core.verification(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: continue
            val busy = _shown.value?.state?.isFinal == false
            if (flow.state() != E2eeSasState.Incoming || busy) {
                // one at a time: a second request waits for the core's timeout
                flow.close()
                continue
            }
            replace(flow, device.pass, asked = false)
            if (!isForeground()) _shown.value?.let(onIncomingInBackground)
        }
    }

    fun accept() = act { it.accept() }

    fun confirm() = act { it.confirm() }

    /** The emoji differ: cancelled as a possible attack. */
    fun mismatch() = act { it.mismatch() }

    fun cancel() = act { it.cancel() }

    /** Closes the dialog; an open flow is cancelled first. */
    fun dismiss() {
        scope.launch {
            mutex.withLock {
                val flow = sas ?: return@withLock
                if (!flow.state().isFinal) {
                    try {
                        flow.cancel()
                        pass()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // the flow is gone already
                    }
                }
                clearLocked()
            }
        }
    }

    /**
     * Account switch or logout: the handle holds the core (and its store lock) until closed,
     * so it goes before the core does.
     */
    suspend fun clear() = mutex.withLock { clearLocked() }

    private fun replace(flow: E2eeSas, pass: () -> Unit, asked: Boolean) {
        clearLocked()
        sas = flow
        this.pass = pass
        weAsked = asked
        started = false
        refreshLocked()
        poller = scope.launch {
            while (isActive) {
                delay(pollMs)
                val done = mutex.withLock {
                    if (sas !== flow) return@withLock true
                    refreshLocked()
                    _shown.value?.state?.isFinal == true
                }
                if (done) break
            }
        }
    }

    private fun clearLocked() {
        poller?.cancel()
        poller = null
        sas?.close()
        sas = null
        _shown.value = null
    }

    private fun refreshLocked() {
        val flow = sas ?: return
        val state = try {
            flow.state()
        } catch (e: Exception) {
            _shown.value = _shown.value?.copy(failed = true)
            return
        }
        _shown.value = VerificationShown(
            flowId = flow.flowId,
            otherUserId = flow.otherUserId,
            otherDeviceId = flow.otherDeviceId(),
            self = flow.isSelfVerification,
            state = state,
            busy = _shown.value?.takeIf { it.flowId == flow.flowId }?.busy ?: false,
        )
        // the asking side starts the comparison once the other accepted
        if (state == E2eeSasState.Ready && weAsked && !started) {
            started = true
            act { it.start() }
        }
    }

    private fun act(action: suspend (E2eeSas) -> Unit) {
        scope.launch {
            mutex.withLock {
                val flow = sas ?: return@withLock
                _shown.value = _shown.value?.copy(busy = true, failed = false)
                val failed = try {
                    action(flow)
                    pass()
                    false
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.WARN) { "Verification step failed: ${e::class.simpleName}" }
                    true
                }
                if (sas !== flow) return@withLock
                refreshLocked()
                _shown.value = _shown.value?.copy(busy = false, failed = failed)
            }
        }
    }
}

/**
 * The verification of the active account (AN-31): [controller] wired to [E2eeManager]. An
 * incoming request while the app is in the background posts a notification that opens it.
 */
object E2eeVerifications {
    private const val CHANNEL = "durio_e2ee_verification"
    private const val NOTIFICATION_ID = 0x5A5
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null

    val controller = VerificationController(
        scope = scope,
        device = { E2eeManager.active?.let { VerificationDevice(it.core) { it.sync.requestPass() } } },
        isForeground = {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        },
        onIncomingInBackground = { notifyIncoming() },
    )

    fun init(context: Context) {
        appContext = context.applicationContext
        scope.launch {
            E2eeManager.events.collect { event ->
                if (event is E2eeSyncEvent.Verifications) controller.onFlows(event.flowIds)
            }
        }
    }

    /** The dialog was opened or answered: the notification has done its job. */
    fun cancelNotification() {
        appContext?.let { NotificationManagerCompat.from(it).cancel(NOTIFICATION_ID) }
    }

    @SuppressLint("MissingPermission")
    private fun notifyIncoming() {
        val context = appContext ?: return
        scope.launch {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return@launch
            manager.createNotificationChannel(
                NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(getString(Res.string.durio_verify_notification_channel))
                    .build()
            )
            val open = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stoat_24dp)
                .setContentTitle(getString(Res.string.durio_verify_notification_title))
                .setContentText(getString(Res.string.durio_verify_notification_text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setTimeoutAfter(10 * 60 * 1000L)
                .build()
            try {
                manager.notify(NOTIFICATION_ID, notification)
            } catch (e: SecurityException) {
                // no notification permission
            }
        }
    }
}
