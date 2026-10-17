package chat.stoat.fork.push

import chat.stoat.c2dm.ChannelRegistrator
import chat.stoat.fork.account.Account
import chat.stoat.fork.account.accountKey

/**
 * One push, whatever brought it (plan AN-12, Q43): FCM now, maybe UnifiedPush later. All values
 * are strings (C11).
 */
data class PushMessage(val data: Map<String, String>)

/** Whose push this is. */
sealed interface PushTarget {
    data class Bound(val account: Account) : PushTarget

    /** No `recipient` (a server without BE-30): shown as "New message", no requests. */
    data object Unbound : PushTarget

    /** For an account that is not (or no longer) on this device. */
    data object Drop : PushTarget
}

/**
 * Finds the account a push is for (BE-30, C11): `instance` is the canonical API URL and
 * `recipient` the user ID, so the pair gives the account key. Two accounts on one server share
 * the FCM token, which is why `recipient` is required.
 *
 * Without `instance` (a server older than BE-30 but with `recipient`) the user ID alone decides,
 * but only when exactly one account has it.
 */
fun resolveAccount(message: PushMessage, accounts: List<Account>): PushTarget {
    if (accounts.isEmpty()) return PushTarget.Drop
    val recipient = message.data["recipient"]?.takeIf { it.isNotEmpty() } ?: return PushTarget.Unbound
    val instance = message.data["instance"]?.takeIf { it.isNotEmpty() }

    val account = if (instance != null) {
        val key = accountKey(instance, recipient)
        accounts.firstOrNull { it.key == key }
    } else {
        accounts.singleOrNull { it.userId == recipient }
    }
    return account?.let { PushTarget.Bound(it) } ?: PushTarget.Drop
}

/**
 * Names of everything a push leaves in the system, per account (plan AN-12): two accounts must
 * never replace or cancel each other's notifications, and logout (AN-11) finds them by prefix.
 */
object NotificationKeys {
    const val EXTRA_ACCOUNT_KEY = "accountKey"

    /** Notification ID of the per-account group summary (messages use `NotificationID.NEW_MESSAGE`). */
    const val SUMMARY_ID = 1

    fun tag(key: String, channelId: String) = "$key:$channelId"

    fun summaryTag(key: String) = "$key:summary"

    fun isOfAccount(tag: String?, key: String) = tag?.startsWith("$key:") == true

    /** PendingIntent request code; [action] tells apart the intents of one conversation. */
    fun requestCode(key: String, channelId: String, action: Int = 0) = tag(key, channelId).hashCode() xor action

    fun shortcutId(applicationId: String, key: String, channelId: String) = "$applicationId.$key.channel.$channelId"

    /** `NotificationChannelGroup` of the account: the user can mute it as a whole. */
    fun channelGroup(key: String) = "acc.$key"

    /**
     * Message channel of the account. The account migrated from a single-account install keeps
     * upstream's channel and with it the user's sound settings (Q41).
     */
    fun messagesChannel(key: String, legacyKey: String?) =
        if (key == legacyKey) ChannelRegistrator.CHANNEL_ID_GROUP_CONVERSATIONS_MESSAGES else "acc.$key.messages"
}
