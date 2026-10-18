package chat.stoat.fork.e2ee.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.durio_keys_export_action
import chat.stoat.shared.resources.durio_keys_import_action
import chat.stoat.shared.resources.durio_keys_passphrase_mismatch
import chat.stoat.shared.resources.durio_keys_strength_strong
import chat.stoat.shared.resources.durio_keys_strength_weak
import chat.stoat.shared.resources.durio_keys_wrong_passphrase
import chat.stoat.fork.e2ee.KeyFileFailure
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Key export and import screens (CR-21): secure window, the passphrase twice and strong enough
// before anything is exported, a file before an import, distinct errors. Robolectric on the JVM,
// the same tests on a device (androidTest).
@RunWith(AndroidJUnit4::class)
class KeyFileScreensTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun text(resource: StringResource) = runBlocking { getString(resource) }

    private fun secure(): Boolean {
        var flags = 0
        rule.runOnUiThread { flags = rule.activity.window.attributes.flags }
        return flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    }

    @Test
    fun exportNeedsAStrongMatchingPassphrase() {
        rule.setContent { MaterialTheme { KeyExportScreen(rememberNavController()) } }
        rule.waitForIdle()
        assertTrue("the passphrase is on screen", secure())
        val export = rule.onNodeWithText(text(Res.string.durio_keys_export_action))
        export.assertIsNotEnabled()

        rule.onNodeWithTag("passphrase").performTextInput("abcdefghij")
        rule.onNodeWithText(text(Res.string.durio_keys_strength_weak)).assertIsDisplayed()
        rule.onNodeWithTag("repeated").performTextInput("abcdefghij")
        export.assertIsNotEnabled()

        rule.onNodeWithTag("passphrase").performTextInput(" and more words")
        rule.onNodeWithText(text(Res.string.durio_keys_strength_strong)).assertIsDisplayed()
        rule.onNodeWithText(text(Res.string.durio_keys_passphrase_mismatch)).assertIsDisplayed()
        export.assertIsNotEnabled()

        rule.onNodeWithTag("repeated").performTextInput(" and more words")
        export.assertIsEnabled()
    }

    @Test
    fun importNeedsAFileAndShowsItsError() {
        val viewModel = KeyImportViewModel()
        rule.setContent { MaterialTheme { KeyImportScreen(rememberNavController(), viewModel) } }
        rule.waitForIdle()
        assertTrue(secure())
        val import = rule.onNodeWithText(text(Res.string.durio_keys_import_action))
        rule.onNodeWithTag("passphrase").performTextInput("some passphrase")
        import.assertIsNotEnabled()

        viewModel.file = android.net.Uri.parse("content://test/keys.txt")
        rule.waitForIdle()
        import.assertIsEnabled()

        viewModel.failure = KeyFileFailure.WrongPassphrase
        // below the fold on a small screen: in the tree is enough
        rule.onNodeWithText(text(Res.string.durio_keys_wrong_passphrase)).assertExists()
    }
}
