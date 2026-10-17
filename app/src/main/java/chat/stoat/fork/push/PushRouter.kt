package chat.stoat.fork.push

import android.content.BroadcastReceiver
import chat.stoat.api.fork.http.AccountHttp
import chat.stoat.fork.account.AccountManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import logcat.logcat

/**
 * Push for several accounts (plan AN-12): finds the account of a push and of a notification
 * action without touching the active account (its socket, token and caches stay as they are).
 */
object PushRouter {
    suspend fun route(message: PushMessage): PushTarget =
        resolveAccount(message, AccountManager.accounts())

    /**
     * Account of a notification action. Notifications posted before AN-12 carry no key; they can
     * only be trusted when the device has a single account.
     */
    suspend fun accountKey(fromIntent: String?): String? {
        val accounts = AccountManager.accounts()
        return if (fromIntent != null) {
            fromIntent.takeIf { key -> accounts.any { it.key == key } }
        } else {
            accounts.singleOrNull()?.key
        }
    }

    /** HTTP of the account a notification action belongs to; null if it has no usable session. */
    suspend fun http(key: String): AccountHttp? = AccountManager.http(key)
}

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Runs a notification action off the main thread (plan AN-12): upstream blocked the receiver's
 * main thread on the network. A receiver has about 10 s, so the work is cut at 8.
 */
fun BroadcastReceiver.launchAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    receiverScope.launch {
        try {
            withTimeout(RECEIVER_TIMEOUT_MS) { block() }
        } catch (e: TimeoutCancellationException) {
            logcat(LogPriority.WARN) { "Notification action timed out" }
        } finally {
            pending.finish()
        }
    }
}

private const val RECEIVER_TIMEOUT_MS = 8_000L
