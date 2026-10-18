package chat.stoat.core.crypto

import chat.stoat.api.fork.e2ee.E2eeSync
import chat.stoat.api.fork.e2ee.E2eeSyncEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// E2eeSync driving the real core over HTTP (plan AN-21, AN-24): two devices upload their keys,
// A shares a room key with B through the mailbox, B hears the doorbell and gets the key.
class E2eeSyncRealCoreTest {
    private val userA = "01KA".padEnd(26, 'A')
    private val userB = "01KB".padEnd(26, 'B')
    private val deviceA = "01KDA1".padEnd(26, '1')
    private val deviceB = "01KDB1".padEnd(26, '1')
    private val channel = "01KC".padEnd(26, 'C')

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
    fun roomKeyReachesTheOtherDevice() = runBlocking {
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

            // as after Ready: the mailbox and key counts, then a pass
            syncB.onReady()
            syncA.onChannelMembers(channel, listOf(userA, userB))
            awaitUntil("A tracks B (the channel is in its core)") { server.queried(deviceA, userB) }

            syncA.prepareSend(channel)
            assertTrue(server.logged("$deviceA POST /e2ee/keys/claim"))
            assertTrue(server.mailboxSize(deviceB) > 0)

            // the doorbell; the room key comes out of the mailbox, which is acked empty
            val keys = scope.async(start = CoroutineStart.UNDISPATCHED) {
                syncB.events.filterIsInstance<E2eeSyncEvent.RoomKeys>().first()
            }
            syncB.onToDevice()
            val received = withTimeout(20_000) { keys.await() }
            assertEquals(channel, received.keys.single().channelId)
            awaitUntil("B acked its mailbox") { server.mailboxSize(deviceB) == 0 }

            // nothing left to prepare: a second message reuses the session
            val before = server.logSize()
            syncA.prepareSend(channel)
            assertEquals(before, server.logSize())
        } finally {
            scope.cancel()
            a.close()
            b.close()
        }
    }
}
