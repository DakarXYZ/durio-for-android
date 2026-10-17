package chat.stoat.api.fork.server

import chat.stoat.core.model.fork.ServerEndpoints
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ServerDiscoveryTest {
    private val requested = mutableListOf<String>()

    private fun discovery(
        allowInsecure: Boolean = false,
        routes: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData?,
    ) = ServerDiscovery(
        MockEngine { request ->
            requested += request.url.toString()
            routes(request) ?: respondError(HttpStatusCode.NotFound)
        },
        allowInsecure,
    )

    private fun MockRequestHandleScope.json(body: String) =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))

    private fun MockRequestHandleScope.html() =
        respond("<!doctype html><title>Hello</title>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))

    private fun root(host: String, scheme: String = "https", ws: String = "wss://$host/ws") = """
        {
          "revolt": "0.16.0",
          "ws": "$ws",
          "app": "$scheme://$host",
          "vapid": "key",
          "features": {
            "captcha": {"enabled": false, "key": ""},
            "email": false,
            "invite_only": true,
            "autumn": {"enabled": true, "url": "$scheme://$host/autumn"},
            "january": {"enabled": true, "url": "$scheme://$host/january"},
            "livekit": {"enabled": true, "nodes": []},
            "e2ee": {"enabled": true, "envelope_versions": [1]}
          }
        }
    """.trimIndent()

    @Test
    fun ourDomainWithWellKnown() = runTest {
        val discovery = discovery { request ->
            when (request.url.toString()) {
                "https://duri0.duckdns.org/.well-known/stoat" ->
                    json("""{"api": "https://duri0.duckdns.org/api/", "name": "dur.io"}""")
                "https://duri0.duckdns.org/api/" -> json(root("duri0.duckdns.org"))
                else -> null
            }
        }

        val server = discovery.discover("  Duri0.DuckDNS.org ")

        assertEquals(
            ServerEndpoints(
                api = "https://duri0.duckdns.org/api",
                ws = "wss://duri0.duckdns.org/ws",
                files = "https://duri0.duckdns.org/autumn",
                proxy = "https://duri0.duckdns.org/january",
                webApp = "https://duri0.duckdns.org",
                livekit = true,
            ),
            server.endpoints,
        )
        assertEquals("dur.io", server.name)
        assertEquals("0.16.0", server.info.revolt)
        assertTrue(server.info.e2eeEnabled)
    }

    @Test
    fun domainWithoutWellKnownTriesApiPath() = runTest {
        val discovery = discovery { request ->
            if (request.url.toString() == "https://chat.example/api/") json(root("chat.example")) else null
        }

        assertEquals("https://chat.example/api", discovery.resolve("https://chat.example/").api)
        assertEquals(
            listOf("https://chat.example/.well-known/stoat", "https://chat.example/api/"),
            requested,
        )
    }

    @Test
    fun domainWithoutWellKnownTriesHostAsApi() = runTest {
        // An old server without .well-known and without the /api prefix; tolerant of missing fields
        val discovery = discovery { request ->
            if (request.url.toString() == "https://api.chat.example/") {
                json("""{"revolt": "0.8.0", "ws": "wss://events.chat.example"}""")
            } else null
        }

        val endpoints = discovery.resolve("api.chat.example")

        assertEquals("https://api.chat.example", endpoints.api)
        assertEquals("wss://events.chat.example", endpoints.ws)
        assertEquals("", endpoints.files)
        assertEquals("https://api.chat.example", endpoints.webApp)
    }

    @Test
    fun ipAndPortInDebug() = runTest {
        // Local backend from `mise start`: delta on 14702, bonfire on 14703, no Caddy
        val discovery = discovery(allowInsecure = true) { request ->
            if (request.url.toString() == "http://10.0.2.2:14702/") {
                json(root("10.0.2.2:14702", scheme = "http", ws = "ws://10.0.2.2:14703"))
            } else null
        }

        val endpoints = discovery.resolve("http://10.0.2.2:14702")

        assertEquals("http://10.0.2.2:14702", endpoints.api)
        assertEquals("ws://10.0.2.2:14703", endpoints.ws)
    }

    @Test
    fun plainHttpIsRefusedOutsideDebug() = runTest {
        val discovery = discovery { null }

        assertFailsWith<DiscoveryError.Insecure> { discovery.resolve("http://10.0.2.2:14702") }
        assertEquals(emptyList(), requested)
    }

    @Test
    fun wellKnownPointingToPlainHttpIsRefused() = runTest {
        val discovery = discovery { request ->
            if (request.url.encodedPath == "/.well-known/stoat") json("""{"api": "http://chat.example/api"}""") else null
        }

        assertFailsWith<DiscoveryError.Insecure> { discovery.resolve("chat.example") }
    }

    @Test
    fun nonStoatSite() = runTest {
        // A single-page app answering every path with HTML
        val discovery = discovery { html() }

        val error = assertFailsWith<DiscoveryError.NotStoatServer> { discovery.resolve("example.com") }
        assertEquals("https://example.com", error.url)
    }

    @Test
    fun jsonThatIsNotStoat() = runTest {
        val discovery = discovery { json("""{"status": "ok", "ws": 5}""") }

        assertFailsWith<DiscoveryError.NotStoatServer> { discovery.resolve("example.com") }
    }

    @Test
    fun wellKnownWithBrokenApiDoesNotFallBack() = runTest {
        val discovery = discovery { request ->
            when (request.url.encodedPath) {
                "/.well-known/stoat" -> json("""{"api": "https://chat.example/broken"}""")
                "/api/" -> json(root("chat.example"))
                else -> null
            }
        }

        assertFailsWith<DiscoveryError.NotStoatServer> { discovery.resolve("chat.example") }
    }

    @Test
    fun redirectsAreFollowedAFewTimes() = runTest {
        val discovery = discovery { request ->
            when (request.url.toString()) {
                "https://chat.example/.well-known/stoat" ->
                    respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, "https://www.chat.example/.well-known/stoat"))
                "https://www.chat.example/.well-known/stoat" -> json("""{"api": "https://www.chat.example/api"}""")
                "https://www.chat.example/api/" -> json(root("www.chat.example"))
                else -> null
            }
        }

        assertEquals("https://www.chat.example/api", discovery.resolve("chat.example").api)
    }

    @Test
    fun redirectLoopEnds() = runTest {
        val discovery = discovery { request ->
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, request.url.encodedPath + "x"))
        }

        assertFailsWith<DiscoveryError.NotStoatServer> { discovery.resolve("chat.example") }
        // three candidates, each the request itself plus three redirects
        assertEquals(12, requested.size)
    }

    @Test
    fun oversizedBodyIsNotAServer() = runTest {
        val discovery = discovery { json("""{"revolt": "0.16.0", "ws": "wss://x", "pad": "${"a".repeat(70_000)}"}""") }

        assertFailsWith<DiscoveryError.NotStoatServer> { discovery.resolve("chat.example") }
    }

    @Test
    fun networkFailureMeansNoConnection() = runTest {
        val discovery = discovery { throw IOException("connection refused") }

        assertFailsWith<DiscoveryError.NoConnection> { discovery.resolve("chat.example") }
    }

    @Test
    fun invalidInput() = runTest {
        val discovery = discovery { null }

        for (input in listOf("", "   ", "chat example", "ftp://chat.example", "https://", "a,b")) {
            assertFailsWith<DiscoveryError.InvalidAddress>(input) { discovery.resolve(input) }
        }
        assertEquals(emptyList(), requested)
    }
}
