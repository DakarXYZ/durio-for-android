package chat.stoat.fork.push

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import chat.stoat.R
import chat.stoat.api.fork.e2ee.E2eeChannels
import chat.stoat.api.fork.http.ApiClient
import chat.stoat.api.internals.ULID
import chat.stoat.api.routes.channel.fetchSingleChannel
import chat.stoat.api.routes.channel.sendMessage
import chat.stoat.fork.account.AccountManager
import chat.stoat.fork.e2ee.E2eeSendError
import chat.stoat.fork.e2ee.OutgoingMessages
import chat.stoat.internals.extensions.getStringBlocking
import chat.stoat.persistence.Database
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.durio_e2ee_reply_not_sent
import chat.stoat.shared.resources.durio_e2ee_reply_not_sent_text
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import logcat.logcat
import java.util.concurrent.ConcurrentHashMap

/**
 * Replies from a notification in an encrypted chat (plan AN-43, Q38). Encrypting may need the
 * network (key claim, room-key share) and does not fit a broadcast receiver, so the reply goes
 * to a WorkManager job. The **text stays in this process's memory**: WorkManager keeps its input
 * in its own SQLite in plaintext, so the job only gets ids. If the process dies before the job
 * runs, the text is gone and the user is told the reply was not sent.
 *
 * Whether a chat is encrypted: the pin of the account (AN-39), else the server's channel. When
 * neither can be asked (offline) the reply also goes the job's way — never plaintext by guess.
 */
object EncryptedReplies {
    private val pending = ConcurrentHashMap<String, String>()

    /** true / false, or null: cannot tell right now. */
    suspend fun isEncrypted(accountKey: String, channelId: String, client: ApiClient): Boolean? {
        if (AccountManager.active?.key == accountKey && E2eeChannels.isEncrypted(channelId)) return true
        if (isPinned(accountKey, channelId)) return true
        return try {
            val channel = fetchSingleChannel(channelId, client)
            E2eeChannels.isEncryptable(channel) && channel.encryption != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun isPinned(accountKey: String, channelId: String): Boolean = try {
        val driver = AccountManager.registry.get(accountKey).sqlDriver
        channelId in Database(driver).encryptedChannelQueries.selectAll().executeAsList()
    } catch (e: Exception) {
        false
    }

    fun enqueue(context: Context, accountKey: String, channelId: String, text: String) {
        val replyId = ULID.makeNext()
        pending[replyId] = text
        val request = OneTimeWorkRequestBuilder<EncryptedReplyWorker>()
            .setInputData(
                workDataOf(
                    EncryptedReplyWorker.ACCOUNT to accountKey,
                    EncryptedReplyWorker.CHANNEL to channelId,
                    EncryptedReplyWorker.REPLY to replyId,
                )
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("e2ee-reply-$replyId", ExistingWorkPolicy.KEEP, request)
    }

    internal fun take(replyId: String): String? = pending.remove(replyId)

    internal fun putBack(replyId: String, text: String) {
        pending[replyId] = text
    }

    /** "Reply not sent, open the chat": the text is lost or could not be encrypted. */
    suspend fun notifyNotSent(context: Context, accountKey: String, channelId: String) {
        val account = AccountManager.state.value.find(accountKey) ?: return
        val notification = NotificationCompat.Builder(context, PushNotifications.messagesChannel(context, account))
            .setSmallIcon(R.drawable.ic_stoat_24dp)
            .setContentTitle(getStringBlocking(Res.string.durio_e2ee_reply_not_sent))
            .setContentText(getStringBlocking(Res.string.durio_e2ee_reply_not_sent_text))
            .setGroup(NotificationKeys.channelGroup(accountKey))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify("${NotificationKeys.tag(accountKey, channelId)}:reply", 0, notification)
        } catch (e: SecurityException) {
            // no notification permission
        }
    }
}

class EncryptedReplyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val key = inputData.getString(ACCOUNT) ?: return Result.success()
        val channelId = inputData.getString(CHANNEL) ?: return Result.success()
        val replyId = inputData.getString(REPLY) ?: return Result.success()

        val text = EncryptedReplies.take(replyId) ?: run {
            // the process died since the reply was typed (Q38)
            EncryptedReplies.notifyNotSent(applicationContext, key, channelId)
            return Result.success()
        }
        val account = AccountManager.accounts().firstOrNull { it.key == key } ?: return Result.success()
        val http = AccountManager.http(key) ?: run {
            EncryptedReplies.notifyNotSent(applicationContext, key, channelId)
            return Result.success()
        }

        val sent = http.use { client ->
            try {
                val channel = fetchSingleChannel(channelId, client)
                val encrypted = EncryptedReplies.isEncrypted(key, channelId, client) ?: true
                if (encrypted) {
                    OutgoingMessages.send(
                        channel = channel,
                        content = text,
                        nonce = ULID.makeNext(),
                        accountKey = key,
                        selfId = account.userId,
                        client = client,
                    )
                } else {
                    sendMessage(channelId, text, client = client)
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "Encrypted reply not sent: ${e::class.simpleName}" }
                // keys could not be shared or no network: try again while the text is here
                if (OutgoingMessages.reason(e) == E2eeSendError.Network && runAttemptCount < MAX_ATTEMPTS) {
                    EncryptedReplies.putBack(replyId, text)
                    return Result.retry()
                }
                false
            }
        }
        if (sent) {
            PushNotifications.cancelConversation(applicationContext, key, channelId)
        } else {
            EncryptedReplies.notifyNotSent(applicationContext, key, channelId)
        }
        return Result.success()
    }

    companion object {
        const val ACCOUNT = "account"
        const val CHANNEL = "channel"
        const val REPLY = "reply"
        private const val MAX_ATTEMPTS = 3
    }
}
