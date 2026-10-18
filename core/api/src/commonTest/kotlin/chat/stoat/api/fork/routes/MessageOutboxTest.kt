package chat.stoat.api.fork.routes

import chat.stoat.api.fork.http.ApiClient
import chat.stoat.api.routes.channel.sendMessage
import chat.stoat.core.model.fork.EncryptedEnvelope
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private typealias Answer = MockRequestHandleScope.(HttpRequestData) -> HttpResponseData

private fun answers(vararg a: Answer): List<Answer> = a.toList()

// The outbox of new messages under server trouble (block 5E, load-chaos.md B2)
@OptIn(ExperimentalCoroutinesApi::class)
class MessageOutboxTest {
    private class Sent(val method: HttpMethod, val path: String, val body: String, val key: String?)

    private val sent = mutableListOf<Sent>()

    /** One answer per POST, in order; the last one repeats. */
    private var posts: List<Answer> = answers({ ok("01M", NONCE) })

    /** The channel's latest messages, for the lookup by nonce. */
    private var latest = "[]"

    private val client = object : ApiClient {
        override val http = HttpClient(
            MockEngine { request ->
                val body = (request.body as? TextContent)?.text.orEmpty()
                sent += Sent(request.method, request.url.encodedPath.removePrefix("/api"), body, request.headers["Idempotency-Key"])
                if (request.method == HttpMethod.Get) {
                    respond(latest, HttpStatusCode.OK)
                } else {
                    val index = sent.count { it.method == HttpMethod.Post } - 1
                    posts[index.coerceAtMost(posts.lastIndex)](request)
                }
            }
        )

        override fun api(path: String) = "https://a.example/api$path"
    }

    private val noJitter = SendRetry(jitter = false)

    private val postsSent get() = sent.filter { it.method == HttpMethod.Post }
    private val lookups get() = sent.filter { it.method == HttpMethod.Get }

    private suspend fun send(resend: Boolean = false) = sendPlainMessage(
        "c1", "hello", NONCE, resend = resend, selfId = SELF, client = client, retry = noJitter,
    )

    @Test
    fun theNonceIsTheIdempotencyKey() = runTest {
        val result = send()
        assertFalse(result.recovered)
        assertEquals("01M", result.message?.id)
        val post = postsSent.single()
        assertEquals("/channels/c1/messages", post.path)
        assertEquals(NONCE, post.key)
        assertEquals(NONCE, Json.parseToJsonElement(post.body).jsonObject["nonce"]?.jsonPrimitive?.content)
        assertTrue(lookups.isEmpty())
    }

    @Test
    fun serverErrorIsRetriedWithTheSameBody() = runTest {
        posts = answers({ respond("", HttpStatusCode.InternalServerError) }, { respond("", HttpStatusCode.BadGateway) }, { ok("01M", NONCE) })
        val result = send()
        assertTrue(result.recovered)
        assertEquals("01M", result.message?.id)
        assertEquals(3, postsSent.size)
        assertEquals(1, postsSent.map { it.body }.distinct().size)
        assertTrue(postsSent.all { it.key == NONCE })
        // 1 s, then 2 s
        assertEquals(3_000, currentTime)
    }

    @Test
    fun transportErrorIsRetried() = runTest {
        posts = answers({ throw IllegalStateException("connection reset") }, { ok("01M", NONCE) })
        val result = send()
        assertTrue(result.recovered)
        assertEquals(2, postsSent.size)
    }

    @Test
    fun duplicateAfterRetryIsFoundByNonce() = runTest {
        posts = answers({ respond("", HttpStatusCode.InternalServerError) }, { error(400, "InvalidOperation") })
        latest = """[{"_id":"01OTHER","nonce":"x","author":"$SELF"},{"_id":"01M","nonce":"$NONCE","author":"$SELF","content":"hello"}]"""
        val result = send()
        assertTrue(result.recovered)
        assertEquals("01M", result.message?.id)
        assertEquals("hello", result.message?.content)
        assertEquals("/channels/c1/messages", lookups.single().path)
    }

    @Test
    fun conflictAfterRetryNotFoundIsAnError() = runTest {
        posts = answers({ throw IllegalStateException("timeout") }, { error(409, "DuplicateNonce") })
        // someone else's message with the same nonce does not count
        latest = """[{"_id":"01X","nonce":"$NONCE","author":"01SOMEONE"}]"""
        val e = assertFailsWith<MessageSendException> { send() }
        assertEquals(409, e.status)
        assertEquals(MessageOutbox.LOOKUP_DELAYS_MS.size, lookups.size)
    }

