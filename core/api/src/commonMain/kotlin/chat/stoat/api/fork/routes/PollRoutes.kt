package chat.stoat.api.fork.routes

import chat.stoat.api.StoatJson
import chat.stoat.api.apiError
import chat.stoat.api.fork.http.ApiClient
import chat.stoat.api.fork.http.api
import chat.stoat.api.internals.ULID
import chat.stoat.api.routes.channel.SendMessageReply
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

/** A poll to send (dur.io servers): 1–300 characters of question, 2–10 answers of 1–100. */
@Serializable
data class PollDraft(
    val question: String,
    val answers: List<String>,
    val multiple: Boolean = false,
) {
    companion object {
        const val MAX_QUESTION = 300
        const val MAX_ANSWER = 100
        const val MIN_ANSWERS = 2
        const val MAX_ANSWERS = 10
    }

    /** Trimmed, without blank answers; null when it would be refused. */
    fun cleaned(): PollDraft? {
        val q = question.trim()
        val a = answers.map { it.trim() }.filter { it.isNotEmpty() }
        if (q.isEmpty() || q.length > MAX_QUESTION) return null
        if (a.size !in MIN_ANSWERS..MAX_ANSWERS || a.any { it.length > MAX_ANSWER }) return null
        return PollDraft(q, a, multiple)
    }
}

@Serializable
private data class SendPollBody(
    val poll: PollDraft,
    val nonce: String,
    val replies: List<SendMessageReply> = emptyList(),
)

@Serializable
private data class PollVoteBody(val answers: List<String>)

/** A request about a poll the server refused: its error type (e.g. `EncryptedChannelViolation`). */
class PollRequestException(val status: Int, message: String) : Exception(message)

private suspend fun checked(response: HttpResponse) {
    if (!response.status.isSuccess()) {
        throw PollRequestException(response.status.value, apiError(response.bodyAsText(), response.status.value))
    }
}

/** `POST /channels/{id}/messages` with a poll and no text (not in encrypted channels). */
suspend fun sendPoll(
    channelId: String,
    poll: PollDraft,
    replies: List<SendMessageReply> = emptyList(),
    client: ApiClient = ApiClient.Active,
) {
    val nonce = ULID.makeNext()
    val body = StoatJson.encodeToString(SendPollBody.serializer(), SendPollBody(poll, nonce, replies))
    checked(client.http.post("/channels/$channelId/messages".api(client)) {
        setBody(TextContent(body, ContentType.Application.Json))
        header("Idempotency-Key", nonce)
    })
}

/** `PUT .../poll`: [answers] become the user's picks; empty takes the vote back. */
suspend fun votePoll(channelId: String, messageId: String, answers: List<String>, client: ApiClient = ApiClient.Active) {
    val body = StoatJson.encodeToString(PollVoteBody.serializer(), PollVoteBody(answers))
    checked(client.http.put("/channels/$channelId/messages/$messageId/poll".api(client)) {
        setBody(TextContent(body, ContentType.Application.Json))
    })
}

/** `POST .../poll/close`: the poll takes no more votes (its author, or ManageMessages). */
suspend fun closePoll(channelId: String, messageId: String, client: ApiClient = ApiClient.Active) {
    checked(client.http.post("/channels/$channelId/messages/$messageId/poll/close".api(client)))
}
