package chat.stoat.core.model.fork

import chat.stoat.core.model.data.STOAT_BASE
import chat.stoat.core.model.data.STOAT_FILES
import chat.stoat.core.model.data.STOAT_INVITES
import chat.stoat.core.model.data.STOAT_SUPPORT
import chat.stoat.core.model.data.STOAT_WEBSOCKET
import chat.stoat.core.model.data.STOAT_WEB_APP
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActiveServerTest {
    private val ours = ServerEndpoints(
        api = "https://duri0.duckdns.org/api",
        ws = "wss://duri0.duckdns.org/ws",
        files = "https://duri0.duckdns.org/autumn",
        proxy = "https://duri0.duckdns.org/january",
        webApp = "https://duri0.duckdns.org",
    )

    @AfterTest
    fun reset() {
        ActiveServer.set(null)
    }

    @Test
    fun constantsFollowTheActiveServer() {
        ActiveServer.set(ours)

        assertEquals("https://duri0.duckdns.org/api/users/@me", "$STOAT_BASE/users/@me")
        assertEquals("wss://duri0.duckdns.org/ws", STOAT_WEBSOCKET)
        assertEquals("https://duri0.duckdns.org/autumn", STOAT_FILES)
        assertEquals("https://duri0.duckdns.org/invite", STOAT_INVITES)
        assertEquals("https://duri0.duckdns.org", STOAT_SUPPORT)

        ActiveServer.set(ours.copy(webApp = "https://other.example", invites = "https://inv.example"))
        assertEquals("https://other.example", STOAT_WEB_APP)
        assertEquals("https://inv.example", STOAT_INVITES)
    }

    @Test
    fun noServerHasNoDefault() {
        assertFailsWith<IllegalStateException> { STOAT_BASE }
        assertFailsWith<IllegalStateException> { STOAT_WEBSOCKET }
        assertEquals("", STOAT_FILES)
        assertEquals("", STOAT_WEB_APP)
        assertEquals("", STOAT_INVITES)
    }

    @Test
    fun upstreamIsRecognisedByApiHost() {
        val upstream = ours.copy(api = "https://api.stoat.chat/0.8")

        assertFalse(UpstreamStoat.isUpstream(ours))
        assertFalse(UpstreamStoat.isUpstream(null))
        assertTrue(UpstreamStoat.isUpstream(upstream))
        assertFalse(UpstreamStoat.isUpstream(ours.copy(api = "https://api.stoat.chat.example/api")))

        ActiveServer.set(upstream)
        assertTrue(UpstreamStoat.isActive)
        assertEquals(UpstreamStoat.SUPPORT, STOAT_SUPPORT)
        assertEquals(UpstreamStoat.INVITES, UpstreamStoat.withUpstreamLinks(upstream).invites)
        assertEquals(ours, UpstreamStoat.withUpstreamLinks(ours))
    }
}
