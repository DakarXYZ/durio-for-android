package chat.stoat.api.fork.e2ee

import chat.stoat.api.fork.http.ApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Key backup and recovery over the account's client (plan AN-32; host contract "Key backup and
// recovery" in stoat-crypto lib.rs): setup with the MFA retry, restore from the four secret
// storage reads, wrong key, no backup on the server.
class E2eeBackupTest {
    private class Server(scope: TestScope) {
        val log = mutableListOf<String>()
        var handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { respond("{}", HttpStatusCode.OK) }

        val client = object : ApiClient {
            override val http = HttpClient(
                MockEngine.create {
                    dispatcher = StandardTestDispatcher(scope.testScheduler)
                    addHandler { request ->
                        val body = (request.body as? TextContent)?.text.orEmpty()
                        val ticket = request.headers["x-mfa-ticket"]?.let { " ticket=$it" }.orEmpty()
                        log += "${request.method.value} ${request.url.encodedPathAndQuery.removePrefix("/api")}$ticket $body".trim()
                        handler(request)
                    }
                }
            )

            override fun api(path: String) = "https://a.example/api$path"
        }
    }

    private fun TestScope.setup(): Triple<FakeE2eeCore, Server, E2eeBackup> {
        val core = FakeE2eeCore()
        val server = Server(this)
        val sync = E2eeSync(core, server.client, backgroundScope)
        return Triple(core, server, E2eeBackup(core, sync, server.client))
    }

    @Test
    fun setupShowsTheKeyFirstAndRunsTheVersionRequestOnce() = runTest {
        val (core, server, backup) = setup()
        server.handler = { respond("""{"version":"v1"}""", HttpStatusCode.OK) }

        val setup = backup.create()
        assertEquals(core.recoveryKey, setup.recoveryKey)
        // nothing on the server until the user has the key
        assertTrue(server.log.isEmpty())
        assertFalse(setup.toString().contains(core.recoveryKey.take(4)), "the key never reaches a string")

        backup.activate(setup)
        assertEquals(listOf("POST /e2ee/backup/version {\"r\":\"bv1\"}"), server.log)
        assertTrue("sent bv1 {\"version\":\"v1\"}" in core.calls)
    }

    @Test
    fun replacingABackupAsksForMfaAndRetriesTheSameRequest() = runTest {
        val (core, server, backup) = setup()
        server.handler = { req ->
            if (req.headers["x-mfa-ticket"] == null) respond("""{"type":"InvalidToken"}""", HttpStatusCode.Unauthorized)
            else respond("""{"version":"v2"}""", HttpStatusCode.OK)
        }
        core.failAction = E2eeAction.MfaRequired

        val setup = backup.create()
        val e = assertFailsWith<E2eeException.ActionRequired> { backup.activate(setup) }
        assertEquals(E2eeAction.MfaRequired, e.action)

        core.failAction = null
        backup.activate(setup, mfaTicket = "T1")
        assertEquals(
            listOf("POST /e2ee/backup/version {\"r\":\"bv1\"}", "POST /e2ee/backup/version ticket=T1 {\"r\":\"bv1\"}"),
            server.log,
        )
        assertEquals(listOf("failed bv1 401", "sent bv1 {\"version\":\"v2\"}"), core.calls.filter { "bv1" in it })
    }

    @Test
    fun restoreReadsTheSecretStorageUnlocksAndImportsInBatches() = runTest {
        val (core, server, backup) = setup()
        server.handler = { req ->
            when (req.url.encodedPathAndQuery.removePrefix("/api")) {
                "/e2ee/secrets/m.secret_storage.default_key" -> respond("""{"key":"K1"}""", HttpStatusCode.OK)
                "/e2ee/secrets/m.secret_storage.key.K1" -> respond("""{"algorithm":"a"}""", HttpStatusCode.OK)
                "/e2ee/secrets/m.megolm_backup.v1" -> respond("""{"encrypted":{}}""", HttpStatusCode.OK)
                "/e2ee/backup/version" -> respond("""{"version":"v1"}""", HttpStatusCode.OK)
                "/e2ee/backup/keys?version=v1" -> respond("""{"rooms":{}}""", HttpStatusCode.OK)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val input = core.recoveryKey.encodeToByteArray()
        val version = backup.unlock(input)
        assertEquals("v1", version)
        assertContentEquals(ByteArray(input.size), input, "the typed key is wiped")
        val secrets = core.unlockedSecrets!!
        assertEquals("""{"key":"K1"}""", secrets.defaultKey)
        assertEquals("""{"algorithm":"a"}""", secrets.keyDescription)
        assertEquals("""{"encrypted":{}}""", secrets.backupSecret)
        assertEquals("""{"version":"v1"}""", secrets.backupVersion)

        val seen = mutableListOf<Long>()
        val done = backup.restore(version) { seen += it.done }
        assertTrue(done.finished)
        assertEquals(listOf(0L, 2L, 3L), seen)
        assertTrue("restore {\"rooms\":{}}" in core.calls && "restore closed" in core.calls)
    }

    @Test
    fun aWrongKeyIsRefusedAndWiped() = runTest {
        val (core, server, backup) = setup()
        server.handler = { req ->
            if (req.url.encodedPath.endsWith("default_key")) respond("""{"key":"K1"}""", HttpStatusCode.OK)
            else respond("{}", HttpStatusCode.OK)
        }
        val input = "not the key".encodeToByteArray()
        assertFailsWith<E2eeException.WrongRecoveryKey> { backup.unlock(input) }
        assertContentEquals(ByteArray(input.size), input)
        assertEquals(null, core.backupVersion)
    }

    @Test
    fun noBackupOnTheServer() = runTest {
        val (core, server, backup) = setup()
        server.handler = { respond("", HttpStatusCode.NotFound) }
        assertFalse(backup.exists())
        assertFailsWith<E2eeException.NoBackup> { backup.unlock(core.recoveryKey.encodeToByteArray()) }
        assertFalse("unlock" in core.calls)
    }

    @Test
    fun aServerErrorIsNotTakenForNoBackup() = runTest {
        val (_, server, backup) = setup()
        server.handler = { respond("", HttpStatusCode.BadGateway) }
        assertEquals(502, assertFailsWith<E2eeRequestException> { backup.exists() }.status)
    }
}
