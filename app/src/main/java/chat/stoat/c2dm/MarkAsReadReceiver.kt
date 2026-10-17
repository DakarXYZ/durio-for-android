package chat.stoat.c2dm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import chat.stoat.api.routes.channel.ackChannel
import chat.stoat.fork.push.NotificationKeys
import chat.stoat.fork.push.PushNotifications
import chat.stoat.fork.push.PushRouter
import chat.stoat.fork.push.launchAsync
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

        // durio: the account the notification belongs to, never the active one, and off the
        // main thread (plan AN-12, AN-15)
        val fromIntent = intent.getStringExtra(NotificationKeys.EXTRA_ACCOUNT_KEY)
        launchAsync {
            val key = PushRouter.accountKey(fromIntent) ?: run {
                logcat(LogPriority.ERROR) { "Notification of an unknown account, aborting" }
                return@launchAsync
            }
            val http = PushRouter.http(key) ?: run {
                logcat(LogPriority.ERROR) { "No session token, aborting" }
                return@launchAsync
            }
            http.use { client ->
                runCatching { ackChannel(channelId, messageId, client = client) }
                    .onFailure { logcat(LogPriority.ERROR) { "Ack failed: ${it.asLog()}" } }
            }
            PushNotifications.cancelConversation(context, key, channelId)
        }
    }
}
