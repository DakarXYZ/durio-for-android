package chat.stoat.fork.update

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.durio_update_available
import chat.stoat.shared.resources.durio_update_available_body
import chat.stoat.shared.resources.durio_update_check
import chat.stoat.shared.resources.durio_update_checking
import chat.stoat.shared.resources.durio_update_downloading
import chat.stoat.shared.resources.durio_update_failed_checksum
import chat.stoat.shared.resources.durio_update_failed_install
import chat.stoat.shared.resources.durio_update_failed_network
import chat.stoat.shared.resources.durio_update_install
import chat.stoat.shared.resources.durio_update_installing
import chat.stoat.shared.resources.durio_update_later
import chat.stoat.shared.resources.durio_update_up_to_date
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Dialog after the daily check finds a newer release (plan AN-18). Installs only on request. */
@Composable
fun UpdatePrompt() {
    val state by AppUpdates.state.collectAsState()
    val dismissed by AppUpdates.dismissed.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val available = state as? AppUpdates.State.Available ?: return
    if (dismissed) return

    AlertDialog(
        onDismissRequest = { AppUpdates.dismiss() },
        title = { Text(stringResource(Res.string.durio_update_available, available.update.versionName)) },
        text = { Text(stringResource(Res.string.durio_update_available_body)) },
        confirmButton = {
            TextButton(onClick = {
                AppUpdates.dismiss()
                scope.launch { AppUpdates.downloadAndInstall(context.applicationContext, available.update) }
            }) {
                Text(stringResource(Res.string.durio_update_install))
            }
        },
        dismissButton = {
            TextButton(onClick = { AppUpdates.dismiss() }) {
                Text(stringResource(Res.string.durio_update_later))
            }
        }
    )
}

/** "Check for updates" on the About screen. */
@Composable
fun UpdateSection() {
    if (!AppUpdates.enabled) return
    val state by AppUpdates.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val status = when (val s = state) {
        AppUpdates.State.Idle -> null
        AppUpdates.State.Checking -> stringResource(Res.string.durio_update_checking)
        AppUpdates.State.UpToDate -> stringResource(Res.string.durio_update_up_to_date)
        is AppUpdates.State.Available -> stringResource(Res.string.durio_update_available, s.update.versionName)
        is AppUpdates.State.Downloading -> stringResource(Res.string.durio_update_downloading)
        is AppUpdates.State.Installing -> stringResource(Res.string.durio_update_installing)
        is AppUpdates.State.Failed -> when (s.reason) {
            AppUpdates.Reason.Network -> stringResource(Res.string.durio_update_failed_network)
            AppUpdates.Reason.Checksum -> stringResource(Res.string.durio_update_failed_checksum)
            AppUpdates.Reason.Install -> stringResource(Res.string.durio_update_failed_install)
        }
    }
    val update = when (val s = state) {
        is AppUpdates.State.Available -> s.update
        is AppUpdates.State.Failed -> s.update
        else -> null
    }
    val busy = state is AppUpdates.State.Checking ||
        state is AppUpdates.State.Downloading ||
        state is AppUpdates.State.Installing

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(enabled = !busy) {
                scope.launch {
                    if (update != null) {
                        AppUpdates.downloadAndInstall(context.applicationContext, update)
                    } else {
                        AppUpdates.check()
                    }
                }
            }
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = stringResource(
                if (update != null) Res.string.durio_update_install else Res.string.durio_update_check
            ),
            style = MaterialTheme.typography.titleMedium
        )
        if (status != null) Text(status, style = MaterialTheme.typography.bodyMedium)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}
