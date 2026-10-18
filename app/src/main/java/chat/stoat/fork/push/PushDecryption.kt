package chat.stoat.fork.push

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import chat.stoat.R
import chat.stoat.api.StoatAPI
import chat.stoat.api.StoatJson
import chat.stoat.api.fork.e2ee.E2eePushDecryptor
import chat.stoat.api.internals.ULID
import chat.stoat.api.routes.channel.fetchSingleMessage
import chat.stoat.c2dm.NotificationID
import chat.stoat.core.model.fork.EncryptedEnvelope
import chat.stoat.core.model.schemas.Message
import chat.stoat.fork.account.Account
import chat.stoat.fork.account.AccountManager
import chat.stoat.fork.e2ee.E2eeManager
import chat.stoat.internals.extensions.getStringBlocking
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.durio_push_encrypted_file
import chat.stoat.shared.resources.durio_push_encrypted_voice
import chat.stoat.shared.resources.durio_push_new_message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import logcat.logcat
import java.util.concurrent.TimeUnit

/**
 * Push of an encrypted message (plan AN-29; fields by C11: `encrypted="1"`, `envelope` only if
 * the push stayed under 3.5 KiB, `recipient`, `instance`). The text is decrypted here with the
 * core of the push's account — active or not — borrowed from the registry
 * ([E2eeManager.withSession]): envelope → decrypt; missing key → one mailbox pass (≤ 5 s) →
 * again; no envelope → the message by id. Whatever fails shows the generic "New message", and a
 * job tries again once there is a network and replaces that line in the notification.
 *
 * On the lock screen encrypted notifications are private, with a public "New message" (Q65).
 * The plaintext lives only in the notification itself; the job gets ids, never text (Q38).
 */
object PushDecryption {
    /** Total budget of the FCM handler for decryption (~20 s before the system may kill it). */
    private const val BUDGET_MS = 9_000L

    fun isEncrypted(data: Map<String, String>) = data["encrypted"] == "1"

