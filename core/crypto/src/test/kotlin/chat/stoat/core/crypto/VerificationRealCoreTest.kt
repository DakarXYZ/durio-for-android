package chat.stoat.core.crypto

import chat.stoat.api.fork.e2ee.E2eeCancelReason
import chat.stoat.api.fork.e2ee.E2eeCore
import chat.stoat.api.fork.e2ee.E2eeSas
import chat.stoat.api.fork.e2ee.E2eeSasState
import chat.stoat.api.fork.e2ee.E2eeSync
import chat.stoat.api.fork.e2ee.E2eeSyncEvent
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

// SAS between two real cores over the mini server (plan AN-31, AN-34): the main scenario — a new
// own device verified with the old one (T7) — and a mismatch between two users, which trusts
// nobody. The UI's part (dialog, emoji names) is driven by the same E2eeSas calls.
class VerificationRealCoreTest {
    private val userA = "01KA".padEnd(26, 'A')
    private val userB = "01KB".padEnd(26, 'B')
    private val deviceA1 = "01KDA1".padEnd(26, '1')
    private val deviceA2 = "01KDA2".padEnd(26, '2')
    private val deviceB = "01KDB1".padEnd(26, '1')
    private val channel = "01KH".padEnd(26, 'H')

    @Before
    fun hostLibrary() = HostLibrary.assume()

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

    // what the app does on every doorbell and after every action: mailbox, then a pass
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
    fun newOwnDeviceIsVerifiedWithTheOldOne() = runBlocking {
        val server = MiniE2eeServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val old = open(server, scope, userA, deviceA1)
        val new = open(server, scope, userA, deviceA2)
        try {
            awaitUntil("both devices uploaded their keys") { server.hasKeysOf(deviceA1) && server.hasKeysOf(deviceA2) }
            // the new device asks the old one (the backend sends E2eeDeviceListUpdate for the user)
            awaitUntil("the new device knows the old one", { new.sync.onDeviceListUpdate(userA) }) {
                new.core.devices(userA).any { it.deviceId == deviceA1 }
            }

            val incoming = scope.async(start = CoroutineStart.UNDISPATCHED) {
                old.sync.events.filterIsInstance<E2eeSyncEvent.Verifications>().first()
            }
            val asking: E2eeSas = new.core.requestVerification(userA, deviceA1)
            assertTrue(asking.isSelfVerification)
            assertEquals(E2eeSasState.Requested, asking.state())
            new.sync.requestPass()

            // the old device: a doorbell, the flow id comes out of its mailbox pass
            old.sync.onDeviceListUpdate(userA)
            val flowIds = withTimeout(30_000) {
                while (!incoming.isCompleted) {
                    old.sync.onToDevice()
                    delay(30)
                }
                incoming.await()
            }.flowIds
            assertEquals(listOf(asking.flowId), flowIds)
            val answering = old.core.verification(flowIds.single())!!
            assertEquals(E2eeSasState.Incoming, answering.state())
            assertEquals(deviceA2, answering.otherDeviceId())

            answering.accept()
            awaitUntil("ready on the asking side", { pump(old, new) }) { asking.state() == E2eeSasState.Ready }
            asking.start()
            awaitUntil("codes on both sides", { pump(old, new) }) {
                asking.state() is E2eeSasState.ShowCodes && answering.state() is E2eeSasState.ShowCodes
            }

            val codesNew = asking.state() as E2eeSasState.ShowCodes
            val codesOld = answering.state() as E2eeSasState.ShowCodes
            assertEquals(7, codesNew.emoji.size)
            assertEquals(codesOld.emoji, codesNew.emoji)
            assertEquals(codesOld.decimals, codesNew.decimals)
            assertTrue(codesNew.emoji.all { it.index in 0..63 && it.description.isNotEmpty() })

            asking.confirm()
            answering.confirm()
            awaitUntil("done on both sides", { pump(old, new) }) {
                asking.state() == E2eeSasState.Done && answering.state() == E2eeSasState.Done
            }
            assertTrue(new.core.devices(userA).single { it.deviceId == deviceA1 }.verified)
            assertTrue(old.core.devices(userA).single { it.deviceId == deviceA2 }.verified)
            assertTrue(new.core.verifications().isEmpty())
            asking.close()
            answering.close()
        } finally {
            scope.cancel()
            old.core.close()
            new.core.close()
        }
    }

    @Test
    fun mismatchCancelsAndTrustsNobody() = runBlocking {
        val server = MiniE2eeServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = open(server, scope, userA, deviceA1)
        val b = open(server, scope, userB, deviceB)
        try {
            awaitUntil("both devices uploaded their keys") { server.hasKeysOf(deviceA1) && server.hasKeysOf(deviceB) }
            // members of a shared encrypted DM: each learns the other's devices
            a.sync.prepareSend(channel, listOf(userA, userB))
            b.sync.prepareSend(channel, listOf(userA, userB))

            val asking = a.core.requestVerification(userB, deviceB)
            assertFalse(asking.isSelfVerification)
            var answering: E2eeSas? = null
            awaitUntil("the request reached B", { pump(a, b) }) {
                answering = b.core.verifications().firstOrNull()
                answering != null
            }
            val other = answering!!
            assertEquals(E2eeSasState.Incoming, other.state())
            assertEquals(userA, other.otherUserId)

            other.accept()
            awaitUntil("ready", { pump(a, b) }) { asking.state() == E2eeSasState.Ready }
            asking.start()
            awaitUntil("codes", { pump(a, b) }) { other.state() is E2eeSasState.ShowCodes && asking.state() is E2eeSasState.ShowCodes }

            // B sees other emoji than A says: a possible attack
            other.mismatch()
            awaitUntil("cancelled on both sides", { pump(a, b) }) {
                asking.state() is E2eeSasState.Cancelled && other.state() is E2eeSasState.Cancelled
            }
            val theirs = assertIs<E2eeSasState.Cancelled>(asking.state())
            assertEquals(E2eeCancelReason.Mismatch, theirs.reason)
            assertFalse(theirs.byUs)
            assertTrue(assertIs<E2eeSasState.Cancelled>(other.state()).byUs)
            assertFalse(a.core.devices(userB).single().verified)
            assertFalse(b.core.devices(userA).single().verified)
            asking.close()
            other.close()
        } finally {
            scope.cancel()
            a.core.close()
            b.core.close()
        }
    }
}
