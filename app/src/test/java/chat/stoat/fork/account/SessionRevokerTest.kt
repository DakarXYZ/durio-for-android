package chat.stoat.fork.account

import chat.stoat.api.StoatAPI
import chat.stoat.api.fork.http.AccountHttp
import chat.stoat.core.model.fork.ServerEndpoints
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

// Logout revokes the session on the account's own server with its own token (plan AN-11)
// runBlocking, not runTest: the 5 s timeout would fire at once on virtual time while the mock
// engine answers on another dispatcher
class SessionRevokerTest {
    private val endpoints = ServerEndpoints(
        api = "https://duri0.duckdns.org/api",
        ws = "wss://duri0.duckdns.org/ws",
        files = "",
        proxy = "",
        webApp = "https://duri0.duckdns.org",
    )

    private fun client(handler: (HttpRequestData) -> HttpStatusCode): Pair<AccountHttp, MutableList<HttpRequestData>> {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond("", handler(request))
        }
        return AccountHttp(endpoints, "token-a", engine, userAgent = "test") to requests
    }

    @Test
    fun revokesTheSessionById() = runBlocking {
        val (http, requests) = client { HttpStatusCode.NoContent }

        assertTrue(SessionRevoker.tryRevoke(http, "01SESSION"))

        val request = requests.single()
        assertEquals(HttpMethod.Delete, request.method)
        assertEquals("https://duri0.duckdns.org/api/auth/session/01SESSION", request.url.toString())
        assertEquals("token-a", request.headers[StoatAPI.TOKEN_HEADER_NAME])
    }

    @Test
    fun withoutSessionIdRevokesTheCurrentSession() = runBlocking {
        val (http, requests) = client { HttpStatusCode.NoContent }

        assertTrue(SessionRevoker.tryRevoke(http, ""))

        assertEquals(HttpMethod.Post, requests.single().method)
        assertEquals("https://duri0.duckdns.org/api/auth/session/logout", requests.single().url.toString())
    }

    @Test
    fun aRefusedSessionCountsAsRevoked() = runBlocking {
        // 401: the session is already gone; nothing to retry
        val (http, _) = client { HttpStatusCode.Unauthorized }
        assertTrue(SessionRevoker.tryRevoke(http, ""))
    }

    @Test
    fun noNetworkIsReportedForRetry() = runBlocking {
        val (http, _) = client { throw IOException("offline") }
        assertFalse(SessionRevoker.tryRevoke(http, "01SESSION"))
    }
}
