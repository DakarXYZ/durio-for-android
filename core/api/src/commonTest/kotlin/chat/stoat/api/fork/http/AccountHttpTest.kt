package chat.stoat.api.fork.http

import chat.stoat.core.model.fork.ServerEndpoints
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

// A rejected token marks the account for a new login (plan AN-10, AN-12)
class AccountHttpTest {
    private val endpoints = ServerEndpoints(
        api = "https://a.example/api",
        ws = "wss://a.example/ws",
        files = "",
        proxy = "",
        webApp = "https://a.example",
    )

    private fun client(status: HttpStatusCode, onUnauthorized: () -> Unit) = AccountHttp(
        endpoints,
        "token-a",
        MockEngine { respond("", status) },
        userAgent = "test",
        onUnauthorized = onUnauthorized,
    )

    @Test
    fun unauthorizedIsReported() = runTest {
        var calls = 0
        client(HttpStatusCode.Unauthorized) { calls++ }.use { it.http.get(it.api("/users/@me")) }
        assertEquals(1, calls)
    }

    @Test
    fun otherErrorsAreNot() = runTest {
        var calls = 0
        client(HttpStatusCode.Forbidden) { calls++ }.use { it.http.get(it.api("/users/@me")) }
        client(HttpStatusCode.OK) { calls++ }.use { it.http.get(it.api("/users/@me")) }
        assertEquals(0, calls)
    }
}
