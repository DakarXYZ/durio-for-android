package chat.stoat.api.fork.e2ee

import chat.stoat.api.fork.http.ApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Host contract of the crypto core (plan AN-21, AN-24; stoat-crypto lib.rs "Host contract")
class E2eeSyncTest {
    private class Server(val scope: TestScope) {
        val log = mutableListOf<String>()
        var handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { respond("{}", HttpStatusCode.OK) }

        val client = object : ApiClient {
            override val http = HttpClient(
                MockEngine.create {
                    dispatcher = StandardTestDispatcher(scope.testScheduler)
                    addHandler { request ->
                        val body = (request.body as? TextContent)?.text.orEmpty()
                        log += "${request.method.value} ${request.url.encodedPathAndQuery.removePrefix("/api")} $body".trim()
                        handler(request)
                    }
                }
            ) {
                // as StoatHttp: E2EE calls must switch it off
                install(HttpRequestRetry) {
                    retryOnServerErrors(maxRetries = 5)
                    retryOnException(maxRetries = 5)
                }
            }

            override fun api(path: String) = "https://a.example/api$path"
        }
    }

    private fun MockRequestHandleScope.ok(body: String) = respond(body, HttpStatusCode.OK)

    private fun TestScope.setup(): Triple<FakeE2eeCore, Server, E2eeSync> {
        val core = FakeE2eeCore()
        val server = Server(this)
        val sync = E2eeSync(core, server.client, backgroundScope)
        return Triple(core, server, sync)
    }

    @Test
    fun startRunsPassesUntilNothingIsLeft() = runTest {
        val (core, server, sync) = setup()
        core.outstanding += request("r1")
        server.handler = { ok("""{"one_time_key_counts":{}}""") }

        sync.start()
        runCurrent()

        assertEquals(listOf("POST /e2ee/keys/upload {\"r\":\"r1\"}"), server.log)
        assertEquals(listOf("pass", "sent r1 {\"one_time_key_counts\":{}}", "pass"), core.calls)
    }

