package chat.stoat.fork.e2ee.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import chat.stoat.api.fork.e2ee.E2eeCancelReason
import chat.stoat.api.fork.e2ee.E2eeSas
import chat.stoat.api.fork.e2ee.E2eeSasEmoji
import chat.stoat.api.fork.e2ee.E2eeSasState
import chat.stoat.fork.e2ee.TestE2eeCore
import chat.stoat.fork.e2ee.VerificationController
import chat.stoat.fork.e2ee.VerificationDevice
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.durio_sas_emoji
import chat.stoat.shared.resources.durio_verify_accept
import chat.stoat.shared.resources.durio_verify_close
import chat.stoat.shared.resources.durio_verify_done
import chat.stoat.shared.resources.durio_verify_incoming_other
import chat.stoat.shared.resources.durio_verify_match
import chat.stoat.shared.resources.durio_verify_mismatch
import chat.stoat.shared.resources.durio_verify_no_match
import chat.stoat.shared.resources.durio_verify_title
import chat.stoat.shared.resources.durio_verify_waiting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getStringArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// The SAS dialog (plan AN-31, AN-34): what the user sees and presses for an incoming request,
// the seven emoji with their translated names, "they differ". Robolectric on the JVM, the same
// test on a device (androidTest).
@RunWith(AndroidJUnit4::class)
class VerificationDialogTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private class Sas(override val flowId: String, var current: E2eeSasState) : E2eeSas {
        override val otherUserId = "01OTHERUSER"
        override val isSelfVerification = false
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

    private val flows = mutableMapOf<String, Sas>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val controller = VerificationController(
        scope = scope,
        device = {
            VerificationDevice(object : TestE2eeCore() {
                override suspend fun verification(flowId: String): E2eeSas? = flows[flowId]
            }) {}
        },
        pollMs = 50,
    )
    private val codes = E2eeSasState.ShowCodes(
        emoji = listOf(0, 1, 2, 3, 4, 5, 63).map { E2eeSasEmoji(it, "?", "fallback $it") },
        decimals = listOf(1111, 2222, 3333),
    )

    @After
    fun stop() = scope.cancel()

    private fun text(resource: org.jetbrains.compose.resources.StringResource, vararg args: Any) =
        runBlocking { getString(resource, *args) }

    private fun waitForText(value: String) = rule.waitUntil(5_000) {
        rule.onAllNodesWithText(value, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun incoming(): Sas {
        val sas = Sas("flow-1", E2eeSasState.Incoming)
        flows[sas.flowId] = sas
        rule.setContent { MaterialTheme { VerificationDialog(controller) } }
        runBlocking { controller.onFlows(listOf(sas.flowId)) }
        waitForText(text(Res.string.durio_verify_title))
        return sas
    }

    @Test
    fun incomingRequestAcceptedComparedAndConfirmed() {
        val sas = incoming()
        rule.onNodeWithText(text(Res.string.durio_verify_incoming_other, "01OTHERUSER")).assertIsDisplayed()

        rule.onNodeWithText(text(Res.string.durio_verify_accept)).performClick()
        waitForText(text(Res.string.durio_verify_waiting))
        assertEquals(listOf("accept"), sas.actions)

        // the other side started: the emoji with the names of our table, by index
        sas.current = codes
        val names = runBlocking { getStringArray(Res.array.durio_sas_emoji) }
        assertEquals(64, names.size)
        waitForText(names[63])
        listOf(0, 1, 2, 3, 4, 5).forEach { rule.onNodeWithText(names[it]).assertIsDisplayed() }
        assertTrue(rule.onAllNodesWithText("fallback", substring = true).fetchSemanticsNodes().isEmpty())

        rule.onNodeWithText(text(Res.string.durio_verify_match)).performClick()
        rule.waitUntil(5_000) { sas.actions.contains("confirm") }

        sas.current = E2eeSasState.Done
        waitForText(text(Res.string.durio_verify_done))
        rule.onNodeWithText(text(Res.string.durio_verify_close)).performClick()
        rule.waitUntil(5_000) { controller.shown.value == null }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText(text(Res.string.durio_verify_title)).fetchSemanticsNodes().isEmpty())
        assertTrue(sas.closed)
    }

    @Test
    fun differentEmojiAreAPossibleAttack() {
        val sas = incoming()
        sas.current = codes
        waitForText(text(Res.string.durio_verify_no_match))

        rule.onNodeWithText(text(Res.string.durio_verify_no_match)).performClick()
        waitForText(text(Res.string.durio_verify_mismatch))
        assertEquals(listOf("mismatch"), sas.actions)
        // only "close" is left; nothing else can be confirmed
        assertTrue(rule.onAllNodesWithText(text(Res.string.durio_verify_match)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun withoutEmojiTheNumbersAreShown() {
        val sas = incoming()
        sas.current = codes.copy(emoji = emptyList())
        waitForText("1111  2222  3333")
    }
}
