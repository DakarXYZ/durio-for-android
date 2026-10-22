package chat.stoat.api.fork.routes

import chat.stoat.api.StoatAPI
import chat.stoat.api.StoatJson
import chat.stoat.api.apiError
import chat.stoat.api.fork.http.ApiClient
import chat.stoat.api.fork.http.api
import chat.stoat.core.model.schemas.Channel
import chat.stoat.core.model.schemas.Server
import io.ktor.client.request.delete
import io.ktor.client.request.patch
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// dur.io servers: channels members make for themselves, and permissions of single members

/** A request about members' own channels the server refused. */
class UserChannelRequestException(val status: Int, message: String) : Exception(message)

private suspend fun ensureOk(response: HttpResponse): String {
    val text = response.bodyAsText()
    if (!response.status.isSuccess()) {
        throw UserChannelRequestException(response.status.value, apiError(text, response.status.value))
    }
    return text
}

/** Most text channels of their own a server lets each member make */
const val MAX_USER_CHANNEL_LIMIT = 3

@Serializable
private data class Override(val allow: Long, val deny: Long)

@Serializable
private data class UserPermissionsBody(val permissions: Override)

@Serializable
private data class UserChannelLimitBody(
    @SerialName("user_channel_limit")
    val userChannelLimit: Int,
)

private fun remember(channel: Channel): Channel {
    channel.id?.let { StoatAPI.channelCache[it] = channel }
    return channel
}

/** `PUT /channels/{id}/permissions/users/{user}`: what one member may do in the channel. */
suspend fun setUserPermissions(
    channelId: String,
    userId: String,
    allow: Long,
    deny: Long,
    client: ApiClient = ApiClient.Active,
): Channel {
    val body = StoatJson.encodeToString(
        UserPermissionsBody.serializer(),
        UserPermissionsBody(Override(allow, deny)),
    )
    val text = ensureOk(client.http.put("/channels/$channelId/permissions/users/$userId".api(client)) {
        setBody(TextContent(body, ContentType.Application.Json))
    })
    return remember(StoatJson.decodeFromString(Channel.serializer(), text))
}

/** `DELETE /channels/{id}/permissions/users/{user}`: drop the member's own permissions. */
suspend fun removeUserPermissions(
    channelId: String,
    userId: String,
    client: ApiClient = ApiClient.Active,
): Channel {
    val text = ensureOk(client.http.delete("/channels/$channelId/permissions/users/$userId".api(client)))
    return remember(StoatJson.decodeFromString(Channel.serializer(), text))
}

/** `PATCH /servers/{id}`: how many text channels of their own each member may make (ManageServer). */
suspend fun setUserChannelLimit(serverId: String, limit: Int, client: ApiClient = ApiClient.Active) {
    val value = limit.coerceIn(0, MAX_USER_CHANNEL_LIMIT)
    val body = StoatJson.encodeToString(UserChannelLimitBody.serializer(), UserChannelLimitBody(value))
    val text = ensureOk(client.http.patch("/servers/$serverId".api(client)) {
        setBody(TextContent(body, ContentType.Application.Json))
    })
    // the server leaves out a limit of 0
    val server = runCatching { StoatJson.decodeFromString(Server.serializer(), text) }.getOrNull()
    val merged = server?.let { StoatAPI.serverCache[serverId]?.mergeWithPartial(it) ?: it }
        ?: StoatAPI.serverCache[serverId]
    merged?.let { StoatAPI.serverCache[serverId] = it.copy(userChannelLimit = value) }
}
