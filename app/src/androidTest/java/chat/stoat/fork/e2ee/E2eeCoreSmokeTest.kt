package chat.stoat.fork.e2ee

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chat.stoat.api.fork.e2ee.E2eeBackup
import chat.stoat.api.fork.e2ee.E2eeCore
import chat.stoat.api.fork.e2ee.E2eeException
import chat.stoat.api.fork.e2ee.E2eeRecoveryKeyCheck
import chat.stoat.api.fork.e2ee.E2eeSasState
import chat.stoat.api.fork.e2ee.E2eeSender
import chat.stoat.api.fork.e2ee.E2eeState
import chat.stoat.api.fork.e2ee.E2eeSync
import chat.stoat.api.fork.e2ee.E2eeSyncEvent
import chat.stoat.api.fork.e2ee.MessageDecryptor
import chat.stoat.api.internals.ULID
import chat.stoat.core.crypto.CryptoStores
import chat.stoat.core.crypto.MiniE2eeServer
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom

/**
 * The E2EE core through JNA on a device (plan AN-34): the `.so` of the device's ABI, UniFFI
 * callbacks and handles (`SasHandle`), the SQLite store. Meant for the minified build too
 * (`-Pdurio.minifiedTests`, `connectedReleaseAndroidTest`): R8 must keep what JNA reaches by
 * reflection.
 */
@RunWith(AndroidJUnit4::class)
class E2eeCoreSmokeTest {
    private val userA = "01KA".padEnd(26, 'A')
    private val userB = "01KB".padEnd(26, 'B')
    private val deviceA1 = "01KDA1".padEnd(26, '1')
    private val deviceA2 = "01KDA2".padEnd(26, '2')
    private val deviceB = "01KDB1".padEnd(26, '1')
    private val channel = "01KH".padEnd(26, 'H')

    private class Device(val core: E2eeCore, val sync: E2eeSync)

    private suspend fun awaitUntil(what: String, step: suspend () -> Unit = {}, condition: suspend () -> Boolean) {
        try {
            withTimeout(30_000) {
                while (!condition()) {
                    step()
                    delay(30)
                }
            }
        } catch (e: Exception) {
            throw AssertionError("timed out: $what", e)
        }
    }

    private fun pump(vararg devices: Device) {
        for (d in devices) {
            d.sync.onToDevice()
            d.sync.requestPass()
        }
    }

    private suspend fun open(server: MiniE2eeServer, scope: CoroutineScope, user: String, device: String): Device {
        val core = CryptoStores.openInMemory(user, device)
        return Device(core, E2eeSync(core, server.client(user, device), scope).also { it.start() })
    }

