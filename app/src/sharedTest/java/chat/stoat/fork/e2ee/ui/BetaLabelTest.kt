package chat.stoat.fork.e2ee.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import chat.stoat.api.fork.e2ee.E2eeRecoveryKeyCheck
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// O8: every way into E2EE says "beta" (no external audit yet). The chat badge, sheet row,
// banner and enable dialog had it already; these are the entry points of stage 5's check.
@RunWith(AndroidJUnit4::class)
class BetaLabelTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun assertBeta(where: String) {
        rule.waitForIdle()
        assertTrue("no beta label on $where", rule.onAllNodesWithTag(BETA_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun settingsEntry() {
        rule.setContent { MaterialTheme { E2eeSettingsEntry(rememberNavController()) } }
        assertBeta("the settings entry")
    }

    @Test
    fun devicesScreen() {
        rule.setContent { MaterialTheme { E2eeSettingsScreen(rememberNavController()) } }
        assertBeta("Devices and encryption")
    }

    @Test
    fun recoveryAndKeyScreens() {
        rule.setContent { MaterialTheme { RecoveryRestoreScreen(rememberNavController()) { E2eeRecoveryKeyCheck.NotAKey } } }
        assertBeta("Restore history")
    }

    @Test
    fun keyExportScreen() {
        rule.setContent { MaterialTheme { KeyExportScreen(rememberNavController()) } }
        assertBeta("Export keys")
    }
}
