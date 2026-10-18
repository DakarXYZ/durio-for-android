package chat.stoat.api.fork.routes

import chat.stoat.api.StoatJson
import chat.stoat.api.fork.e2ee.E2eeMessages
import chat.stoat.api.fork.http.ApiClient
import chat.stoat.api.fork.http.api
import chat.stoat.api.fork.routes.e2ee.retryAfterMs
import chat.stoat.api.routes.channel.SendMessageBody
import chat.stoat.api.routes.channel.SendMessageReply
import chat.stoat.core.model.schemas.Message
import co.touchlab.kermit.Logger
import io.ktor.client.plugins.retry
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.ListSerializer
import kotlin.random.Random

/**
 * What a send ended with. [message] is the server's copy (null when its answer could not be
 * read), decrypted for the active account when [recovered]; [raw] is the answer as upstream
 * `sendMessage` returned it. [recovered]: the message went through only on a retry or was found
 * by its nonce — its socket event may have been missed, the caller shows [message] itself.
 */
class SentMessage(val message: Message?, val raw: String, val recovered: Boolean)

/** The server refused a send for good ([status] 4xx), or still failed after every retry (5xx). */
class MessageSendException(val status: Int, val type: String?, val body: String) :
    Exception("Message send failed: ${type ?: status}")

/**
 * Retries of one send: [retries] after the first attempt, the pause doubling from
 * [initialDelayMs] up to [maxDelayMs] (plus up to a quarter of jitter, so clients do not
 * come back together after a server restart).
 */
class SendRetry(
    val retries: Int = 5,
    val initialDelayMs: Long = 1_000,
    val maxDelayMs: Long = 16_000,
    val jitter: Boolean = true,
) {
    internal fun delayMs(retry: Int): Long {
        val base = (initialDelayMs shl (retry - 1).coerceIn(0, 20)).coerceAtMost(maxDelayMs)
        return if (jitter && base >= 4) base + Random.nextLong(base / 4) else base
    }
}

/**
 * The outbox of every new message, plain and encrypted (block 5E, `stoat-crypto/docs/load-chaos.md`
 * B2): one body, one `nonce`, sent again as is.
 *
 * - The `Idempotency-Key` header is the nonce. The server answers a repeat of either from the
 *   same author in the same channel with the message the first attempt stored (200), so a lost
 *   answer does not make a second message. An encrypted body keeps its envelope: it is encrypted
 *   once, a retry never spends another Megolm index on the same message.
 * - Network errors, 5xx and 429 are retried ([SendRetry]); the client's own retry plugin is off
 *   for this request, the outbox counts attempts itself.
 * - `400 InvalidOperation` / `409` after a retry (racing the first attempt, or the server's nonce
 *   cache lost in a restart) mean "probably sent": the channel's latest messages are read and
 *   the message is found by its nonce. So are retries that ran out. Only a message not found
 *   is an error.
 */
object MessageOutbox {
    /** How many latest messages are searched for the nonce. */
    internal const val LOOKUP_LIMIT = 50

    /** Pauses before each lookup: the first attempt may still be on its way into the database. */
    internal val LOOKUP_DELAYS_MS = longArrayOf(0, 2_000, 4_000)

