package chat.stoat.c2dm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import chat.stoat.api.routes.channel.ackChannel
import chat.stoat.fork.account.AccountHttp
import chat.stoat.fork.account.AccountManager
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import logcat.asLog
import logcat.logcat

class MarkAsReadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val channelId = intent.getStringExtra("channelId") ?: run {
            logcat(LogPriority.ERROR) { "No channel ID, aborting" }
            return
        }

        val messageId = intent.getStringExtra("messageId") ?: run {
            logcat(LogPriority.ERROR) { "No message ID, aborting" }
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
                runCatching { ackChannel(channelId, messageId, client = client) }
                    .onFailure { logcat(LogPriority.ERROR) { "Ack failed: ${it.asLog()}" } }
            }
        }

        NotificationManagerCompat.from(context).cancel(channelId, NotificationID.NEW_MESSAGE)
    }
}
