package chat.stoat.fork.messages

import chat.stoat.api.StoatAPI
import chat.stoat.api.internals.isUlid
import chat.stoat.api.routes.server.fetchMember
import chat.stoat.api.routes.user.fetchUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The users a mention (`<@id>`) points at who are not known yet: fetched once, so the mention
 * shows their name instead of their id (the user cache is observed, the text redraws).
 */
object MentionNames {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requested = mutableSetOf<String>()

    fun request(userId: String, serverId: String?) {
        if (!userId.isUlid()) return
        val key = "$serverId/$userId"
        synchronized(requested) { if (!requested.add(key)) return }

        scope.launch {
            if (serverId != null && !StoatAPI.members.hasMember(serverId, userId)) {
                runCatching { fetchMember(serverId, userId) }
            }
            if (StoatAPI.userCache[userId] == null) {
                runCatching { fetchUser(userId) }
            }
        }
    }
}
