package chat.stoat.api.fork.routes

import chat.stoat.api.fork.http.ApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The admin routes of a dur.io server (backend routes/admin): who is an admin, finding a user,
// their sessions and ending them
class AdminRoutesTest {
    private var status = HttpStatusCode.OK
    private var reply = ""
    private val log = mutableListOf<String>()
    private val bodies = mutableListOf<String>()

    private val client = object : ApiClient {
        override val http = HttpClient(
            MockEngine { request ->
                val ticket = request.headers["x-mfa-ticket"]?.let { " ticket=$it" }.orEmpty()
                log += "${request.method.value} ${request.url.encodedPathAndQuery.removePrefix("/api")}$ticket"
                (request.body as? TextContent)?.let { bodies += "${request.method.value} ${request.url.encodedPath.removePrefix("/api")} ${it.text}" }
                respond(reply, status)
            }
        )

        override fun api(path: String) = "https://a.example/api$path"
    }

    @Test
    fun adminStatus() = runTest {
        reply = """{"admin":true}"""
        val status = fetchAdminStatus(client)
        assertTrue(status.admin)
        assertNull(status.elevatedUntilMs)
        assertFalse(status.elevated(0))
        assertEquals(listOf("GET /admin/@me"), log)

        reply = """{"admin":false}"""
        assertFalse(fetchAdminStatus(client).admin)

        // admin mode on for this session until 12:00 UTC
        reply = """{"admin":true,"elevated_until":"2026-10-19T12:00:00.000Z"}"""
        val elevated = fetchAdminStatus(client)
        assertEquals(1_792_411_200_000L, elevated.elevatedUntilMs)
        assertTrue(elevated.elevated(1_792_411_199_999L))
        assertFalse(elevated.elevated(1_792_411_200_000L))
    }

    @Test
    fun elevationSendsTheTicketAndReturnsItsEnd() = runTest {
        reply = """{"expires_at":"2026-10-19T12:00:00Z"}"""
        assertEquals(1_792_411_200_000L, elevateAdmin("T1", client))
        assertEquals(listOf("POST /admin/elevate ticket=T1"), log)

        status = HttpStatusCode.NoContent
        reply = ""
        endAdminElevation(client)
        assertEquals("DELETE /admin/elevate", log.last())
    }

    @Test
    fun anExpiredAdminModeAsksFor2faAndRetriesOnce() = runTest {
        var elevated = false
        val client = object : ApiClient {
            override val http = HttpClient(
                MockEngine { request ->
                    log += "${request.method.value} ${request.url.encodedPath.removePrefix("/api")}"
                    when {
                        request.url.encodedPath.endsWith("/admin/elevate") -> {
                            elevated = request.headers["x-mfa-ticket"] == "T1"
                            respond("""{"expires_at":"2026-10-19T12:00:00Z"}""", HttpStatusCode.OK)
                        }
                        elevated -> respond("[]", HttpStatusCode.OK)
                        else -> respond("""{"type":"AdminElevationRequired"}""", HttpStatusCode.Forbidden)
                    }
                }
            )

            override fun api(path: String) = "https://a.example/api$path"
        }

        var asked = 0
        val sessions = withAdminElevation({
            asked++
            elevateAdmin("T1", client) > 0
        }) { fetchUserSessions("01U", client) }
        assertEquals(emptyList(), sessions)
        assertEquals(1, asked)
        assertEquals(listOf("GET /admin/users/01U/sessions", "POST /admin/elevate", "GET /admin/users/01U/sessions"), log)

        // cancelled 2FA: nothing more is sent
        elevated = false
        log.clear()
        assertNull(withAdminElevation({ false }) { fetchUserSessions("01U", client) })
        assertEquals(listOf("GET /admin/users/01U/sessions"), log)

        // other refusals are not retried
        assertFailsWith<AdminRequestException> {
            withAdminElevation({ error("not asked") }) { throw AdminRequestException(403, "NotPrivileged") }
        }
    }

    @Test
    fun invites() = runTest {
        reply = """{"_id":"K7x9Q2abcd","expires_at":"2026-10-19T12:00:00Z","created_at":"2026-10-19T11:00:00Z",
            "multi_use":true,"uses":0,"created_by":"01A"}"""
        val invite = createAdminInvite(1, multiUse = true, client)
        assertEquals("K7x9Q2abcd", invite.code)
        assertEquals(1_792_411_200_000L, invite.expiresAtMs)
        assertTrue(invite.multiUse)
        assertEquals("POST /admin/invites {\"ttl_hours\":1,\"multi_use\":true}", bodies.last())
        assertEquals("https://duri0.duckdns.org/invite/K7x9Q2abcd", AdminInvite.link("duri0.duckdns.org", invite.code))

        reply = """[{"_id":"B","expires_at":"2026-10-19T12:00:00Z","multi_use":false,"uses":0,"created_by":"01A"},
            {"_id":"A","expires_at":"2026-10-19T11:30:00Z","multi_use":true,"uses":3,"created_by":"01A"}]"""
        val invites = fetchAdminInvites(client)
        assertEquals(listOf("B", "A"), invites.map { it.code })
        assertEquals(3L, invites[1].uses)

        status = HttpStatusCode.NoContent
        reply = ""
        revokeAdminInvite("A", client)
        assertEquals("DELETE /admin/invites/A", log.last())

        status = HttpStatusCode.NotFound
        reply = """{"type":"NotFound","location":"x"}"""
        assertEquals(404, assertFailsWith<AdminRequestException> { revokeAdminInvite("A", client) }.status)

        status = HttpStatusCode.BadRequest
        reply = """{"type":"InvalidOperation","location":"x"}"""
        assertEquals("InvalidOperation", assertFailsWith<AdminRequestException> { createAdminInvite(2, false, client) }.type)

        status = HttpStatusCode.Forbidden
        reply = """{"type":"AdminElevationRequired","location":"x"}"""
        assertTrue(assertFailsWith<AdminRequestException> { fetchAdminInvites(client) }.needsElevation)
    }