    @Test
    fun duplicateOnTheFirstAttemptIsRefused() = runTest {
        posts = answers({ error(400, "InvalidOperation") })
        val e = assertFailsWith<MessageSendException> { send() }
        assertEquals("InvalidOperation", e.type)
        assertEquals(1, postsSent.size)
        assertTrue(lookups.isEmpty())
    }

    @Test
    fun duplicateOfAResendIsLookedUpAtOnce() = runTest {
        posts = answers({ error(409, "DuplicateNonce") })
        latest = """[{"_id":"01M","nonce":"$NONCE","author":"$SELF"}]"""
        val result = send(resend = true)
        assertTrue(result.recovered)
        assertEquals(1, postsSent.size)
    }

    @Test
    fun otherRefusalIsNotRetried() = runTest {
        posts = answers({ error(403, "MissingPermission") })
        val e = assertFailsWith<MessageSendException> { send() }
        assertEquals(403, e.status)
        assertEquals(1, postsSent.size)
        assertTrue(lookups.isEmpty())
    }

    @Test
    fun outOfRetriesLooksTheMessageUp() = runTest {
        posts = answers({ respond("", HttpStatusCode.ServiceUnavailable) })
        latest = """[{"_id":"01M","nonce":"$NONCE","author":"$SELF"}]"""
        val result = send()
        assertTrue(result.recovered)
        assertEquals(1 + noJitter.retries, postsSent.size)
        // 1 + 2 + 4 + 8 + 16 s
        assertEquals(31_000, currentTime)
    }

    @Test
    fun outOfRetriesNotFoundThrowsTheLastFailure() = runTest {
        posts = answers({ respond("", HttpStatusCode.ServiceUnavailable) })
        val e = assertFailsWith<MessageSendException> { send() }
        assertEquals(503, e.status)
    }

    @Test
    fun rateLimitWaitsWhatTheServerSays() = runTest {
        posts = answers({ respond("""{"type":"RateLimited","retry_after":7000}""", HttpStatusCode.TooManyRequests) }, { ok("01M", NONCE) })
        send()
        assertEquals(7_000, currentTime)
        assertEquals(2, postsSent.size)
    }

    @Test
    fun encryptedRetrySendsTheSameEnvelope() = runTest {
        posts = answers({ respond("", HttpStatusCode.BadGateway) }, { ok("01M", NONCE) })
        val envelope = EncryptedEnvelope(sessionId = "s", deviceId = "d", ciphertext = "x")
        val result = sendEncryptedMessage("c1", envelope, NONCE, client = client, retry = noJitter)
        assertTrue(result.recovered)
        assertEquals(2, postsSent.size)
        assertEquals(postsSent[0].body, postsSent[1].body)
        assertTrue(postsSent.all { it.key == NONCE })
    }

    @Test
    fun encryptedOutOfRetriesIsAServerError() = runTest {
        posts = answers({ respond("", HttpStatusCode.InternalServerError) })
        val envelope = EncryptedEnvelope(sessionId = "s", deviceId = "d", ciphertext = "x")
        val e = assertFailsWith<EncryptedSendException> {
            sendEncryptedMessage("c1", envelope, NONCE, client = client, retry = noJitter)
        }
        assertEquals(500, e.status)
    }

    @Test
    fun upstreamSendStillReturnsARefusal() = runTest {
        posts = answers({ error(403, "MissingPermission") })
        val body = sendMessage("c1", "hi", nonce = NONCE, client = client)
        assertTrue("MissingPermission" in body)
        assertEquals(NONCE, postsSent.single().key)
    }

    @Test
    fun retryPausesDoubleUpToTheCap() {
        val retry = SendRetry(jitter = false)
        assertEquals(listOf(1_000L, 2_000, 4_000, 8_000, 16_000, 16_000), (1..6).map(retry::delayMs))
        assertNotNull(SendRetry().delayMs(3).takeIf { it in 4_000..5_000 })
    }

    private fun MockRequestHandleScope.ok(id: String, nonce: String) =
        respond("""{"_id":"$id","nonce":"$nonce","channel":"c1","author":"$SELF","content":"hello"}""", HttpStatusCode.OK, headersOf())

    private fun MockRequestHandleScope.error(status: Int, type: String) =
        respond("""{"type":"$type"}""", HttpStatusCode.fromValue(status))

    private companion object {
        const val NONCE = "01NONCE"
        const val SELF = "01SELF"
    }
}
