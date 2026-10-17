package chat.stoat.api.fork.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlin.concurrent.Volatile

/**
 * Which KV keys belong to an account and which to the device (plan AN-08).
 *
 * Upstream keeps everything in one `revolt_kv` file. The file stays where it is and keeps the
 * device keys (`fcmToken`, `pushNotificationsRejected`, `keyboardHeight`, `experimentsEnabled`,
 * `exp/` and `spark/` flags, `ageGateUnlocked`); the keys below go to `kv_<accountKey>` of the
 * active account. `KVStorage(context)` routes each call by its key, so upstream callers work
 * unchanged.
 */
object AccountKv {
    private val ACCOUNT_KEYS = setOf(
        "sessionToken",
        "sessionId",
        "selfId",
        "selfName",
        "selfAvatarUrl",
        "currentDestination",
        "mentionOnReply",
    )
    private val ACCOUNT_PREFIXES = listOf("lastChannel/", "draftContent/")

    val accountKeys: Set<String>
        get() = ACCOUNT_KEYS

    fun isAccountScoped(key: String): Boolean =
        key in ACCOUNT_KEYS || ACCOUNT_PREFIXES.any { key.startsWith(it) }

    /**
     * The KV file of the active account; null before login (and while an old install waits for
     * its migration), when account keys stay in the device file as upstream had them.
     */
    @Volatile
    var active: DataStore<Preferences>? = null

    fun storeFor(key: String, device: DataStore<Preferences>): DataStore<Preferences> =
        active?.takeIf { isAccountScoped(key) } ?: device
}
