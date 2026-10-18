package chat.stoat.api.fork.e2ee

import chat.stoat.api.fork.http.ApiClient
import chat.stoat.api.fork.routes.EnableEncryptionException
import chat.stoat.api.fork.routes.enableChannelEncryption
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// PUT /channels/{id}/encryption (plan AN-27, BE-11)
class ChannelEncryptionRouteTest {
    private var status = HttpStatusCode.OK
    private var reply = ""
    private val log = mutableListOf<String>()

    private val client = object : ApiClient {
        override val http = HttpClient(
            MockEngine { request ->
                log += "${request.method.value} ${request.url.encodedPath} ${(request.body as TextContent).text}"
                respond(reply, status)
            }
        )

        override fun api(path: String) = "https://a.example$path"
    }

    @Test
    fun enablingReturnsTheEncryptedChannel() = runTest {
        reply = """{"_id":"c1","channel_type":"DirectMessage","recipients":["a","b"],"active":true,
            "encryption":{"algorithm":"m.megolm.v1.aes-sha2","enabled_at":"2026-10-18T00:00:00Z","enabled_by":"a"}}"""

        val channel = enableChannelEncryption("c1", client)

        assertEquals("a", channel.encryption?.enabledBy)
        assertEquals(listOf("PUT /channels/c1/encryption {\"algorithm\":\"m.megolm.v1.aes-sha2\"}"), log)
    }

    @Test
    fun refusalCarriesStatusAndType() = runTest {
        status = HttpStatusCode.BadRequest
        reply = """{"type":"NotEncryptable"}"""
        val e = assertFailsWith<EnableEncryptionException> { enableChannelEncryption("c1", client) }
        assertEquals(400, e.status)
        assertEquals("NotEncryptable", e.type)

        status = HttpStatusCode.NotFound
        reply = ""
        assertEquals(null, assertFailsWith<EnableEncryptionException> { enableChannelEncryption("c1", client) }.type)
    }
}
