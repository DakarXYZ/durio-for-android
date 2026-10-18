package chat.stoat.api.fork.e2ee

import androidx.compose.runtime.mutableStateMapOf
import chat.stoat.api.StoatAPI
import chat.stoat.api.StoatAPIHost
import chat.stoat.core.model.schemas.Channel
import chat.stoat.core.model.schemas.ChannelType
import chat.stoat.persistence.Database
import co.touchlab.kermit.Logger
import kotlin.concurrent.Volatile

/**
 * Which DMs and groups of the active account are encrypted (plan AN-26, AN-27, AN-39). Server
 * and guild channels never are.
 *
 * A channel seen with `encryption` once is **pinned** in the account's cache database
 * (`EncryptedChannel`) and stays encrypted for this client whatever the server says later — a
 * server that drops the flag (downgrade) only gets a warning ([isDowngraded]), and before
 * `Ready` (channels hydrated from SQLite without the flag) nothing goes out in plaintext.
 */
object E2eeChannels {
    /** Where pins live; the account's cache database by default. */
    interface PinStore {
        /** Changes with the account (its database). */
        val identity: Any
        fun load(): Set<String>
        fun pin(channelId: String)
    }

    @Volatile
    var store: () -> PinStore? = ::databaseStore

    private val pinned = mutableStateMapOf<String, Boolean>()
    private val downgraded = mutableStateMapOf<String, Boolean>()

    @Volatile
    private var loadedFor: Any? = null

    fun isEncryptable(channel: Channel?): Boolean =
        channel?.channelType == ChannelType.DirectMessage || channel?.channelType == ChannelType.Group

    /** Whether messages to [channelId] must be encrypted. */
    fun isEncrypted(channelId: String?): Boolean {
        if (channelId == null) return false
        val channel = StoatAPI.channelCache[channelId]
        if (channel != null && !isEncryptable(channel)) return false
        return channel?.encryption != null || isPinned(channelId)
    }

    fun isEncrypted(channel: Channel?): Boolean {
        if (!isEncryptable(channel)) return false
        return channel?.encryption != null || channel?.id?.let(::isPinned) == true
    }

    /** Pinned as encrypted, but the server sent it without `encryption` (AN-39 warning). */
    fun isDowngraded(channelId: String?): Boolean = channelId != null && downgraded[channelId] == true

    /**
     * A channel as the server sent it (`Ready`, `ChannelCreate`, `ChannelUpdate`, a fetch).
     * Returns true when it became encrypted just now: its members then go to the core.
     */
    fun observe(channel: Channel): Boolean {
        val id = channel.id ?: return false
        if (!isEncryptable(channel)) return false
        val wasPinned = isPinned(id)
        if (channel.encryption == null) {
            if (wasPinned) {
                if (downgraded[id] != true) Logger.w { "E2EE: server sent encrypted channel $id without encryption" }
                downgraded[id] = true
            }
            return false
        }
        downgraded.remove(id)
        if (wasPinned) return false
        pinned[id] = true
        try {
            store()?.pin(id)
        } catch (e: Exception) {
            Logger.w { "E2EE: channel pin not stored: ${e::class.simpleName}" }
        }
        return true
    }

    /** Members for the core: the channel's recipients (the core adds the own user itself). */
    fun members(channel: Channel): List<String> = channel.recipients.orEmpty()

    /** In a DM, the other user; null in a group. */
    fun partner(channel: Channel, selfId: String?): String? =
        if (channel.channelType == ChannelType.DirectMessage) channel.recipients?.firstOrNull { it != selfId } else null

    /** Encrypted DMs/groups in the cache with their members (after `Ready`, contract step 5). */
    fun encryptedWithMembers(): List<Pair<String, List<String>>> =
        StoatAPI.channelCache.values.filter { isEncrypted(it) && it.id != null }.map { it.id!! to members(it) }

    private fun isPinned(channelId: String): Boolean {
        ensureLoaded()
        return pinned[channelId] == true
    }

    private fun ensureLoaded() {
        val store = try {
            store()
        } catch (e: Exception) {
            null
        } ?: return
        if (loadedFor === store.identity) return
        loadedFor = store.identity
        pinned.clear()
        downgraded.clear()
        try {
            store.load().forEach { pinned[it] = true }
        } catch (e: Exception) {
            Logger.w { "E2EE: channel pins not loaded: ${e::class.simpleName}" }
        }
    }

    private fun databaseStore(): PinStore? {
        val driver = try {
            StoatAPIHost.storage.sqlDriver
        } catch (e: UninitializedPropertyAccessException) {
            return null
        }
        return object : PinStore {
            override val identity: Any = driver
            override fun load() = Database(driver).encryptedChannelQueries.selectAll().executeAsList().toSet()
            override fun pin(channelId: String) = Database(driver).encryptedChannelQueries.pin(channelId).let { }
        }
    }
}
