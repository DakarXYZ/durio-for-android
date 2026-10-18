package chat.stoat.api.fork.routes

import chat.stoat.api.StoatJson
import chat.stoat.api.fork.http.ApiClient
import chat.stoat.api.fork.http.api
import chat.stoat.api.routes.channel.SendMessageReply
import chat.stoat.core.model.fork.EncryptedEnvelope
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The server refused an encrypted send or edit (shapes: 10-backend "Заметки реализации этапа 2").
 * [type] is the server's error type: `EncryptedChannelViolation` (400), `PayloadTooLarge` (422),
 * `NotFound` (404: an attachment's `encrypted` flag does not match the channel).
 */
class EncryptedSendException(val status: Int, val type: String?) : Exception("Encrypted send refused: ${type ?: status}")

@Serializable
private class EncryptedSendBody(
    val encrypted: EncryptedEnvelope,
    val nonce: String,
    val replies: List<SendMessageReply>,
    /** autumn ids of the encrypted blobs **and** their thumbnails (C9). */
    val attachments: List<String>?,
)

@Serializable
private class EncryptedEditBody(val encrypted: EncryptedEnvelope)

/**
 * `POST /channels/{id}/messages` with `encrypted` instead of `content` (plan AN-26): no
 * `content`, `embeds`, `masquerade` or mentions (C10). The envelope is encrypted once and the
 * same body is retried by [MessageOutbox] (block 5E): the Megolm index is not spent twice, and a
 * repeat of the nonce is answered with the stored message.
 *
 * @throws EncryptedSendException refused (4xx), or 5xx after every retry
 */
suspend fun sendEncryptedMessage(
    channelId: String,
    envelope: EncryptedEnvelope,
    nonce: String,
    replies: List<SendMessageReply> = emptyList(),
    attachments: List<String>? = null,
    idempotencyKey: String = nonce,
    resend: Boolean = false,
    selfId: String? = null,
    client: ApiClient = ApiClient.Active,
    retry: SendRetry = SendRetry(),
): SentMessage {
    val body = EncryptedSendBody(envelope, nonce, replies, attachments?.takeIf { it.isNotEmpty() })
    return try {
        MessageOutbox.send(
            channelId = channelId,
            nonce = nonce,
            body = StoatJson.encodeToString(EncryptedSendBody.serializer(), body),
            idempotencyKey = idempotencyKey,
            resend = resend,
            selfId = selfId,
            client = client,
            retry = retry,
        )
    } catch (e: MessageSendException) {
        throw EncryptedSendException(e.status, e.type)
    }
}

/** `PATCH /channels/{id}/messages/{msg}` with only `encrypted` (plan AN-26). */
suspend fun editEncryptedMessage(
    channelId: String,
    messageId: String,
    envelope: EncryptedEnvelope,
    client: ApiClient = ApiClient.Active,
) {
    val response = client.http.patch("/channels/$channelId/messages/$messageId".api(client)) {
        setBody(TextContent(StoatJson.encodeToString(EncryptedEditBody.serializer(), EncryptedEditBody(envelope)), ContentType.Application.Json))
    }
    if (!response.status.isSuccess()) {
        throw EncryptedSendException(response.status.value, errorType(response.bodyAsText()))
    }
}

internal fun errorType(body: String): String? = try {
    Json.parseToJsonElement(body).jsonObject["type"]?.jsonPrimitive?.contentOrNull
} catch (_: Exception) {
    null
}
