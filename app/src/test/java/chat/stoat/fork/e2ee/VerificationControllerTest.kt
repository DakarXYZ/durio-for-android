package chat.stoat.fork.e2ee

import chat.stoat.api.fork.e2ee.E2eeCancelReason
import chat.stoat.api.fork.e2ee.E2eeSas
import chat.stoat.api.fork.e2ee.E2eeSasEmoji
import chat.stoat.api.fork.e2ee.E2eeSasState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The verification dialog's logic (plan AN-31): incoming request, accept, the asking side
// starting the comparison, confirm / mismatch, one flow at a time, handles always closed (they
// hold the store lock), a request pass after every action, background notification.
class VerificationControllerTest {
    private class Sas(
        override val flowId: String,
        var current: E2eeSasState,
        override val otherUserId: String = "01OTHER",
        override val isSelfVerification: Boolean = true,
    ) : E2eeSas {
        val actions = mutableListOf<String>()
        var closed = false
        override fun otherDeviceId() = "01DEV"
        override fun state() = current
        override suspend fun accept() {
            actions += "accept"
            current = E2eeSasState.Ready
        }
        override suspend fun start() {
            actions += "start"
            current = E2eeSasState.Started
        }
        override suspend fun confirm() {
            actions += "confirm"
            current = E2eeSasState.Confirmed
        }
        override suspend fun mismatch() {
            actions += "mismatch"
            current = E2eeSasState.Cancelled(E2eeCancelReason.Mismatch, "m.mismatched_sas", byUs = true)
        }
        override suspend fun cancel() {
            actions += "cancel"
            current = E2eeSasState.Cancelled(E2eeCancelReason.User, "m.user", byUs = true)
        }
        override fun close() {
            closed = true
        }
    }

    private class Core : TestE2eeCore() {
        val flows = mutableMapOf<String, Sas>()
        var requested: Sas? = null
        override suspend fun verification(flowId: String): E2eeSas? = flows[flowId]
        override suspend fun requestVerification(userId: String, deviceId: String): E2eeSas =
            Sas("asked-$deviceId", E2eeSasState.Requested, otherUserId = userId).also { requested = it }
    }

    private class Setup(scope: TestScope, foreground: Boolean = true) {
        val core = Core()
        var passes = 0
        val background = mutableListOf<VerificationShown>()
        val controller = VerificationController(
            scope = scope.backgroundScope,
            device = { VerificationDevice(core) { passes++ } },
            pollMs = 100,
            isForeground = { foreground },
            onIncomingInBackground = { background += it },
        )
    }

    private val codes = E2eeSasState.ShowCodes(List(7) { E2eeSasEmoji(it, "🐶", "Dog") }, listOf(1, 2, 3))

    @Test
    fun incomingRequestIsAcceptedComparedAndConfirmed() = runTest {
        val s = Setup(this)
        val flow = Sas("f1", E2eeSasState.Incoming).also { s.core.flows["f1"] = it }

        s.controller.onFlows(listOf("f1"))
        assertEquals(E2eeSasState.Incoming, s.controller.shown.value?.state)
        assertEquals("01DEV", s.controller.shown.value?.otherDeviceId)

        s.controller.accept()
        runCurrent()
        assertEquals(listOf("accept"), flow.actions)
        assertEquals(1, s.passes)
        // the answering side never starts: the asking side does, its core accepts by itself
        flow.current = codes
        advanceTimeBy(150)
        assertEquals(codes, s.controller.shown.value?.state)
        assertFalse("start" in flow.actions)

        s.controller.confirm()
        runCurrent()
        assertEquals(listOf("accept", "confirm"), flow.actions)
        flow.current = E2eeSasState.Done
        advanceTimeBy(150)
        assertEquals(E2eeSasState.Done, s.controller.shown.value?.state)

        s.controller.dismiss()
        runCurrent()
        assertNull(s.controller.shown.value)
        assertTrue(flow.closed)
        // nothing to cancel once done
        assertFalse("cancel" in flow.actions)
    }

