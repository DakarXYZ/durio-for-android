package chat.stoat.api.fork.routes

import chat.stoat.api.StoatAPI
import chat.stoat.api.StoatJson
import chat.stoat.api.apiError
import chat.stoat.api.fork.http.ApiClient
import chat.stoat.api.fork.http.api
import chat.stoat.core.model.fork.ThreadInfo
import chat.stoat.core.model.schemas.Channel
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** A request about a thread or spoiler the server refused (dur.io servers). */
class ThreadRequestException(val status: Int, message: String) : Exception(message)

private suspend fun ensureOk(response: HttpResponse): String {
    val text = response.bodyAsText()
    if (!response.status.isSuccess()) {
        throw ThreadRequestException(response.status.value, apiError(text, response.status.value))
    }
    return text
}

@Serializable
private data class CreateThreadBody(val name: String, val message: String? = null)

@Serializable
private data class EditThreadBody(val name: String? = null, val archived: Boolean? = null)

@Serializable
private data class SpoilerBody(val kind: String)

/** Longest thread name the server takes */
const val MAX_THREAD_NAME = 100

/**
 * A name for a thread from the text of its message: the first non-blank line without mentions
 * and markup, cut to [MAX_THREAD_NAME].
 */
fun threadNameFrom(content: String?): String {
    val line = (content ?: "")
        .replace(Regex("<[@#%][^>]+>"), "")
        .replace(Regex("[*_~`|>#]+"), "")
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        ?: ""
    return if (line.length > MAX_THREAD_NAME) line.take(MAX_THREAD_NAME - 1) + "…" else line
}

private fun remember(channel: Channel): Channel {
    channel.id?.let { StoatAPI.channelCache[it] = channel }
    return channel
}

/** `POST /channels/{parent}/threads`: a thread from [messageId], or hanging off a system message. */
suspend fun createThread(
    parentId: String,
    name: String,
    messageId: String? = null,
    client: ApiClient = ApiClient.Active,
): Channel {
    val body = StoatJson.encodeToString(CreateThreadBody.serializer(), CreateThreadBody(name.trim(), messageId))
    val text = ensureOk(client.http.post("/channels/$parentId/threads".api(client)) {
        setBody(TextContent(body, ContentType.Application.Json))
    })
    return remember(StoatJson.decodeFromString(Channel.serializer(), text))
}

/** `GET /channels/{parent}/threads`: all threads of a channel, archived ones too. */
suspend fun fetchThreads(parentId: String, client: ApiClient = ApiClient.Active): List<Channel> {
    val text = ensureOk(client.http.get("/channels/$parentId/threads".api(client)))
    return StoatJson.decodeFromString(ListSerializer(Channel.serializer()), text).map(::remember)
}

/** `PATCH /channels/{thread}/thread`: rename or (un)archive (its starter, or ManageChannel). */
suspend fun editThread(
    threadId: String,
    name: String? = null,
    archived: Boolean? = null,
    client: ApiClient = ApiClient.Active,
): Channel {
    val body = StoatJson.encodeToString(EditThreadBody.serializer(), EditThreadBody(name, archived))
    val text = ensureOk(client.http.patch("/channels/$threadId/thread".api(client)) {
        setBody(TextContent(body, ContentType.Application.Json))
    })
    return remember(StoatJson.decodeFromString(Channel.serializer(), text))
}

/** `PUT .../spoiler`: a moderator hides a message behind a spoiler (`spoiler` or `nsfw`). */
suspend fun setMessageSpoiler(
    channelId: String,
    messageId: String,
    kind: String,
    client: ApiClient = ApiClient.Active,
) {
    val body = StoatJson.encodeToString(SpoilerBody.serializer(), SpoilerBody(kind))
    ensureOk(client.http.put("/channels/$channelId/messages/$messageId/spoiler".api(client)) {
        setBody(TextContent(body, ContentType.Application.Json))
    })
}

/** `DELETE .../spoiler`: take the message out from behind its spoiler. */
suspend fun removeMessageSpoiler(channelId: String, messageId: String, client: ApiClient = ApiClient.Active) {
    ensureOk(client.http.delete("/channels/$channelId/messages/$messageId/spoiler".api(client)))
}

/** The threads of a channel in the cache, most recently active first. */
fun cachedThreads(parentId: String): List<Channel> =
    StoatAPI.channelCache.values
        .filter { it.thread?.parent == parentId }
        .sortedByDescending { it.lastMessageID ?: it.id ?: "" }

/** Whether a cached thread counts as archived now (by hand, or a week without messages). */
fun isArchivedThread(channel: Channel, nowMs: Long): Boolean {
    val thread: ThreadInfo = channel.thread ?: return false
    val last = (channel.lastMessageID ?: channel.id)?.let {
        runCatching { chat.stoat.api.internals.ULID.asTimestamp(it) }.getOrNull()
    } ?: nowMs
    return ThreadInfo.isArchived(thread, last, nowMs)
}
