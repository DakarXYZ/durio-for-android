package chat.stoat.fork.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import chat.stoat.R
import chat.stoat.activities.MainActivity
import chat.stoat.c2dm.ChannelRegistrator
import chat.stoat.c2dm.NotificationID
import chat.stoat.fork.account.Account
import chat.stoat.internals.extensions.getStringBlocking
import chat.stoat.persistence.KVStorage
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.durio_push_new_message
import chat.stoat.shared.resources.notification_channel_group_conversations
import chat.stoat.shared.resources.notification_channel_messages
import chat.stoat.shared.resources.notification_channel_messages_description

/**
 * Notification channels, groups and summaries per account (plan AN-12). Channels of background
 * accounts are created here, lazily: `ChannelRegistrator` only runs for the active account.
 */
object PushNotifications {
    /** Device KV: the account that took over upstream's message channel (Q41). */
    private const val LEGACY_CHANNEL_ACCOUNT = "durio/legacyChannelAccount"

    suspend fun rememberLegacyChannelAccount(context: Context, key: String) {
        KVStorage(context).set(LEGACY_CHANNEL_ACCOUNT, key)
    }

    /** "alice @ duri0.duckdns.org": tells accounts apart in the shade and in system settings. */
    fun label(account: Account) = "${account.displayName ?: account.userId} @ ${account.host}"

    /** Creates (idempotent) and returns the account's message channel. */
    suspend fun messagesChannel(context: Context, account: Account): String {
        val legacyKey = KVStorage(context).get(LEGACY_CHANNEL_ACCOUNT)
        val id = NotificationKeys.messagesChannel(account.key, legacyKey)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(id) != null) return id

        val group = if (id == ChannelRegistrator.CHANNEL_ID_GROUP_CONVERSATIONS_MESSAGES) {
            NotificationChannelGroup(
                ChannelRegistrator.CHANNEL_ID_GROUP_CONVERSATIONS,
                getStringBlocking(Res.string.notification_channel_group_conversations)
            )
        } else {
            NotificationChannelGroup(NotificationKeys.channelGroup(account.key), label(account))
        }
        manager.createNotificationChannelGroup(group)
        manager.createNotificationChannel(
            NotificationChannel(
                id,
                getStringBlocking(Res.string.notification_channel_messages),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                this.group = group.id
                description = getStringBlocking(Res.string.notification_channel_messages_description)
            }
        )
        return id
    }

    /** Group summary, so the account's conversations fold together (Android shows ~50 per app). */
    fun postSummary(context: Context, account: Account, channelId: String) {
        if (!canNotify(context)) return
        val summary = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stoat_24dp)
            .setContentTitle(label(account))
            .setSubText(label(account))
            .setGroup(NotificationKeys.channelGroup(account.key))
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setContentIntent(openAccount(context, account.key))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context)
            .notify(NotificationKeys.summaryTag(account.key), NotificationKeys.SUMMARY_ID, summary)
    }

    /** Removes a conversation's notification, and the summary once nothing is left under it. */
    fun cancelConversation(context: Context, key: String, channelId: String) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(NotificationKeys.tag(key, channelId), NotificationID.NEW_MESSAGE)
        // posted before AN-12, without the account in the tag
        manager.cancel(channelId, NotificationID.NEW_MESSAGE)

        val summaryTag = NotificationKeys.summaryTag(key)
        val left = context.getSystemService(NotificationManager::class.java).activeNotifications
            .count { NotificationKeys.isOfAccount(it.tag, key) && it.tag != summaryTag }
        if (left == 0) manager.cancel(summaryTag, NotificationKeys.SUMMARY_ID)
    }

    /**
     * A push without `recipient` (server older than BE-30): the account is unknown, so no
     * requests and no actions, only a hint to open the app.
     */
    fun showUnbound(context: Context, data: Map<String, String>) {
        if (!canNotify(context)) return
        ChannelRegistrator(context).register()
        val notification = NotificationCompat.Builder(
            context,
            ChannelRegistrator.CHANNEL_ID_GROUP_CONVERSATIONS_MESSAGES
        )
            .setSmallIcon(R.drawable.ic_stoat_24dp)
            .setContentTitle(data["author_name"])
            .setContentText(getStringBlocking(Res.string.durio_push_new_message))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openAccount(context, key = null))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(UNBOUND_TAG, NotificationID.NEW_MESSAGE, notification)
    }

    private fun openAccount(context: Context, key: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            if (key != null) putExtra(NotificationKeys.EXTRA_ACCOUNT_KEY, key)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        return PendingIntent.getActivity(
            context,
            (key ?: UNBOUND_TAG).hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun canNotify(context: Context) =
        ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private const val UNBOUND_TAG = "unbound"
}
