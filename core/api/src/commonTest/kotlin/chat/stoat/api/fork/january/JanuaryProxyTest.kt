package chat.stoat.api.fork.january

import chat.stoat.api.routes.microservices.january.januaryProxyUrl
import kotlin.test.Test
import kotlin.test.assertEquals

// The server's own files are never sent through January
class JanuaryProxyTest {
    private val proxy = "https://example.org/january"
    private val files = "https://example.org/autumn"

    @Test
    fun ownFilesStayDirect() {
        val own = "https://example.org/autumn/attachments/abc"
        assertEquals(own, januaryProxyUrl(own, proxy, files))
        assertEquals(own, januaryProxyUrl(own, proxy, "$files/"))
    }

    @Test
    fun otherUrlsAreProxied() {
        assertEquals(
            "https://example.org/january/proxy?url=https%3A%2F%2Fcdn.test%2Fa.png",
            januaryProxyUrl("https://cdn.test/a.png", proxy, files),
        )
        // a look-alike prefix is not the file server
        val lookAlike = "https://example.org/autumnal/a.png"
        assertEquals(
            "https://example.org/january/proxy?url=https%3A%2F%2Fexample.org%2Fautumnal%2Fa.png",
            januaryProxyUrl(lookAlike, proxy, files),
        )
    }

    @Test
    fun noFileServerProxiesEverything() {
        assertEquals(
            "https://example.org/january/proxy?url=https%3A%2F%2Fexample.org%2Fautumn%2Fx",
            januaryProxyUrl("https://example.org/autumn/x", proxy, ""),
        )
    }
}
