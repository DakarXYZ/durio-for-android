package chat.stoat.core.crypto

import chat.stoat.api.fork.e2ee.DecryptionStatus
import chat.stoat.api.fork.e2ee.E2eeAction
import chat.stoat.api.fork.e2ee.E2eeBackup
import chat.stoat.api.fork.e2ee.E2eeCore
import chat.stoat.api.fork.e2ee.E2eeException
import chat.stoat.api.fork.e2ee.E2eeRecoveryKeyCheck
import chat.stoat.api.fork.e2ee.E2eeSender
import chat.stoat.api.fork.e2ee.E2eeState
import chat.stoat.api.fork.e2ee.E2eeSync
import chat.stoat.api.fork.e2ee.E2eeSyncEvent
import chat.stoat.api.fork.e2ee.E2eeVerification
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
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Key backup through the real core (plan AN-32, AN-42, AN-34): the device that set the backup up
// is lost (store key gone, AN-42), a new login restores with the recovery key and reads the old
// message — shown as "sender not confirmed"; rotation needs MFA; a backup replaced on the server
// stops the uploads.
class BackupRealCoreTest {
    private val userA = "01KA".padEnd(26, 'A')
    private val userB = "01KB".padEnd(26, 'B')
    private val deviceA1 = "01KDA1".padEnd(26, '1')
    private val deviceA2 = "01KDA2".padEnd(26, '2')
    private val deviceB = "01KDB1".padEnd(26, '1')
    private val channel = "01KH".padEnd(26, 'H')

    @Before
    fun hostLibrary() = HostLibrary.assume()

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

    @Test
    fun lostDeviceRestoresItsHistoryOnTheNextLogin() = runBlocking {
        E2eeState.clear()
        val server = MiniE2eeServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a1 = CryptoStores.openInMemory(userA, deviceA1)
        val b = CryptoStores.openInMemory(userB, deviceB)
        var a2: E2eeCore? = null
        try {
            val clientA1 = server.client(userA, deviceA1)
            val syncA1 = E2eeSync(a1, clientA1, scope)
            val syncB = E2eeSync(b, server.client(userB, deviceB), scope)
            syncA1.start()
            syncB.start()
            awaitUntil("keys uploaded") { server.hasKeysOf(deviceA1) && server.hasKeysOf(deviceB) }

            // set up: the key is shown before anything reaches the server
            val backupA1 = E2eeBackup(a1, syncA1, clientA1)
            assertFalse(backupA1.exists())
            val setup = backupA1.create()
            val recoveryKey = assertNotNull(setup.recoveryKey)
            assertFalse(setup.toString().contains(recoveryKey.take(4)))
            assertFalse(backupA1.exists())
            backupA1.activate(setup)
            assertTrue(backupA1.exists())
            assertEquals(E2eeRecoveryKeyCheck.Valid, a1.recoveryKeyCheck(recoveryKey.encodeToByteArray()))
            assertEquals(E2eeRecoveryKeyCheck.NotAKey, a1.recoveryKeyCheck("a passphrase".encodeToByteArray()))

            val envelope = E2eeSender(a1, syncA1).encrypt(
                channel, listOf(userA, userB), E2eeSender.message("before the loss", ULID.makeNext(), ts = 1),
            )
            val message = Message(id = ULID.makeNext(), channel = channel, author = userA, content = "🔒", encrypted = envelope)
            awaitUntil("the room key is in the backup", { syncA1.requestPass() }) { server.backupKeyCount(userA) > 0 }
            val status = a1.backupStatus()
            assertTrue(status.enabled && status.recoverySetUp && !status.outdated)

            // AN-42: the store key is gone — the device is reset by logging in again (new session
            // = new device, C22); the old store is never opened again
            a1.close()
            a2 = CryptoStores.openInMemory(userA, deviceA2)
            val clientA2 = server.client(userA, deviceA2)
            val syncA2 = E2eeSync(a2, clientA2, scope)
            syncA2.start()
            val decryptor = MessageDecryptor(a2)
            assertNull(decryptor.decrypt(listOf(message)).single().content, "no key before the restore")

            val backupA2 = E2eeBackup(a2, syncA2, clientA2)
            assertTrue(backupA2.exists())
            // a typo and another key are told apart; neither unlocks
            val typo = recoveryKey.replaceRange(5, 6, if (recoveryKey[5] == 'a') "b" else "a")
            assertFailsWith<E2eeException.RecoveryKeyTypo> { backupA2.unlock(typo.encodeToByteArray()) }
            val other = CryptoStores.openInMemory(userB, "01KDX".padEnd(26, 'X')).use { it.createRecovery().recoveryKey!! }
            assertFailsWith<E2eeException.WrongRecoveryKey> { backupA2.unlock(other.encodeToByteArray()) }

            val version = backupA2.unlock(recoveryKey.encodeToByteArray())
            val progress = backupA2.restore(version)
            assertTrue(progress.finished)
            assertTrue(progress.imported >= 1)

            val restored = decryptor.decrypt(listOf(message)).single()
            assertEquals("before the loss", restored.content)
            // anybody with the backup's public key could have added it (ADR 0001)
            val shown = assertIs<DecryptionStatus.Decrypted>(E2eeState.status[message.id])
            assertEquals(E2eeVerification.Unauthenticated, shown.verification)

            // a rotation keeps the recovery key and always needs MFA
            val rotation = backupA2.rotate()
            assertNull(rotation.recoveryKey)
            val mfa = assertFailsWith<E2eeException.ActionRequired> { backupA2.activate(rotation) }
            assertEquals(E2eeAction.MfaRequired, mfa.action)
            backupA2.activate(rotation, mfaTicket = "ticket")
            assertTrue(a2.backupStatus().enabled)

            // the backup replaced on the server (another device or the server itself): uploads
            // stop until the user unlocks it with the recovery key
            val replaced = scope.async(start = CoroutineStart.UNDISPATCHED) {
                syncA2.events.filterIsInstance<E2eeSyncEvent.ActionRequired>().first()
            }
            awaitUntil("the rotated backup holds every key", { syncA2.requestPass() }) {
                a2.backupStatus().let { it.keysBackedUp == it.keysTotal }
            }
            server.replaceBackupOf(userA)
            E2eeSender(a2, syncA2).encrypt("01KJ".padEnd(26, 'J'), listOf(userA, userB), E2eeSender.message("new key", ULID.makeNext(), ts = 2))
            syncA2.requestPass()
            assertEquals(E2eeAction.BackupVersionChanged, withTimeout(30_000) { replaced.await() }.action)
            assertTrue(a2.backupStatus().outdated)
        } finally {
            scope.cancel()
            a1.close()
            a2?.close()
            b.close()
            E2eeState.clear()
        }
    }
}
