package chat.stoat.fork.e2ee.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import chat.stoat.api.fork.e2ee.E2eeChannels
import chat.stoat.api.fork.e2ee.E2eeRecoveryKeyCheck
import chat.stoat.fork.e2ee.E2eePrivacy
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.durio_keylost_action
import chat.stoat.shared.resources.durio_keylost_confirm
import chat.stoat.shared.resources.durio_keylost_later
import chat.stoat.shared.resources.durio_keylost_title
import chat.stoat.shared.resources.durio_recovery_activate
import chat.stoat.shared.resources.durio_recovery_input_key
import chat.stoat.shared.resources.durio_recovery_input_passphrase
import chat.stoat.shared.resources.durio_recovery_input_typo
import chat.stoat.shared.resources.durio_recovery_key_title
import chat.stoat.shared.resources.durio_recovery_restore
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// The screens of encrypted chats, recovery and a lost device (plan AN-31..33, AN-42, AN-34):
// FLAG_SECURE where secrets or encrypted chats are on screen, the recovery key check while
// typing, "I saved it" before the backup goes on, the reset prompt. Robolectric on the JVM, the
// same tests on a device (androidTest).
@RunWith(AndroidJUnit4::class)
class E2eeScreensTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val defaultStore = E2eeChannels.store
    private var secureBefore: Boolean? = null

    @After
    fun restore() {
        E2eeChannels.store = defaultStore
        secureBefore?.let { E2eePrivacy.setSecureScreen(rule.activity, it) }
    }

    private fun text(resource: StringResource) = runBlocking { getString(resource) }

    private fun secure(): Boolean {
        var flags = 0
        rule.runOnUiThread { flags = rule.activity.window.attributes.flags }
        return flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    }

    private fun pinEncrypted(vararg channels: String) {
        val identity = Any()
        E2eeChannels.store = {
            object : E2eeChannels.PinStore {
                override val identity: Any = identity
                override fun load() = channels.toSet()
                override fun pin(channelId: String) = Unit
            }
        }
    }

    @Test
    fun encryptedChannelIsSecureOnlyWhenAskedFor() {
        E2eePrivacy.init(rule.activity)
        secureBefore = E2eePrivacy.secureScreen
        pinEncrypted("01ENCRYPTED")
        var channel by mutableStateOf("01ENCRYPTED")
        var shown by mutableStateOf(true)

        E2eePrivacy.setSecureScreen(rule.activity, false)
        rule.setContent { if (shown) EncryptedChannelPrivacy(channel) }
        rule.waitForIdle()
        assertFalse("the option is off by default", secure())

        E2eePrivacy.setSecureScreen(rule.activity, true)
        rule.waitForIdle()
        assertTrue(secure())

        // leaving the chat gives the window back as it was
        shown = false
        rule.waitForIdle()
        assertFalse(secure())

        // a plain DM or a server channel: never
        channel = "01PLAIN"
        shown = true
        rule.waitForIdle()
        assertFalse(secure())
    }

    @Test
    fun restoreChecksTheKeyWhileTypingAndIsSecure() {
        val checked = mutableListOf<String>()
        rule.setContent {
            MaterialTheme {
                RecoveryRestoreScreen(rememberNavController()) { bytes ->
                    val input = bytes.decodeToString()
                    checked += input
                    when {
                        input.startsWith("EsT") && input.endsWith("typo") -> E2eeRecoveryKeyCheck.Typo
                        input.startsWith("EsT") -> E2eeRecoveryKeyCheck.Valid
                        else -> E2eeRecoveryKeyCheck.NotAKey
                    }
                }
            }
        }
        rule.waitForIdle()
        assertTrue("the typed key is on screen", secure())
        rule.onNodeWithText(text(Res.string.durio_recovery_restore)).assertIsNotEnabled()

        val field = rule.onNode(hasSetTextAction())
        field.performTextInput("EsTc typo")
        rule.onNodeWithText(text(Res.string.durio_recovery_input_typo)).assertIsDisplayed()
        rule.onNodeWithText(text(Res.string.durio_recovery_restore)).assertIsNotEnabled()
        assertEquals("EsTc typo", checked.last())

        rule.onNode(hasSetTextAction()).performTextInput(" fixed")
        rule.onNodeWithText(text(Res.string.durio_recovery_input_key)).assertIsDisplayed()
        rule.onNodeWithText(text(Res.string.durio_recovery_restore)).assertIsEnabled()
    }

    @Test
    fun aPassphraseIsAcceptedToo() {
        rule.setContent {
            MaterialTheme { RecoveryRestoreScreen(rememberNavController()) { E2eeRecoveryKeyCheck.NotAKey } }
        }
        rule.onNode(hasSetTextAction()).performTextInput("correct horse battery staple")
        rule.onNodeWithText(text(Res.string.durio_recovery_input_passphrase)).assertIsDisplayed()
        rule.onNodeWithText(text(Res.string.durio_recovery_restore)).assertIsEnabled()
    }

    @Test
    fun recoveryKeyIsShownSecurelyAndMustBeSavedFirst() {
        val viewModel = RecoverySetupViewModel().apply { step = RecoverySetupViewModel.Step.Key }
        rule.setContent { MaterialTheme { RecoverySetupScreen(rememberNavController(), rotate = false, viewModel = viewModel) } }
        rule.onNodeWithText(text(Res.string.durio_recovery_key_title)).assertIsDisplayed()
        assertTrue(secure())

        rule.onNodeWithText(text(Res.string.durio_recovery_activate)).assertIsNotEnabled()
        rule.onNode(isToggleable()).performClick()
        rule.onNodeWithText(text(Res.string.durio_recovery_activate)).assertIsEnabled()

        // the key leaves the screen: the window is not secure any more
        viewModel.step = RecoverySetupViewModel.Step.Done
        rule.waitForIdle()
        assertFalse(secure())
    }

    @Test
    fun keyLostPromptLeadsToTheReset() {
        var reset = 0
        var later = 0
        rule.setContent { MaterialTheme { KeyLostPrompt(onReset = { reset++ }, onLater = { later++ }) } }
        rule.onNodeWithText(text(Res.string.durio_keylost_title)).assertIsDisplayed()
        rule.onNodeWithText(text(Res.string.durio_keylost_later)).performClick()
        rule.onNodeWithText(text(Res.string.durio_keylost_action)).performClick()
        assertEquals(1, reset)
        assertEquals(1, later)
    }

    @Test
    fun resetLogsOutOnlyAfterConfirming() {
        val loggedOut = mutableListOf<String?>()
        rule.setContent {
            MaterialTheme { KeyLostScreen(rememberNavController(), accountKey = "account", logout = { loggedOut += it }) }
        }
        val action = text(Res.string.durio_keylost_action)
        rule.onNodeWithText(action).performClick()
        rule.onNodeWithText(text(Res.string.durio_keylost_confirm)).assertIsDisplayed()
        assertTrue(loggedOut.isEmpty())

        rule.onNode(hasText(action) and hasAnyAncestor(isDialog())).performClick()
        rule.waitForIdle()
        assertEquals(listOf<String?>("account"), loggedOut)
        assertTrue(rule.onAllNodesWithText(text(Res.string.durio_keylost_confirm)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun resetNeedsAnAccount() {
        rule.setContent { MaterialTheme { KeyLostScreen(rememberNavController(), accountKey = null, logout = {}) } }
        rule.onNodeWithText(text(Res.string.durio_keylost_action)).assertIsNotEnabled()
    }
}
