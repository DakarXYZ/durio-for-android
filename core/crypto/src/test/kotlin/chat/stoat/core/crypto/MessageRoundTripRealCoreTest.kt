package chat.stoat.core.crypto

import chat.stoat.api.fork.e2ee.DecryptionStatus
import chat.stoat.api.fork.e2ee.E2eeNoRecipientDevicesException
import chat.stoat.api.fork.e2ee.E2eePushDecryptor
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
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

// Send and read through the real core (plan AN-25, AN-26, AN-34): A encrypts with E2eeSender,
// B gets the room key from its mailbox and MessageDecryptor shows the text; an edit replaces it;
// a message the server re-bound to other replies, or a message before the key, is not shown.
class MessageRoundTripRealCoreTest {
    private val userA = "01KA".padEnd(26, 'A')
    private val userB = "01KB".padEnd(26, 'B')
    private val userC = "01KC".padEnd(26, 'C')
    private val deviceA = "01KDA1".padEnd(26, '1')
    private val deviceB = "01KDB1".padEnd(26, '1')
    private val channel = "01KH".padEnd(26, 'H')

    @Before
    fun hostLibrary() = HostLibrary.assume()

    private suspend fun awaitUntil(what: String, condition: () -> Boolean) {
        try {
            withTimeout(20_000) { while (!condition()) delay(20) }
        } catch (e: Exception) {
            throw AssertionError("timed out: $what", e)
        }
    }

    @Test
    fun encryptedMessageAndItsEditReachTheOtherDevice() = runBlocking {
        E2eeState.clear()
        val server = MiniE2eeServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = CryptoStores.openInMemory(userA, deviceA)
        val b = CryptoStores.openInMemory(userB, deviceB)
        try {
            val syncA = E2eeSync(a, server.client(userA, deviceA), scope)
            val syncB = E2eeSync(b, server.client(userB, deviceB), scope)
            syncA.start()
            syncB.start()
            awaitUntil("both devices uploaded their keys") { server.hasKeysOf(deviceA) && server.hasKeysOf(deviceB) }

            // a user without any device: a DM to them is refused, nothing is encrypted
            assertFailsWith<E2eeNoRecipientDevicesException> {
                E2eeSender(a, syncA).encrypt("01KX".padEnd(26, 'X'), listOf(userA, userC), text("lost"), requireDevicesOf = userC)
            }

            val nonce = ULID.makeNext()
            val envelope = E2eeSender(a, syncA).encrypt(
                channel, listOf(userA, userB), E2eeSender.message("hello B", nonce, listOf(REPLY), ts = 1), requireDevicesOf = userB,
            )
            val original = Message(id = ULID.makeNext(), channel = channel, author = userA, content = "🔒", replies = listOf(REPLY), encrypted = envelope)

            // B before its mailbox: waiting for the key, nothing shown
            val decryptor = MessageDecryptor(b)
            val early = decryptor.decrypt(listOf(original)).single()
            assertNull(early.content)
            assertEquals(DecryptionStatus.Pending, E2eeState.status[original.id])

            val keys = scope.async(start = CoroutineStart.UNDISPATCHED) {
                syncB.events.filterIsInstance<E2eeSyncEvent.RoomKeys>().first()
            }
            syncB.onToDevice()
            val roomKeys = withTimeout(20_000) { keys.await() }.keys
            assertEquals(setOf(original.id!!), decryptor.waitingFor(roomKeys))

            val shown = decryptor.decrypt(listOf(original)).single()
            assertEquals("hello B", shown.content)

            // the echo of the own message (socket, other screens) is readable by the sender too
            assertEquals("hello B", MessageDecryptor(a).decrypt(listOf(original)).single().content)
            assertIs<DecryptionStatus.Decrypted>(E2eeState.status[original.id])

            // the edit: edit_of = the message, edit_seq + 1; no new key needed
            val editEnvelope = E2eeSender(a, syncA).encrypt(
                channel, listOf(userA, userB),
                E2eeSender.edit(original.id!!, "hello again", ULID.makeNext(), 0, listOf(REPLY), emptyList(), ts = 2),
            )
            val edited = decryptor.decrypt(listOf(original.copy(encrypted = editEnvelope)), mapOf(original.id!! to shown)).single()
            assertEquals("hello again", edited.content)
            assertEquals(1L, (E2eeState.status[original.id] as DecryptionStatus.Decrypted).editSeq)

            // the server rebinds a message to other replies: suspicious, not shown
            val third = E2eeSender(a, syncA).encrypt(channel, listOf(userA, userB), E2eeSender.message("third", ULID.makeNext(), listOf(REPLY), ts = 3))
            val rebound = Message(id = ULID.makeNext(), channel = channel, author = userA, content = "🔒", replies = emptyList(), encrypted = third)
            assertNull(decryptor.decrypt(listOf(rebound)).single().content)
            assertEquals(DecryptionStatus.Suspicious, E2eeState.status[rebound.id])

            // the same ciphertext served again under another id: a replay
            val replay = original.copy(id = ULID.makeNext())
            assertNull(decryptor.decrypt(listOf(replay)).single().content)
            assertEquals(DecryptionStatus.Suspicious, E2eeState.status[replay.id])
        } finally {
            scope.cancel()
            a.close()
            b.close()
            E2eeState.clear()
        }
    }

    @Test
    fun pushOfAReplyIsReadWithOneMailboxPass() = runBlocking {
        val server = MiniE2eeServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = CryptoStores.openInMemory(userA, deviceA)
        val b = CryptoStores.openInMemory(userB, deviceB)
        try {
            val syncA = E2eeSync(a, server.client(userA, deviceA), scope)
            val syncB = E2eeSync(b, server.client(userB, deviceB), scope)
            syncA.start()
            syncB.start()
            awaitUntil("both devices uploaded their keys") { server.hasKeysOf(deviceA) && server.hasKeysOf(deviceB) }

            // a reply: the push carries the envelope, not the open replies (AN-29)
            val envelope = E2eeSender(a, syncA).encrypt(
                channel, listOf(userA, userB), E2eeSender.message("ping", ULID.makeNext(), listOf(REPLY), ts = 1),
            )
            val pushed = Message(id = ULID.makeNext(), channel = channel, author = userA, encrypted = envelope)

            // the room key is still in B's mailbox: one pass inside the push budget
            val outcome = E2eePushDecryptor(b, syncB).decrypt(pushed, repliesKnown = false)
            assertEquals("ping", assertIs<E2eePushDecryptor.Outcome.Text>(outcome).content)
            assertEquals(0, server.mailboxSize(deviceB))
        } finally {
            scope.cancel()
            a.close()
            b.close()
        }
    }

    private fun text(content: String) = E2eeSender.message(content, ULID.makeNext(), ts = 0)

    private companion object {
        val REPLY = "01KR".padEnd(26, 'R')
    }
}