    /** The notification text, or null: show the generic one. */
    suspend fun text(context: Context, account: Account, data: Map<String, String>): String? {
        val messageId = data["message"] ?: return null
        val channelId = data["channel"] ?: return null
        val author = data["author"] ?: return null
        return try {
            withTimeoutOrNull(BUDGET_MS) {
                E2eeManager.withSession(account.key) { core, sync ->
                    val envelope = data["envelope"]?.let(::parseEnvelope)
                    val message = if (envelope != null) {
                        Message(id = messageId, channel = channelId, author = author, encrypted = envelope)
                    } else {
                        AccountManager.http(account.key)?.use { fetchSingleMessage(channelId, messageId, client = it) }
                            ?: return@withSession null
                    }
                    when (val outcome = E2eePushDecryptor(core, sync).decrypt(message, repliesKnown = envelope == null)) {
                        is E2eePushDecryptor.Outcome.Text -> render(context, outcome, account)
                        else -> null
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "Encrypted push not decrypted: ${e::class.simpleName}" }
            null
        }
    }

    private fun parseEnvelope(json: String): EncryptedEnvelope? = try {
        StoatJson.decodeFromString(EncryptedEnvelope.serializer(), json)
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun render(context: Context, outcome: E2eePushDecryptor.Outcome.Text, account: Account): String? {
        val content = outcome.content?.takeIf { it.isNotBlank() }
        return when {
            content == null && outcome.attachments > 0 -> getStringBlocking(Res.string.durio_push_encrypted_file)
            content == null -> null
            // voice messages carry their metadata JSON as text (C8)
            content.startsWith("{") && "chat.stoat.VoiceMessage" in content -> getStringBlocking(Res.string.durio_push_encrypted_voice)
            else -> mentionsToNames(content, account)
        }
    }

    /** `<@id>` → `@name` where the name is known (the active account's cache), else `@…`. */
    private fun mentionsToNames(content: String, account: Account): String =
        MENTION.replace(content) { match ->
            val id = match.groupValues[1]
            val name = if (AccountManager.active?.key == account.key) StoatAPI.userCache[id]?.username else null
            "@" + (name ?: "…")
        }

    private val MENTION = Regex("<@([0-9A-HJKMNP-TV-Z]{26})>")

    /** Lock screen: the encrypted text stays private, a generic public version is shown (Q65). */
    fun protect(context: Context, builder: NotificationCompat.Builder, channel: String) {
        builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        builder.setPublicVersion(
            NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_stoat_24dp)
                .setContentTitle(getStringBlocking(Res.string.durio_push_new_message))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .build()
        )
    }

    /** The generic line went out: decrypt again later and replace it (ids only, Q38). */
    fun retryLater(context: Context, accountKey: String, channelId: String, messageId: String, authorId: String) {
        val request = OneTimeWorkRequestBuilder<PushRedecryptWorker>()
            .setInputData(
                workDataOf(
                    PushRedecryptWorker.ACCOUNT to accountKey,
                    PushRedecryptWorker.CHANNEL to channelId,
                    PushRedecryptWorker.MESSAGE to messageId,
                    PushRedecryptWorker.AUTHOR to authorId,
                )
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(RETRY_DELAY_S, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("e2ee-push-$accountKey-$messageId", ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Replaces the generic line of [messageId] in the conversation's notification with [text],
     * silently; nothing if the user has dismissed it meanwhile.
     */
    fun replaceInNotification(context: Context, accountKey: String, channelId: String, messageId: String, text: String) {
        val tag = NotificationKeys.tag(accountKey, channelId)
        val manager = NotificationManagerCompat.from(context)
        val active = manager.activeNotifications.firstOrNull { it.tag == tag && it.id == NotificationID.NEW_MESSAGE }
            ?: return
        val old = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(active.notification) ?: return
        val timestamp = try {
            ULID.asTimestamp(messageId)
        } catch (e: IllegalArgumentException) {
            return
        }
        val generic = getStringBlocking(Res.string.durio_push_new_message)
        val style = NotificationCompat.MessagingStyle(old.user)
            .setConversationTitle(old.conversationTitle)
            .setGroupConversation(old.isGroupConversation)
        var replaced = false
        for (m in old.messages) {
            if (!replaced && m.timestamp == timestamp && m.text?.toString() == generic) {
                style.addMessage(NotificationCompat.MessagingStyle.Message(text, m.timestamp, m.person))
                replaced = true
            } else {
                style.addMessage(m)
            }
        }
        if (!replaced) return
        val notification: Notification = NotificationCompat.Builder(context, active.notification)
            .setStyle(style)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .build()
        try {
            manager.notify(tag, NotificationID.NEW_MESSAGE, notification)
        } catch (e: SecurityException) {
            // notification permission revoked meanwhile
        }
    }

    private const val RETRY_DELAY_S = 15L
}

/** Decrypts the message of a push again later (AN-29); input are ids only. */
class PushRedecryptWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val key = inputData.getString(ACCOUNT) ?: return Result.success()
        val channel = inputData.getString(CHANNEL) ?: return Result.success()
        val message = inputData.getString(MESSAGE) ?: return Result.success()
        val author = inputData.getString(AUTHOR) ?: return Result.success()
        val account = AccountManager.accounts().firstOrNull { it.key == key } ?: return Result.success()

        // without the envelope: the message is fetched by id, with its open replies
        val text = PushDecryption.text(
            applicationContext,
            account,
            mapOf("message" to message, "channel" to channel, "author" to author, "encrypted" to "1"),
        )
        if (text == null) return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        PushDecryption.replaceInNotification(applicationContext, key, channel, message, text)
        return Result.success()
    }

    companion object {
        const val ACCOUNT = "account"
        const val CHANNEL = "channel"
        const val MESSAGE = "message"
        const val AUTHOR = "author"
        private const val MAX_ATTEMPTS = 3
    }
}
