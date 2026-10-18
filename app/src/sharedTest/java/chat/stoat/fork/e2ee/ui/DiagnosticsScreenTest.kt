package chat.stoat.fork.e2ee.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.durio_diag_failed
import chat.stoat.shared.resources.durio_diag_share
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// "Send diagnostic report" (CR-22): the user sees exactly what is shared, and nothing leaves
// before "Share…". Robolectric on the JVM, the same test on a device (androidTest).
@RunWith(AndroidJUnit4::class)
class DiagnosticsScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun text(resource: StringResource) = runBlocking { getString(resource) }

    @Test
    fun theReportIsShownBeforeItIsShared() {
        val shared = mutableListOf<String>()
        val report = "dur.io diagnostic report\nformat: 1\ncore report:\n{\"format\":1}"
        rule.setContent {
            MaterialTheme {
                DiagnosticsScreen(rememberNavController(), DiagnosticsViewModel(), build = { report }, share = { _, text -> shared += text })
            }
        }
        rule.onNodeWithTag("report").assertTextEquals(report)
        assertEquals(emptyList<String>(), shared)

        rule.onNodeWithText(text(Res.string.durio_diag_share)).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf(report), shared)
    }

    @Test
    fun aFailedReportSaysSo() {
        rule.setContent {
            MaterialTheme {
                DiagnosticsScreen(rememberNavController(), DiagnosticsViewModel(), build = { error("no") }, share = { _, _ -> })
            }
        }
        rule.onNodeWithText(text(Res.string.durio_diag_failed)).assertExists()
    }
}