    @Test
    fun elevationAndTotpRefusals() = runTest {
        status = HttpStatusCode.Forbidden
        reply = """{"type":"TotpRequired"}"""
        val totp = assertFailsWith<AdminRequestException> { elevateAdmin("T1", client) }
        assertTrue(totp.needsTotp)
        assertFalse(totp.needsElevation)

        // a used or expired MFA ticket: a new code, not a logout
        status = HttpStatusCode.Unauthorized
        reply = """{"type":"InvalidToken","location":"crates/core/database/src/models/mfa_tickets"}"""
        val ticket = assertFailsWith<AdminRequestException> { elevateAdmin("old", client) }
        assertTrue(ticket.invalidTicket)
        assertEquals("InvalidToken", ticket.type)

        status = HttpStatusCode.Forbidden
        reply = """{"type":"AdminElevationRequired","location":"crates/delta/src/routes/admin/mod.rs"}"""
        val elevation = assertFailsWith<AdminRequestException> { findAdminUser("alice#0420", client) }
        assertTrue(elevation.needsElevation)
        assertTrue(assertFailsWith<AdminRequestException> { endAllUserSessions("01U", client) }.needsElevation)
    }

    @Test
    fun aServerWithoutAdminRoutesHasNoAdmins() = runTest {
        status = HttpStatusCode.NotFound
        reply = ""
        assertFalse(fetchAdminStatus(client).admin)
    }

    @Test
    fun findsByTagWithTheHashEncoded() = runTest {
        reply = """{"_id":"01U","username":"alice","discriminator":"0420","display_name":"Alice"}"""
        val user = findAdminUser(" alice#0420 ", client)!!
        assertEquals("01U", user.id)
        assertEquals("Alice", user.displayName)
        assertEquals("alice#0420", user.tag)
        assertFalse(user.bot)
        assertEquals(listOf("GET /admin/users?query=alice%230420"), log)

        reply = """{"_id":"01B","username":"bot","discriminator":"0001","bot":true}"""
        assertTrue(findAdminUser("01B", client)!!.bot)
    }

    @Test
    fun nobodyFoundIsNull() = runTest {
        status = HttpStatusCode.NotFound
        reply = """{"type":"NotFound"}"""
        assertNull(findAdminUser("nobody#0000", client))
    }

    @Test
    fun sessionsWithoutTokens() = runTest {
        reply = """[{"_id":"S1","name":"Pixel","last_seen":"2026-10-19T10:00:00Z"},{"_id":"S2","name":"Windows"}]"""
        val sessions = fetchUserSessions("01U", client)
        assertEquals(listOf("S1", "S2"), sessions.map { it.id })
        assertEquals(1_792_404_000_000L, sessions[0].lastSeenMs)
        assertNull(sessions[1].lastSeenMs)
        assertEquals(listOf("GET /admin/users/01U/sessions"), log)
    }

    @Test
    fun endingSessions() = runTest {
        status = HttpStatusCode.NoContent
        endUserSession("01U", "S1", client)
        endAllUserSessions("01U", client)
        assertEquals(listOf("DELETE /admin/users/01U/sessions/S1", "DELETE /admin/users/01U/sessions"), log)
    }

    @Test
    fun refusalsCarryStatusAndType() = runTest {
        status = HttpStatusCode.Forbidden
        reply = """{"type":"NotPrivileged"}"""
        val forbidden = assertFailsWith<AdminRequestException> { fetchUserSessions("01U", client) }
        assertEquals(403, forbidden.status)
        assertEquals("NotPrivileged", forbidden.type)
        assertFailsWith<AdminRequestException> { fetchAdminStatus(client) }

        status = HttpStatusCode.BadRequest
        reply = """{"type":"IsBot"}"""
        assertEquals("IsBot", assertFailsWith<AdminRequestException> { fetchUserSessions("01B", client) }.type)

        status = HttpStatusCode.NotFound
        reply = """{"type":"NotFound"}"""
        assertEquals(404, assertFailsWith<AdminRequestException> { endUserSession("01U", "gone", client) }.status)
    }
}