    @Test
    fun sqliteStoreOpensOnlyWithItsKey() = runBlocking {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, "durio-smoke-store")
        dir.deleteRecursively()
        val key = ByteArray(CryptoStores.KEY_BYTES).also { SecureRandom().nextBytes(it) }
        try {
            val core = CryptoStores.open(userA, deviceA1, dir, key.copyOf())
            val identity = core.identityKeys()
            core.close()

            CryptoStores.open(userA, deviceA1, dir, key.copyOf()).use { assertEquals(identity, it.identityKeys()) }
            // another key: what a lost Keystore key leads to (AN-42)
            try {
                CryptoStores.open(userA, deviceA1, dir, ByteArray(CryptoStores.KEY_BYTES) { 7 }).close()
                fail("the store opened with another key")
            } catch (e: E2eeException.StoreUnusable) {
                // expected
            }
            CryptoStores.destroy(dir)
        } finally {
            key.fill(0)
            dir.deleteRecursively()
        }
    }

    @Test
    fun sasBetweenTwoDevicesThroughJna() = runBlocking {
        val server = MiniE2eeServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val old = open(server, scope, userA, deviceA1)
        val new = open(server, scope, userA, deviceA2)
        try {
            awaitUntil("keys uploaded") { server.hasKeysOf(deviceA1) && server.hasKeysOf(deviceA2) }
            awaitUntil("the new device knows the old one", { new.sync.onDeviceListUpdate(userA) }) {
                new.core.devices(userA).any { it.deviceId == deviceA1 }
            }
            val asking = new.core.requestVerification(userA, deviceA1)
            new.sync.requestPass()
            old.sync.onDeviceListUpdate(userA)
            var answering: chat.stoat.api.fork.e2ee.E2eeSas? = null
            awaitUntil("the request reached the old device", { pump(old, new) }) {
                answering = old.core.verifications().firstOrNull()
                answering != null
            }
            val other = answering!!
            other.accept()
            awaitUntil("ready", { pump(old, new) }) { asking.state() == E2eeSasState.Ready }
            asking.start()
            awaitUntil("codes", { pump(old, new) }) {
                asking.state() is E2eeSasState.ShowCodes && other.state() is E2eeSasState.ShowCodes
            }
            assertEquals((asking.state() as E2eeSasState.ShowCodes).emoji, (other.state() as E2eeSasState.ShowCodes).emoji)
            asking.confirm()
            other.confirm()
            awaitUntil("done", { pump(old, new) }) { asking.state() == E2eeSasState.Done && other.state() == E2eeSasState.Done }
            assertTrue(new.core.devices(userA).single { it.deviceId == deviceA1 }.verified)
            asking.close()
            other.close()
        } finally {
            scope.cancel()
            old.core.close()
            new.core.close()
        }
    }

    @Test
    fun backupRestoreWithTheRecoveryKeyThroughJna() = runBlocking {
        E2eeState.clear()
        val server = MiniE2eeServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a1 = open(server, scope, userA, deviceA1)
        val b = open(server, scope, userB, deviceB)
        var a2: Device? = null
        try {
            awaitUntil("keys uploaded") { server.hasKeysOf(deviceA1) && server.hasKeysOf(deviceB) }
            val clientA1 = server.client(userA, deviceA1)
            val backupA1 = E2eeBackup(a1.core, a1.sync, clientA1)
            val setup = backupA1.create()
            val recoveryKey = setup.recoveryKey ?: error("a new backup shows its recovery key")
            backupA1.activate(setup)
            assertEquals(E2eeRecoveryKeyCheck.Valid, a1.core.recoveryKeyCheck(recoveryKey.encodeToByteArray()))
            val typo = recoveryKey.replaceRange(5, 6, if (recoveryKey[5] == 'a') "b" else "a")
            assertEquals(E2eeRecoveryKeyCheck.Typo, a1.core.recoveryKeyCheck(typo.encodeToByteArray()))
            assertEquals(E2eeRecoveryKeyCheck.NotAKey, a1.core.recoveryKeyCheck("a passphrase".encodeToByteArray()))

            val envelope = E2eeSender(a1.core, a1.sync).encrypt(
                channel, listOf(userA, userB), E2eeSender.message("smoke", ULID.makeNext(), ts = 1),
            )
            val message = Message(id = ULID.makeNext(), channel = channel, author = userA, content = "🔒", encrypted = envelope)
            awaitUntil("the room key is in the backup", { a1.sync.requestPass() }) { server.backupKeyCount(userA) > 0 }

            // B reads it after the to-device key arrived
            val keys = scope.async(start = CoroutineStart.UNDISPATCHED) {
                b.sync.events.filterIsInstance<E2eeSyncEvent.RoomKeys>().first()
            }
            b.sync.onToDevice()
            withTimeout(30_000) { keys.await() }
            assertEquals("smoke", MessageDecryptor(b.core).decrypt(listOf(message)).single().content)

            // a new device of A restores the history with the recovery key
            a1.core.close()
            val restored = open(server, scope, userA, deviceA2).also { a2 = it }
            val backupA2 = E2eeBackup(restored.core, restored.sync, server.client(userA, deviceA2))
            val decryptor = MessageDecryptor(restored.core)
            assertNull(decryptor.decrypt(listOf(message)).single().content)
            val progress = backupA2.restore(backupA2.unlock(recoveryKey.encodeToByteArray()))
            assertTrue(progress.finished && progress.imported >= 1)
            assertEquals("smoke", decryptor.decrypt(listOf(message)).single().content)
        } finally {
            scope.cancel()
            a1.core.close()
            a2?.core?.close()
            b.core.close()
            E2eeState.clear()
        }
    }
}