    /**
     * `POST /channels/{id}/messages` with [body] (JSON). [resend]: an earlier run already sent
     * this nonce (a job retried by WorkManager), so a duplicate answer is "probably sent" right
     * away. [selfId] narrows the nonce lookup to own messages.
     *
     * @throws MessageSendException refused, or a server error after every retry, and not found
     * @throws Exception the transport error of the last attempt, and not found
     */
    suspend fun send(
        channelId: String,
        nonce: String,
        body: String,
        idempotencyKey: String = nonce,
        resend: Boolean = false,
        selfId: String? = null,
        client: ApiClient = ApiClient.Active,
        retry: SendRetry = SendRetry(),
    ): SentMessage {
        var attempt = 0
        var failure: Exception
        while (true) {
            var waitMs: Long? = null
            failure = try {
                val response = client.http.post("/channels/$channelId/messages".api(client)) {
                    retry { noRetry() }
                    setBody(TextContent(body, ContentType.Application.Json))
                    header("Idempotency-Key", idempotencyKey)
                }
                val text = response.bodyAsText()
                val status = response.status.value
                if (response.status.isSuccess()) return sent(text, recovered = attempt > 0, client)

                val refused = MessageSendException(status, errorType(text), text)
                when {
                    status >= 500 -> refused
                    status == 429 -> refused.also { waitMs = retryAfter(response, text) }
                    isDuplicate(refused) && (attempt > 0 || resend) -> {
                        Logger.i { "Send answered $status after a retry: looking the message up by nonce" }
                        return lookup(channelId, nonce, selfId, client) ?: throw refused
                    }
                    else -> throw refused
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: MessageSendException) {
                throw e
            } catch (e: Exception) {
                Logger.w { "Send attempt failed: ${e::class.simpleName}" }
                e
            }

            if (attempt >= retry.retries) break
            attempt++
            delay(waitMs ?: retry.delayMs(attempt))
        }
        // out of retries: an attempt may still have reached the server
        if (attempt > 0 || resend) lookup(channelId, nonce, selfId, client)?.let { return it }
        throw failure
    }

    private fun isDuplicate(e: MessageSendException) =
        e.status == 409 || (e.status == 400 && e.type == "InvalidOperation")

    private fun retryAfter(response: HttpResponse, text: String): Long? =
        (retryAfterMs(text, response.headers[HttpHeaders.RetryAfter])
            ?: response.headers["X-RateLimit-Reset-After"]?.trim()?.toLongOrNull())
            ?.coerceIn(0, MAX_RATE_LIMIT_WAIT_MS)

    private suspend fun sent(text: String, recovered: Boolean, client: ApiClient): SentMessage {
        val message = try {
            StoatJson.decodeFromString(Message.serializer(), text)
        } catch (e: Exception) {
            null
        }
        // only shown by the caller when recovered: then it must be the plaintext (AN-25)
        val shown = if (recovered && message != null) E2eeMessages.single(message, client) else message
        return SentMessage(shown, text, recovered)
    }

    /** The message with [nonce] among the channel's latest, or null (also when they cannot be read). */
    internal suspend fun lookup(channelId: String, nonce: String, selfId: String?, client: ApiClient): SentMessage? {
        for (pause in LOOKUP_DELAYS_MS) {
            if (pause > 0) delay(pause)
            val latest = try {
                val response = client.http.get("/channels/$channelId/messages".api(client)) {
                    retry { noRetry() }
                    parameter("limit", LOOKUP_LIMIT)
                    parameter("include_users", false)
                }
                if (!response.status.isSuccess()) continue
                StoatJson.decodeFromString(ListSerializer(Message.serializer()), response.bodyAsText())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w { "Send lookup failed: ${e::class.simpleName}" }
                continue
            }
            val found = latest.firstOrNull { it.nonce == nonce && (selfId == null || it.author == selfId) }
            if (found != null) {
                val shown = E2eeMessages.single(found, client)
                return SentMessage(shown, StoatJson.encodeToString(Message.serializer(), found), recovered = true)
            }
        }
        Logger.w { "Sent message not found by its nonce" }
        return null
    }

    private const val MAX_RATE_LIMIT_WAIT_MS = 60_000L
}

/**
 * A new plaintext message through [MessageOutbox] (upstream `sendMessage` delegates here):
 * the same body as upstream, the nonce as `Idempotency-Key` unless [idempotencyKey] is given.
 */
suspend fun sendPlainMessage(
    channelId: String,
    content: String,
    nonce: String,
    replies: List<SendMessageReply>? = null,
    attachments: List<String>? = null,
    idempotencyKey: String = nonce,
    resend: Boolean = false,
    selfId: String? = null,
    client: ApiClient = ApiClient.Active,
    retry: SendRetry = SendRetry(),
): SentMessage {
    val body = SendMessageBody(
        content = content,
        nonce = nonce,
        replies = replies ?: emptyList(),
        attachments = attachments,
    )
    return MessageOutbox.send(
        channelId = channelId,
        nonce = nonce,
        body = StoatJson.encodeToString(SendMessageBody.serializer(), body),
        idempotencyKey = idempotencyKey,
        resend = resend,
        selfId = selfId,
        client = client,
        retry = retry,
    )
}
