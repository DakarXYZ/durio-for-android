package chat.stoat.fork.upstream

import chat.stoat.core.model.fork.ActiveServer
import chat.stoat.core.model.fork.ServerEndpoints
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// An account on our server contacts no upstream service; one on stoat.chat keeps them (AN-17, Q39)
class UpstreamServicesTest {
    private val previous = ActiveServer.currentOrNull

    @After
    fun restore() = ActiveServer.set(previous)

    private fun server(api: String) = ServerEndpoints(api = api, ws = "", files = "", proxy = "", webApp = "")

    private fun all() = listOf(
        UpstreamServices.geo,
        UpstreamServices.health,
        UpstreamServices.gazette,
        UpstreamServices.discover,
        UpstreamServices.testers,
        UpstreamServices.support,
    )

    @Test
    fun ourServerUsesNone() {
        ActiveServer.set(server("https://name.duckdns.org/api"))
        assertTrue(all().none { it })
    }

    @Test
    fun noAccountUsesNone() {
        ActiveServer.set(null)
        assertTrue(all().none { it })
    }

    @Test
    fun upstreamAccountKeepsGeoAndCommunityLinks() {
        ActiveServer.set(server("https://api.revolt.chat"))
        assertTrue(UpstreamServices.geo)
        assertTrue(UpstreamServices.discover)
        assertTrue(UpstreamServices.testers)
        assertTrue(UpstreamServices.support)
        // opt-in at build time, off in our builds
        assertFalse(UpstreamServices.health)
        assertFalse(UpstreamServices.gazette)
    }
}
