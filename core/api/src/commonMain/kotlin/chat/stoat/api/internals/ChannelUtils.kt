package chat.stoat.api.internals

import chat.stoat.api.StoatAPI
import chat.stoat.core.model.schemas.Channel
import chat.stoat.core.model.schemas.Server
import chat.stoat.core.model.schemas.User

sealed class CategorisedChannelList {
    data class Channel(val channel: chat.stoat.core.model.schemas.Channel) :
        CategorisedChannelList()

    data class Category(val category: chat.stoat.core.model.schemas.Category) :
        CategorisedChannelList()

    // durio: "Channels <member>" over the channels a member made for themselves
    data class UserChannels(val ownerId: String) : CategorisedChannelList()
}

object ChannelUtils {
    /**
     * Resolves the name of a channel, preferring the name of the channel itself, then the name of the first recipient.
     * @param channel The channel to resolve the name of.
     * @return The name of the channel, or the name of the first recipient if the channel is a DM.
     * @see User.resolveDefaultName
     */
    fun resolveName(channel: Channel): String? {
        return channel.name
            ?: StoatAPI.userCache[channel.recipients?.first { u -> u != StoatAPI.selfId }]?.let {
                User.resolveDefaultName(
                    it
                )
            }
    }

    fun resolveDMPartner(channel: Channel): String? {
        return channel.recipients?.firstOrNull { u -> u != StoatAPI.selfId }
    }

    fun categoriseServerFlat(server: Server): List<CategorisedChannelList> {
        val output = mutableListOf<CategorisedChannelList>()

        val uncategorised =
            server.channels?.filter { c ->
                server.categories?.none { cat ->
                    cat.channels?.contains(
                        c
                    ) == true
                } ?: true
            }
                ?.mapNotNull {
                    StoatAPI.channelCache[it]?.let { it1 ->
                        CategorisedChannelList.Channel(it1)
                    }
                } ?: emptyList()
        output.addAll(uncategorised)

        val categories =
            server.categories?.map { CategorisedChannelList.Category(it) } ?: emptyList()
        categories.forEach {
            output.add(it)
            val channels = it.category.channels?.mapNotNull { c ->
                StoatAPI.channelCache[c]?.let { it1 ->
                    CategorisedChannelList.Channel(it1)
                }
            } ?: emptyList()
            output.addAll(channels)
        }

        // durio: members' own channels go out of the categories, grouped by member
        val own = output.filterIsInstance<CategorisedChannelList.Channel>()
            .filter { userChannelOwner(it.channel) != null }
        if (own.isEmpty()) return output

        output.removeAll(own)
        own.groupBy { userChannelOwner(it.channel)!! }.forEach { (ownerId, channels) ->
            output.add(CategorisedChannelList.UserChannels(ownerId))
            output.addAll(channels)
        }

        return output
    }

    /**
     * durio: the member who made this server channel for themselves, if anyone did
     */
    fun userChannelOwner(channel: Channel): String? =
        channel.owner.takeIf { channel.channelType == chat.stoat.core.model.schemas.ChannelType.TextChannel && channel.thread == null }

    /**
     * durio: the channels each member made for themselves on a server
     */
    fun userChannels(server: Server): Map<String, List<Channel>> =
        server.channels.orEmpty()
            .mapNotNull { StoatAPI.channelCache[it] }
            .filter { userChannelOwner(it) != null }
            .groupBy { userChannelOwner(it)!! }

    /**
     * durio: whether we may make a channel of our own on this server
     * @param serverPermissions Our permissions on the server
     */
    fun canMakeOwnChannel(server: Server, serverPermissions: Long?): Boolean {
        val selfId = StoatAPI.selfId ?: return false
        if (serverPermissions == null || serverPermissions.hasPermission(PermissionBit.ManageChannel)) {
            return false
        }
        val limit = server.userChannelLimit ?: 0
        return userChannels(server)[selfId].orEmpty().size < limit
    }
}
