package chat.stoat.fork.e2ee

import chat.stoat.api.fork.e2ee.E2eeChannelSettings
import chat.stoat.api.fork.e2ee.E2eeCore
import chat.stoat.api.fork.e2ee.E2eeLimits
import chat.stoat.api.fork.e2ee.E2eeRequest
import chat.stoat.api.fork.e2ee.E2eeUndeliveredDevice
import chat.stoat.api.fork.e2ee.ToDeviceOutcome
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// One core per account and process, closed when unused (plan AN-23)
class CryptoRegistryTest {
    private class Core(val key: String) : E2eeCore {
        var closed = false
        override val userId = "u"
        override val deviceId = "d"
        override suspend fun setServerLimits(limits: E2eeLimits) = Unit
        override suspend fun outgoingRequests() = emptyList<E2eeRequest>()
        override suspend fun markRequestSent(request: E2eeRequest, body: String) = Unit
        override suspend fun markRequestFailed(request: E2eeRequest, status: Int) = Unit
        override suspend fun wakeupInMs(): Long? = null
        override suspend fun receiveToDevice(body: String) = ToDeviceOutcome(0, emptyList(), emptyList(), emptyList())
        override suspend fun markAckSent(ids: List<String>) = Unit
        override suspend fun receiveDeviceListUpdate(users: List<String>) = Unit
        override suspend fun receiveReconnect() = Unit
        override suspend fun receiveOtkCounts(body: String) = Unit
        override suspend fun setChannelMembers(channelId: String, members: List<String>, settings: E2eeChannelSettings) = Unit
        override suspend fun prepareSend(channelId: String) = emptyList<E2eeRequest>()
        override suspend fun undeliveredDevices() = emptyList<E2eeUndeliveredDevice>()
        override fun close() {
            closed = true
        }
    }

    private val opened = mutableListOf<Core>()
    private val destroyed = mutableListOf<String>()

    private fun TestScope.registry() = CryptoRegistry(
        scope = backgroundScope,
        idleCloseMs = 60_000,
        open = { key -> Core(key).also { opened += it } },
        destroyStore = { destroyed += it },
    )

    @Test
    fun sharedWhileInUse() = runTest {
        val registry = registry()
        val ui = registry.acquire("a")
        val push = registry.acquire("a")

        assertSame(ui, push)
        assertEquals(1, opened.size)

        registry.release("a", closeNow = true)
        assertFalse(opened[0].closed)
        registry.release("a", closeNow = true)
        assertTrue(opened[0].closed)
        assertFalse(registry.isOpen("a"))
    }

    @Test
    fun backgroundUseClosesAfterIdle() = runTest {
        val registry = registry()
        registry.withCore("a") { }
        runCurrent()
        assertFalse(opened[0].closed)

        // another push within the minute reuses it
        advanceTimeBy(30_000)
        registry.withCore("a") { }
        assertEquals(1, opened.size)

        advanceTimeBy(60_001)
        runCurrent()
        assertTrue(opened[0].closed)

        registry.withCore("a") { }
        assertEquals(2, opened.size)
    }

    @Test
    fun destroyClosesWhateverHoldsIt() = runTest {
        val registry = registry()
        registry.acquire("a")
        registry.destroy("a")

        assertTrue(opened[0].closed)
        assertEquals(listOf("a"), destroyed)
        assertFalse(registry.isOpen("a"))
    }

    @Test
    fun accountsAreSeparate() = runTest {
        val registry = registry()
        val a = registry.acquire("a")
        val b = registry.acquire("b")
        registry.release("a", closeNow = true)

        assertTrue((a as Core).closed)
        assertFalse((b as Core).closed)
    }
}
