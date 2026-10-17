package chat.stoat.api.fork.http

import chat.stoat.api.StoatAPI
import chat.stoat.api.routes.channel.ackChannel
import chat.stoat.core.model.fork.ActiveServer
import chat.stoat.core.model.fork.ServerEndpoints
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionTokenTest {
    private val serverA = ServerEndpoints(
        api = "https://a.example/api",
        ws = "wss://a.example/ws",
        files = "https://a.example/autumn",
        proxy = "https://a.example/january",
        webApp = "https://a.example",
    )
    private val serverB = ServerEndpoints(
        api = "http://10.0.2.2:14702",
        ws = "ws://10.0.2.2:14703",
        files = "https://files.b.example",
        proxy = "",
        webApp = "http://10.0.2.2:5173",
    )

    private val requests = mutableListOf<HttpRequestData>()
    private val engine = MockEngine { request ->
        requests += request
        respondOk()
    }

    private val previousServer = ActiveServer.currentOrNull

    @AfterTest
    fun restoreActiveServer() {
        ActiveServer.set(previousServer)
    }

    private fun activeClient(token: String) = HttpClient(engine) {
        install(SessionTokenPlugin("TestSessionToken", { ActiveServer.currentOrNull }, { token }))
    }

    private fun HttpRequestData.token() = headers[StoatAPI.TOKEN_HEADER_NAME]

    @Test
    fun tokenGoesOnlyToTheActiveServer() = runTest {
        ActiveServer.set(serverA)
        val client = activeClient("token-a")

        client.get("https://a.example/api/users/@me")
        client.get("https://a.example/autumn/attachments")
        client.get("https://foreign.example/api/health")
        client.get("https://a.example.foreign.example/api")
        client.get("http://a.example/api/users/@me")

        assertEquals(listOf("token-a", "token-a", null, null, null), requests.map { it.token() })
    }

    @Test
    fun noServerMeansNoToken() = runTest {
        ActiveServer.set(null)
        activeClient("token-a").get("https://a.example/api/users/@me")

        assertNull(requests.single().token())
    }

    @Test
    fun explicitTokenIsKept() = runTest {
        ActiveServer.set(serverA)
        activeClient("token-a").get("https://foreign.example/api") {
            header(StoatAPI.TOKEN_HEADER_NAME, "explicit")
        }

        assertEquals("explicit", requests.single().token())
    }

    @Test
    fun portIsPartOfTheServer() {
        assertTrue(serverB.acceptsSessionToken(Url("http://10.0.2.2:14702/users/@me")))
        assertFalse(serverB.acceptsSessionToken(Url("http://10.0.2.2:14703/")))
        assertTrue(serverA.acceptsSessionToken(Url("https://A.example:443/api")))
    }

    @Test
    fun accountHttpUsesItsOwnServerAndTokenWhileAnotherIsActive() = runTest {
        ActiveServer.set(serverA)
        StoatAPI.setSessionHeader("token-a")
        val accountB = AccountHttp(serverB, "token-b", engine, userAgent = "test")

        ackChannel("channel", "message", client = accountB)
        accountB.http.get("https://a.example/api/users/@me")
        accountB.http.get("https://files.b.example/attachments")

        val (ack, foreign, files) = requests
        assertEquals("http://10.0.2.2:14702/channels/channel/ack/message", ack.url.toString())
        assertEquals("token-b", ack.token())
        assertNull(foreign.token())
        assertEquals("token-b", files.token())
        assertEquals("token-a", StoatAPI.sessionToken)

        accountB.close()
        StoatAPI.setSessionHeader("")
    }
}
