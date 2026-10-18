package chat.stoat.fork.e2ee.ui

import android.content.Context
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import chat.stoat.fork.e2ee.E2eeDiagnostics
import chat.stoat.fork.e2ee.media.EncryptedShare
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.durio_diag_failed
import chat.stoat.shared.resources.durio_diag_preview
import chat.stoat.shared.resources.durio_diag_share
import chat.stoat.shared.resources.durio_diag_text
import chat.stoat.shared.resources.durio_diag_title
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import logcat.LogPriority
import logcat.logcat
import org.jetbrains.compose.resources.stringResource

class DiagnosticsViewModel : ViewModel() {
    var report by mutableStateOf<String?>(null)
    var failed by mutableStateOf(false)
}

/**
 * "Send diagnostic report" (plan CR-22): the report is built and shown in full first; "Share…"
 * hands it to the system share sheet as a text file (FileProvider, deleted after ten minutes like
 * the shared attachments of AN-44). The app sends nothing by itself; no Sentry.
 */
@Composable
fun DiagnosticsScreen(
    navController: NavController,
    viewModel: DiagnosticsViewModel = viewModel(),
    build: suspend (Context) -> String = E2eeDiagnostics::build,
    share: suspend (Context, String) -> Unit = ::shareReport,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        if (viewModel.report != null) return@LaunchedEffect
        try {
            viewModel.report = build(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "Diagnostic report not built: ${e::class.simpleName}" }
            viewModel.failed = true
        }
    }

    RecoveryScaffold(Res.string.durio_diag_title, onBack = { navController.popBackStack() }) {
        Text(stringResource(Res.string.durio_diag_text))
        val report = viewModel.report
        when {
            viewModel.failed -> Hint(stringResource(Res.string.durio_diag_failed), warning = true, padded = false)
            report == null -> CircularProgressIndicator()
            else -> {
                Text(stringResource(Res.string.durio_diag_preview), style = MaterialTheme.typography.titleSmall)
                SelectionContainer {
                    Text(
                        report,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("report"),
                    )
                }
                Button(onClick = { scope.launch { share(context, report) } }) { Text(stringResource(Res.string.durio_diag_share)) }
            }
        }
    }
}

private suspend fun shareReport(context: Context, report: String) {
    try {
        val uri = EncryptedShare.prepareText(context, E2eeDiagnostics.fileName(), report)
        EncryptedShare.share(context, uri, "text/plain")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logcat("DiagnosticsScreen", LogPriority.WARN) { "Diagnostic report not shared: ${e::class.simpleName}" }
    }
}
