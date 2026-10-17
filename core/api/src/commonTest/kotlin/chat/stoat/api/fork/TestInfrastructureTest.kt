package chat.stoat.api.fork

import app.cash.turbine.test
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Smoke test for the test dependencies themselves (plan AN-16): Ktor MockEngine,
// kotlinx-coroutines-test and Turbine must be usable from commonTest.
class TestInfrastructureTest {
    @Test
    fun mockEngineAnswersRequests() = runTest {
        val client = HttpClient(MockEngine { request ->
            respond(request.url.encodedPath, HttpStatusCode.OK)
        })

        assertEquals("/ping", client.get("https://example.invalid/ping").bodyAsText())
    }

    @Test
    fun turbineCollectsFlows() = runTest {
        flowOf(1, 2).test {
            assertEquals(1, awaitItem())
            assertEquals(2, awaitItem())
            awaitComplete()
        }
    }
}
