package chat.stoat.api.fork.e2ee

import chat.stoat.api.StoatAPI
import chat.stoat.core.model.schemas.Channel
import chat.stoat.core.model.schemas.ChannelType

/**
 * Which DMs and groups of the active account are encrypted (plan AN-26, AN-27). Server and
 * guild channels never are.
 */
object E2eeChannels {
    fun isEncryptable(channel: Channel?): Boolean =
        channel?.channelType == ChannelType.DirectMessage || channel?.channelType == ChannelType.Group

    /** Whether messages to [channelId] must be encrypted. */
    fun isEncrypted(channelId: String?): Boolean {
        if (channelId == null) return false
        return isEncrypted(StoatAPI.channelCache[channelId])
    }

    fun isEncrypted(channel: Channel?): Boolean = isEncryptable(channel) && channel?.encryption != null

    /** Members for the core: the channel's recipients (the core adds the own user itself). */
    fun members(channel: Channel): List<String> = channel.recipients.orEmpty()

    /** In a DM, the other user; null in a group. */
    fun partner(channel: Channel, selfId: String?): String? =
        if (channel.channelType == ChannelType.DirectMessage) channel.recipients?.firstOrNull { it != selfId } else null

    /** Encrypted DMs/groups in the cache with their members (after `Ready`, contract step 5). */
    fun encryptedWithMembers(): List<Pair<String, List<String>>> =
        StoatAPI.channelCache.values.filter { isEncrypted(it) && it.id != null }.map { it.id!! to members(it) }
}
