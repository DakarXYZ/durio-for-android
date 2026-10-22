package chat.stoat.internals

import chat.stoat.api.StoatAPI
import chat.stoat.api.internals.PermissionBit
import chat.stoat.api.internals.Roles
import chat.stoat.api.internals.has
import chat.stoat.api.routes.server.fetchMembers
import chat.stoat.composables.chat.AutocompleteSuggestion
import chat.stoat.core.model.schemas.ChannelType

object Autocomplete {
    private val emojiImpl = EmojiImpl()

    /** durio: how many people a mention suggests at most */
    private const val MAX_USERS = 25

    /** durio: servers whose whole member list (offline members too) was fetched */
    private val loadedServers = mutableSetOf<String>()

    /**
     * durio: fetch the member list of a server once, so that mentions suggest everyone, not only
     * the members this client happened to see. True when it was fetched just now.
     */
    suspend fun loadMembers(serverId: String): Boolean {
        synchronized(loadedServers) { if (!loadedServers.add(serverId)) return false }
        return runCatching { fetchMembers(serverId, includeOffline = true) }
            .onFailure { synchronized(loadedServers) { loadedServers.remove(serverId) } }
            .isSuccess
    }

    /** durio: whether [user] (and [member] in a server) matches what was typed after `@` */
    internal fun matches(query: String, username: String?, displayName: String?, nickname: String?): Boolean =
        query.isEmpty() || listOfNotNull(username, displayName, nickname).any { it.contains(query, ignoreCase = true) }

    /** durio: names starting with the query first, then by name */
    internal fun rank(query: String, name: String): Pair<Int, String> =
        (if (name.startsWith(query, ignoreCase = true)) 0 else 1) to name.lowercase()

    fun emoji(query: String): List<AutocompleteSuggestion.Emoji> {
        val unicodeResults = emojiImpl.shortcodeContains(query).map {
            AutocompleteSuggestion.Emoji(
                it.shortcodes.find { shortcode -> shortcode.contains(query, ignoreCase = true) }
                    ?: it.shortcodes.first(),
                it.base.joinToString("") { s -> String(Character.toChars(s.toInt())) },
                null,
                query
            )
        }.distinctBy { it.shortcode }

        val customResults =
            StoatAPI.emojiCache.values.filter {
                it.name?.contains(query, ignoreCase = true) ?: false
            }.mapNotNull {
                if (it.name != null) {
                    AutocompleteSuggestion.Emoji(
                        ":${it.id}:",
                        null,
                        it,
                        query
                    )
                } else {
                    null
                }
            }.distinctBy { it.custom?.id }

        return (unicodeResults + customResults)
    }

    fun userOrRole(
        channelId: String,
        serverId: String? = null,
        query: String
    ): List<AutocompleteSuggestion> {
        val channel = StoatAPI.channelCache[channelId] ?: return emptyList()

        val member = serverId?.let { StoatAPI.members.getMember(serverId, StoatAPI.selfId ?: "") }
        val massMentionSuggestions = listOf("everyone", "online")
            .filter { it.startsWith(query, ignoreCase = true) }
        val hereIsOnlineMnemonic = if (query == "here") listOf("online") else emptyList()

        val selfPermissions = StoatAPI.channelCache[channelId]?.let { ch ->
            Roles.permissionFor(
                ch,
                StoatAPI.userCache[StoatAPI.selfId],
                member
            )
        }

        return when (channel.channelType) {
            ChannelType.DirectMessage -> {
                val otherUser = channel.recipients?.find { it != StoatAPI.selfId }
                if (otherUser != null) {
                    val user = StoatAPI.userCache[otherUser]
                    if (user != null && matches(query, user.username, user.displayName, null)) {
                        listOf(
                            AutocompleteSuggestion.User(
                                user,
                                null,
                                query
                            )
                        )
                    } else {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            }

            ChannelType.Group -> {
                val users =
                    channel.recipients?.mapNotNull { StoatAPI.userCache[it] } ?: emptyList()
                users
                    .filter { matches(query, it.username, it.displayName, null) }
                    .map {
                        AutocompleteSuggestion.User(
                            it,
                            null,
                            query
                        )
                    }
            }

            ChannelType.SavedMessages -> {
                val user = StoatAPI.userCache[StoatAPI.selfId]
                return if (user != null && user.username?.contains(
                        query,
                        ignoreCase = true
                    ) == true
                ) {
                    listOf(
                        AutocompleteSuggestion.User(
                            user,
                            null,
                            query
                        )
                    )
                } else {
                    emptyList()
                }
            }

            ChannelType.TextChannel, ChannelType.VoiceChannel -> {
                if (serverId == null) return emptyList()

                val roles =
                    if (selfPermissions has PermissionBit.MentionRoles) StoatAPI.serverCache[serverId]?.roles
                        ?: emptyMap() else emptyMap()
                // durio: every cached member (the list is fetched when `@` is typed), by username,
                // display name or nickname, from the first letter on
                val allUsers = StoatAPI.members.membersOf(serverId)
                    .mapNotNull { m -> StoatAPI.userCache[m.id?.user]?.let { u -> m to u } }
                    .filter { (m, u) -> matches(query, u.username, u.displayName, m.nickname) }
                    .sortedBy { (m, u) -> rank(query, m.nickname ?: u.displayName ?: u.username ?: "").let { "${it.first}${it.second}" } }
                    .take(MAX_USERS)
                val rolesByName =
                    roles.filter { it.value.name?.contains(query, ignoreCase = true) == true }
                        .map { it.value to it.key }


                (allUsers.map {
                    AutocompleteSuggestion.User(
                        it.second,
                        it.first,
                        query
                    )
                } + rolesByName.map { (role, roleId) ->
                    AutocompleteSuggestion.Role(
                        role,
                        roleId,
                        query
                    )
                })
            }

            null -> emptyList()
        } + if (selfPermissions has PermissionBit.MentionEveryone) (massMentionSuggestions + hereIsOnlineMnemonic).map { mention ->
            AutocompleteSuggestion.MassMention(mention)
        } else listOf()
    }

    fun channel(
        serverId: String,
        query: String
    ): List<AutocompleteSuggestion.Channel> {
        val server = StoatAPI.serverCache[serverId] ?: return emptyList()
        val channels = server.channels?.mapNotNull { StoatAPI.channelCache[it] } ?: emptyList()

        return channels.filter { it.name?.contains(query, ignoreCase = true) == true }.map {
            AutocompleteSuggestion.Channel(
                it,
                query
            )
        }
    }
}