package chat.stoat.api.fork.routes

import chat.stoat.api.fork.http.ApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
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

    private val client = object : ApiClient {
        override val http = HttpClient(
            MockEngine { request ->
                log += "${request.method.value} ${request.url.encodedPathAndQuery.removePrefix("/api")}"
                respond(reply, status)
            }
        )

        override fun api(path: String) = "https://a.example/api$path"
    }

    @Test
    fun adminStatus() = runTest {
        reply = """{"admin":true}"""
        assertTrue(fetchAdminStatus(client))
        assertEquals(listOf("GET /admin/@me"), log)

        reply = """{"admin":false}"""
        assertFalse(fetchAdminStatus(client))
    }

    @Test
    fun aServerWithoutAdminRoutesHasNoAdmins() = runTest {
        status = HttpStatusCode.NotFound
        reply = ""
        assertFalse(fetchAdminStatus(client))
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