    @Test
    fun readyFollowsTheContractOrder() = runTest {
        val (core, server, sync) = setup()
        val full = ToDeviceOutcome(100, (1..100).map { "m$it" }, listOf(E2eeRoomKey("c1", "s1")), emptyList())
        core.pages += full
        core.pages += ToDeviceOutcome(3, listOf("n1", "n2", "n3"), emptyList(), listOf("flow1"))
        server.handler = { req ->
            when {
                req.url.encodedPath.endsWith("/keys/count") -> ok("""{"one_time_key_counts":{"signed_curve25519":3}}""")
                req.url.encodedPath.endsWith("/to_device") -> ok("""{"messages":[]}""")
                else -> respond("", HttpStatusCode.NoContent)
            }
        }
        val events = mutableListOf<E2eeSyncEvent>()
        collect(sync, events)

        sync.start()
        sync.onReady()
        runCurrent()

        // start and Ready arrive together: one cycle
        assertEquals(
            listOf(
                "reconnect",
                "toDevice {\"messages\":[]}",
                "acked 100",
                "toDevice {\"messages\":[]}",
                "acked 3",
                "otk {\"one_time_key_counts\":{\"signed_curve25519\":3}}",
                "pass",
            ),
            core.calls
        )
        assertEquals(
            listOf(
                "GET /e2ee/to_device?limit=100",
                "POST /e2ee/to_device/ack {\"ids\":[${(1..100).joinToString(",") { "\"m$it\"" }}]}",
                "GET /e2ee/to_device?limit=100",
                "POST /e2ee/to_device/ack {\"ids\":[\"n1\",\"n2\",\"n3\"]}",
                "GET /e2ee/keys/count",
            ),
            server.log
        )
        assertEquals(
            listOf(
                E2eeSyncEvent.RoomKeys(listOf(E2eeRoomKey("c1", "s1"))),
                E2eeSyncEvent.Verifications(listOf("flow1")),
            ),
            events
        )
    }

    @Test
    fun doorbellFetchesOnlyTheMailbox() = runTest {
        val (core, server, sync) = setup()
        server.handler = { ok("""{"messages":[]}""") }
        sync.start()
        runCurrent()
        core.calls.clear()

        sync.onToDevice()
        runCurrent()

        assertEquals(listOf("GET /e2ee/to_device?limit=100"), server.log)
        assertEquals(listOf("toDevice {\"messages\":[]}", "pass"), core.calls)
    }

    @Test
    fun eventsGoIntoTheCoreBeforeThePass() = runTest {
        val (core, _, sync) = setup()
        sync.start()
        runCurrent()
        core.calls.clear()

        sync.onDeviceListUpdate("01A")
        sync.onDeviceListUpdate("01B")
        sync.onChannelMembers("c1", listOf("01A", "01USER"))
        runCurrent()

        assertEquals(listOf("deviceList 01A,01B", "members c1 01A,01USER", "pass"), core.calls)
    }

    @Test
    fun refusedInputDoesNotBlockTheMailbox() = runTest {
        val (core, server, sync) = setup()
        core.refuseMembers = true
        server.handler = { ok("""{"messages":[]}""") }
        sync.start()
        runCurrent()
        core.calls.clear()

        sync.onChannelMembers("c1", listOf("bad"))
        sync.onToDevice()
        runCurrent()

        assertEquals(listOf("members c1 bad", "toDevice {\"messages\":[]}", "pass"), core.calls)
        assertEquals(E2eeSync.State.Idle, sync.state.value)
    }

    @Test
    fun serverErrorBacksOffAndRetriesOnce() = runTest {
        val (core, server, sync) = setup()
        core.outstanding += request("r1")
        var fail = true
        server.handler = { if (fail) respond("", HttpStatusCode.BadGateway) else ok("{}") }

        sync.start()
        runCurrent()
        // one HTTP call per attempt: the client's own retry is off
        assertEquals(1, server.log.size)
        assertEquals(listOf("pass", "failed r1 502"), core.calls)
        assertEquals(E2eeSync.State.BackingOff, sync.state.value)

        // triggers while backing off wait for the timer
        sync.requestPass()
        sync.onToDevice()
        runCurrent()
        assertEquals(1, server.log.size)

        fail = false
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(
            listOf("GET /e2ee/to_device?limit=100", "POST /e2ee/keys/upload {\"r\":\"r1\"}"),
            server.log.drop(1)
        )
        assertEquals(E2eeSync.State.Idle, sync.state.value)
    }

    @Test
    fun rateLimitWaitsForRetryAfter() = runTest {
        val (core, server, sync) = setup()
        core.outstanding += request("r1")
        var limited = true
        server.handler = { if (limited) respond("""{"retry_after":5000}""", HttpStatusCode.TooManyRequests) else ok("{}") }

        sync.start()
        runCurrent()
        limited = false
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(1, server.log.size)

        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(2, server.log.size)
        assertEquals(listOf("pass", "failed r1 429", "pass", "sent r1 {}", "pass"), core.calls)
    }

    @Test
    fun readyEndsTheBackoff() = runTest {
        val (core, server, sync) = setup()
        core.outstanding += request("r1")
        var fail = true
        server.handler = { if (fail) respond("", HttpStatusCode.ServiceUnavailable) else ok("{}") }
        sync.start()
        runCurrent()

        fail = false
        sync.onReady()
        runCurrent()

        assertTrue("reconnect" in core.calls)
        assertTrue(core.calls.contains("sent r1 {}"))
    }

    @Test
    fun rejectedRequestIsLeftToTheCore() = runTest {
        val (core, server, sync) = setup()
        core.outstanding += request("r1")
        core.failAction = E2eeAction.DeviceKeysConflict
        core.wakeup = 30_000
        server.handler = { respond("", HttpStatusCode.Conflict) }
        val events = mutableListOf<E2eeSyncEvent>()
        collect(sync, events)

        sync.start()
        runCurrent()

        // no hot loop: one attempt, then the core's wakeup
        assertEquals(listOf("pass", "failed r1 409"), core.calls)
        assertEquals(listOf<E2eeSyncEvent>(E2eeSyncEvent.ActionRequired(E2eeAction.DeviceKeysConflict)), events)
        assertEquals(E2eeSync.State.Idle, sync.state.value)

        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(listOf("pass", "failed r1 409", "pass", "failed r1 409"), core.calls)
    }

    @Test
    fun unauthorizedStopsEverything() = runTest {
        val (core, server, sync) = setup()
        core.outstanding += request("r1")
        server.handler = { respond("", HttpStatusCode.Unauthorized) }
        val events = mutableListOf<E2eeSyncEvent>()
        collect(sync, events)

        sync.start()
        runCurrent()
        sync.onReady()
        sync.onToDevice()
        runCurrent()

        assertEquals(1, server.log.size)
        assertEquals(E2eeSync.State.Unauthorized, sync.state.value)
        assertEquals(listOf<E2eeSyncEvent>(E2eeSyncEvent.Unauthorized), events)
    }

    @Test
    fun networkFailureIsMarkedWithoutStatus() = runTest {
        val (core, server, sync) = setup()
        core.outstanding += request("r1")
        server.handler = { throw IllegalStateException("offline") }

        sync.start()
        runCurrent()

        assertEquals(listOf("pass", "failed r1 0"), core.calls)
        assertEquals(E2eeSync.State.BackingOff, sync.state.value)
    }

    @Test
    fun wakeupSchedulesThePass() = runTest {
        val (core, _, sync) = setup()
        core.wakeup = 10_000
        sync.start()
        runCurrent()
        assertEquals(listOf("pass"), core.calls)

        core.wakeup = null
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(listOf("pass", "pass"), core.calls)
    }

    @Test
    fun prepareSendLoopsUntilEmpty() = runTest {
        val (core, server, sync) = setup()
        val claim = request("c", E2eeRequestKind.KeysClaim, path = "/e2ee/keys/claim")
        val share = request("t1", E2eeRequestKind.ToDevice, "PUT", "/e2ee/to_device/t1")
        core.toPrepare["ch"] = mutableListOf(claim, share)
        server.handler = { ok("{}") }

        sync.prepareSend("ch")

        assertEquals(
            listOf("POST /e2ee/keys/claim {\"r\":\"c\"}", "PUT /e2ee/to_device/t1 {\"r\":\"t1\"}"),
            server.log
        )
        assertEquals(listOf("prepare ch", "sent c {}", "sent t1 {}", "prepare ch"), core.calls)
    }

    @Test
    fun prepareSendPutsTheMembersInFirst() = runTest {
        val (core, _, sync) = setup()

        sync.prepareSend("ch", listOf("01A", "01USER"))

        assertEquals(listOf("members ch 01A,01USER", "prepare ch"), core.calls)
    }

    @Test
    fun prepareSendFailureIsReported() = runTest {
        val (core, server, sync) = setup()
        core.toPrepare["ch"] = mutableListOf(request("c", E2eeRequestKind.KeysClaim, path = "/e2ee/keys/claim"))
        server.handler = { respond("", HttpStatusCode.InternalServerError) }

        val e = assertFailsWith<E2eeSendPreparationException> { sync.prepareSend("ch") }
        assertTrue(e.retryable)
        assertEquals(listOf("prepare ch", "failed c 500"), core.calls)
    }

    @Test
    fun retryAfterParsing() {
        assertEquals(1500L, chat.stoat.api.fork.routes.e2ee.retryAfterMs("""{"retry_after":1500}""", null))
        assertEquals(3000L, chat.stoat.api.fork.routes.e2ee.retryAfterMs("", "3"))
        assertEquals(null, chat.stoat.api.fork.routes.e2ee.retryAfterMs("oops", null))
    }

    @Test
    fun backoffDoublesUpToTheCap() {
        val b = Backoff(initialMs = 1_000, maxMs = 5_000)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 5_000L, 5_000L), List(5) { b.next() })
        b.reset()
        assertEquals(1_000L, b.next())
    }

    // subscribed right away: events without a subscriber are dropped
    private fun TestScope.collect(sync: E2eeSync, into: MutableList<E2eeSyncEvent>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            sync.events.collect { into += it }
        }
    }
}
