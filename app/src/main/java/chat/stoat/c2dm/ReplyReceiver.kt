package chat.stoat.c2dm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import chat.stoat.api.routes.channel.sendMessage
import chat.stoat.fork.account.AccountHttp
import chat.stoat.fork.account.AccountManager
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import logcat.asLog
import logcat.logcat

class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val channelId = intent.getStringExtra("channelId") ?: run {
            logcat(LogPriority.ERROR) { "No channel ID, aborting" }
            return
        }

        val content = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence("content")
            ?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: run {
                logcat(LogPriority.ERROR) { "No content, aborting" }
                return
            }

        runBlocking {
            // durio: the account's own client, never the global token (AN-15); AN-12 picks the
            // account from the intent
            val http = AccountManager.active?.let { AccountHttp(it.key) } ?: run {
                logcat(LogPriority.ERROR) { "No session token, aborting" }
                return@runBlocking
            }
            http.use { client ->
                runCatching { sendMessage(channelId, content, client = client) }
                    .onFailure { logcat(LogPriority.ERROR) { "Send failed: ${it.asLog()}" } }
            }
        }

        NotificationManagerCompat.from(context).cancel(channelId, NotificationID.NEW_MESSAGE)
    }
}
