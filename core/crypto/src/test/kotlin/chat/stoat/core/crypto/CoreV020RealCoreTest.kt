package chat.stoat.core.crypto

import chat.stoat.api.fork.e2ee.E2eeBackup
import chat.stoat.api.fork.e2ee.E2eeException
import chat.stoat.api.fork.e2ee.E2eeRequestKind
import chat.stoat.api.fork.e2ee.E2eeSender
import chat.stoat.api.fork.e2ee.E2eeState
import chat.stoat.api.fork.e2ee.E2eeSync
import chat.stoat.api.fork.e2ee.E2eeSyncEvent
import chat.stoat.api.fork.e2ee.MessageDecryptor
import chat.stoat.api.internals.ULID
import chat.stoat.core.model.schemas.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// What core 0.2.0 added, through the facade (CR-21, CR-22, review O-2): a key file moves the
// history to a new device; a wrong passphrase and a broken file are told apart; the diagnostic
// report carries no keys; a request of the core refused for good ends as a sync problem.
class CoreV020RealCoreTest {
    private val userA = "01KA".padEnd(26, 'A')
    private val userB = "01KB".padEnd(26, 'B')
    private val deviceA1 = "01KDA1".padEnd(26, '1')
    private val deviceA2 = "01KDA2".padEnd(26, '2')
    private val deviceB = "01KDB1".padEnd(26, '1')
    private val channel = "01KH".padEnd(26, 'H')
    private val other = "01KJ".padEnd(26, 'J')

    @Before
    fun hostLibrary() = HostLibrary.assume()

    private suspend fun awaitUntil(what: String, condition: suspend () -> Boolean) {
        try {
            withTimeout(30_000) { while (!condition()) delay(30) }
        } catch (e: Exception) {
            throw AssertionError("timed out: $what", e)
        }
    }

    @Test
    fun keyFileCarriesTheHistoryToANewDevice() = runBlocking {
        E2eeState.clear()
        val server = MiniE2eeServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a1 = CryptoStores.openInMemory(userA, deviceA1)
        val a2 = CryptoStores.openInMemory(userA, deviceA2)
        val b = CryptoStores.openInMemory(userB, deviceB)
        try {
            val syncA1 = E2eeSync(a1, server.client(userA, deviceA1), scope)
            E2eeSync(b, server.client(userB, deviceB), scope).start()
            syncA1.start()
            awaitUntil("keys uploaded") { server.hasKeysOf(deviceA1) && server.hasKeysOf(deviceB) }

            val sender = E2eeSender(a1, syncA1)
            val first = Message(
                id = ULID.makeNext(), channel = channel, author = userA, content = "🔒",
                encrypted = sender.encrypt(channel, listOf(userA, userB), E2eeSender.message("in the file", ULID.makeNext(), ts = 1)),
            )
            sender.encrypt(other, listOf(userA, userB), E2eeSender.message("another chat", ULID.makeNext(), ts = 2))

            // export: an empty passphrase is refused; one channel or all of them
            assertFailsWith<E2eeException.PassphraseRequired> { a1.exportRoomKeys(ByteArray(0)) }
            val one = a1.exportRoomKeys("correct horse".encodeToByteArray(), listOf(channel))
            assertEquals(1, one.sessions)
            assertFalse(one.toString().contains(one.data))
            assertEquals(2, a1.exportRoomKeys("correct horse".encodeToByteArray()).sessions)

            // import on the new device: a wrong passphrase and a broken file are different errors
            assertFailsWith<E2eeException.WrongPassphrase> { a2.importRoomKeys(one.data, "wrong".encodeToByteArray()) }
            assertFailsWith<E2eeException.InvalidKeyFile> { a2.importRoomKeys("not a key file", "x".encodeToByteArray()) }
            val decryptor = MessageDecryptor(a2)
            assertNull(decryptor.decrypt(listOf(first)).single().content, "no key before the import")

            val imported = a2.importRoomKeys(one.data, "correct horse".encodeToByteArray())
            assertEquals(1, imported.total)
            assertEquals(1, imported.imported)
            assertEquals(listOf(channel), imported.roomKeys.map { it.channelId })
            assertEquals("in the file", decryptor.decrypt(listOf(first)).single().content)
        } finally {
            scope.cancel()
            a1.close()
            a2.close()
            b.close()
            E2eeState.clear()
        }
    }

    @Test
    fun diagnosticReportHasNoKeys() = runBlocking {
        val server = MiniE2eeServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = CryptoStores.openInMemory(userA, deviceA1)
        try {
            val sync = E2eeSync(a, server.client(userA, deviceA1), scope)
            sync.start()
            awaitUntil("keys uploaded") { server.hasKeysOf(deviceA1) }
            val setup = E2eeBackup(a, sync, server.client(userA, deviceA1)).create()
            val identity = a.identityKeys()

            val report = a.debugReport()
            assertEquals("1", Json.parseToJsonElement(report).jsonObject["format"]?.jsonPrimitive?.content)
            for (secret in listOf(setup.recoveryKey!!, identity.curve25519, identity.ed25519)) {
                assertFalse(report.contains(secret), "the report holds a key")
            }
        } finally {
            scope.cancel()
            a.close()
        }
    }

    @Test
    fun ownRequestRefusedForGoodIsASyncProblem() = runBlocking {
        val server = MiniE2eeServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // a short pause after each refusal instead of 1 s, 2 s, 4 s
        val a = CryptoStores.openInMemory(userA, deviceA1, ownRetryMs = 5)
        try {
            val client = server.client(userA, deviceA1)
            val sync = E2eeSync(a, client, scope)
            val problem = scope.async(start = CoroutineStart.UNDISPATCHED) {
                sync.events.filterIsInstance<E2eeSyncEvent.SyncProblem>().first()
            }
            sync.start()
            awaitUntil("keys uploaded") { server.hasKeysOf(deviceA1) }

            // the backup version is accepted, its secrets are refused for good
            server.refuse = { method, path -> if (method == "PUT" && path.startsWith("/e2ee/secrets/")) 403 else null }
            val backup = E2eeBackup(a, sync, client)
            backup.activate(backup.create())

            val event = withTimeout(30_000) {
                while (!problem.isCompleted) {
                    sync.requestPass()
                    delay(30)
                }
                problem.await()
            }
            assertEquals(E2eeRequestKind.SecretPut, event.kind)
            assertTrue(a.debugReport().contains("rejected"))

            // the loop ends: once every secret upload is dropped, passes send nothing more
            fun puts() = synchronized(server) { server.log.count { " PUT /e2ee/secrets/" in it } }
            awaitUntil("all secret uploads dropped") {
                val before = puts()
                sync.requestPass()
                delay(300)
                puts() == before
            }
            val settled = puts()
            sync.requestPass()
            delay(300)
            assertEquals(settled, puts())
        } finally {
            scope.cancel()
            a.close()
        }
    }
}