    @Test
    fun theAskingSideStartsOnceTheOtherAccepted() = runTest {
        val s = Setup(this)
        assertTrue(s.controller.start("01ME", "01OLD"))
        val flow = s.core.requested!!
        assertEquals(1, s.passes)
        assertEquals(E2eeSasState.Requested, s.controller.shown.value?.state)

        flow.current = E2eeSasState.Ready
        advanceTimeBy(150)
        runCurrent()
        assertEquals(listOf("start"), flow.actions)
        assertEquals(2, s.passes)
        // once
        flow.current = E2eeSasState.Ready
        advanceTimeBy(350)
        assertEquals(listOf("start"), flow.actions)
    }

    @Test
    fun mismatchCancelsAsAnAttack() = runTest {
        val s = Setup(this)
        val flow = Sas("f1", codes).also { s.core.flows["f1"] = it }
        flow.current = E2eeSasState.Incoming
        s.controller.onFlows(listOf("f1"))
        flow.current = codes
        s.controller.mismatch()
        runCurrent()
        val state = s.controller.shown.value?.state as E2eeSasState.Cancelled
        assertEquals(E2eeCancelReason.Mismatch, state.reason)
    }

    @Test
    fun oneFlowAtATimeAndEveryHandleIsClosed() = runTest {
        val s = Setup(this)
        val first = Sas("f1", E2eeSasState.Incoming).also { s.core.flows["f1"] = it }
        val second = Sas("f2", E2eeSasState.Incoming).also { s.core.flows["f2"] = it }
        val notIncoming = Sas("f3", E2eeSasState.Done).also { s.core.flows["f3"] = it }

        s.controller.onFlows(listOf("f1", "f2", "f3"))
        assertEquals("f1", s.controller.shown.value?.flowId)
        assertTrue(second.closed)
        assertTrue(notIncoming.closed)
        assertFalse(first.closed)

        // closing an open flow cancels it first
        s.controller.dismiss()
        runCurrent()
        assertEquals(listOf("cancel"), first.actions)
        assertTrue(first.closed)
    }

    @Test
    fun accountSwitchClosesTheHandle() = runTest {
        val s = Setup(this)
        val flow = Sas("f1", E2eeSasState.Incoming).also { s.core.flows["f1"] = it }
        s.controller.onFlows(listOf("f1"))
        s.controller.clear()
        assertTrue(flow.closed)
        assertNull(s.controller.shown.value)
    }

    @Test
    fun incomingInTheBackgroundNotifies() = runTest {
        val s = Setup(this, foreground = false)
        Sas("f1", E2eeSasState.Incoming).also { s.core.flows["f1"] = it }
        s.controller.onFlows(listOf("f1"))
        assertEquals(listOf("f1"), s.background.map { it.flowId })
    }

    @Test
    fun aFailedStepIsShownAndTheFlowStays() = runTest {
        val failing = object : E2eeSas by Sas("f1", E2eeSasState.Incoming) {
            override suspend fun accept() = throw IllegalStateException("gone")
        }
        val core = object : TestE2eeCore() {
            override suspend fun verification(flowId: String): E2eeSas = failing
        }
        val controller = VerificationController(backgroundScope, { VerificationDevice(core) {} }, pollMs = 100)
        controller.onFlows(listOf("f1"))
        controller.accept()
        runCurrent()
        val shown = controller.shown.value!!
        assertTrue(shown.failed)
        assertFalse(shown.busy)
        assertEquals(E2eeSasState.Incoming, shown.state)
    }

    @Test
    fun withoutADeviceNothingStarts() = runTest {
        val controller = VerificationController(backgroundScope, { null })
        assertFalse(controller.start("u", "d"))
        controller.onFlows(listOf("f1"))
        assertNull(controller.shown.value)
    }
}
