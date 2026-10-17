package chat.stoat.api.fork.server

import kotlin.test.Test
import kotlin.test.assertEquals

class CanonicalApiUrlTest {
    // Same vectors as canonical_api_url in stoatchat pushd (BE-30): push `instance` must match
    @Test
    fun sharedVectors() {
        val vectors = listOf(
            "https://duri0.duckdns.org/api" to "https://duri0.duckdns.org/api",
            "https://duri0.duckdns.org/api/" to "https://duri0.duckdns.org/api",
            "HTTPS://Duri0.DuckDNS.org/api" to "https://duri0.duckdns.org/api",
            "https://duri0.duckdns.org:443/api" to "https://duri0.duckdns.org/api",
            "http://localhost:80/" to "http://localhost",
            "http://10.0.2.2:14702" to "http://10.0.2.2:14702",
            "https://example.com:8443/api" to "https://example.com:8443/api",
            "https://api.stoat.chat" to "https://api.stoat.chat",
            "duri0.duckdns.org/api" to "https://duri0.duckdns.org/api",
            "  https://duri0.duckdns.org/API/  " to "https://duri0.duckdns.org/API",
        )

        for ((input, expected) in vectors) {
            assertEquals(expected, canonicalApiUrl(input), "input: \"$input\"")
        }
    }

    @Test
    fun isIdempotent() {
        val once = canonicalApiUrl("HTTP://Example.COM:80/Api//")
        assertEquals("http://example.com/Api", once)
        assertEquals(once, canonicalApiUrl(once))
    }
}
