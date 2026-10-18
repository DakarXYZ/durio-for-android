package chat.stoat.fork.e2ee.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.stoat.R
import chat.stoat.api.StoatAPI
import chat.stoat.api.fork.e2ee.E2eeCancelReason
import chat.stoat.api.fork.e2ee.E2eeSasEmoji
import chat.stoat.api.fork.e2ee.E2eeSasState
import chat.stoat.fork.e2ee.E2eeVerifications
import chat.stoat.fork.e2ee.VerificationShown
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.durio_e2ee_cancel
import chat.stoat.shared.resources.durio_sas_emoji
import chat.stoat.shared.resources.durio_verify_accept
import chat.stoat.shared.resources.durio_verify_cancelled
import chat.stoat.shared.resources.durio_verify_close
import chat.stoat.shared.resources.durio_verify_compare
import chat.stoat.shared.resources.durio_verify_compare_numbers
import chat.stoat.shared.resources.durio_verify_done
import chat.stoat.shared.resources.durio_verify_done_text
import chat.stoat.shared.resources.durio_verify_failed
import chat.stoat.shared.resources.durio_verify_incoming_other
import chat.stoat.shared.resources.durio_verify_incoming_self
import chat.stoat.shared.resources.durio_verify_match
import chat.stoat.shared.resources.durio_verify_mismatch
import chat.stoat.shared.resources.durio_verify_mismatch_text
import chat.stoat.shared.resources.durio_verify_no_match
import chat.stoat.shared.resources.durio_verify_timeout
import chat.stoat.shared.resources.durio_verify_title
import chat.stoat.shared.resources.durio_verify_waiting
import chat.stoat.shared.resources.durio_verify_waiting_other
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/**
 * The SAS dialog over every screen (plan AN-31): incoming request, waiting, the seven emoji with
 * their names (Matrix table, translated), confirm or "they differ", the result. The state is the
 * core's ([E2eeVerifications.controller]); the dialog only shows it.
 */
@Composable
fun VerificationDialog() {
    val controller = E2eeVerifications.controller
    val shown by controller.shown.collectAsState()
    val flow = shown ?: return
    LaunchedEffect(flow.flowId) { E2eeVerifications.cancelNotification() }

    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(painterResource(R.drawable.ic_shield_lock_24dp), contentDescription = null) },
        title = { Text(stringResource(Res.string.durio_verify_title)) },
        text = { Body(flow) },
        confirmButton = {
            when (flow.state) {
                E2eeSasState.Incoming -> Button(enabled = !flow.busy, onClick = controller::accept) { Text(stringResource(Res.string.durio_verify_accept)) }
                is E2eeSasState.ShowCodes -> Button(enabled = !flow.busy, onClick = controller::confirm) { Text(stringResource(Res.string.durio_verify_match)) }
                E2eeSasState.Done, is E2eeSasState.Cancelled -> Button(onClick = controller::dismiss) { Text(stringResource(Res.string.durio_verify_close)) }
                else -> Unit
            }
        },
        dismissButton = {
            when (flow.state) {
                is E2eeSasState.ShowCodes -> TextButton(enabled = !flow.busy, onClick = controller::mismatch) {
                    Text(stringResource(Res.string.durio_verify_no_match))
                }
                E2eeSasState.Done, is E2eeSasState.Cancelled -> Unit
                else -> TextButton(onClick = controller::dismiss) { Text(stringResource(Res.string.durio_e2ee_cancel)) }
            }
        },
    )
}

@Composable
private fun Body(flow: VerificationShown) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (val state = flow.state) {
            E2eeSasState.Incoming -> Text(
                if (flow.self) {
                    stringResource(Res.string.durio_verify_incoming_self)
                } else {
                    stringResource(Res.string.durio_verify_incoming_other, displayName(flow.otherUserId))
                }
            )
            E2eeSasState.Requested, E2eeSasState.Ready, E2eeSasState.Started, E2eeSasState.Confirmed -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(
                    stringResource(
                        if (state == E2eeSasState.Confirmed) Res.string.durio_verify_waiting_other else Res.string.durio_verify_waiting
                    )
                )
            }
            is E2eeSasState.ShowCodes -> Codes(state)
            E2eeSasState.Done -> Text(stringResource(Res.string.durio_verify_done_text))
            is E2eeSasState.Cancelled -> Text(
                stringResource(
                    when (state.reason) {
                        E2eeCancelReason.Mismatch -> Res.string.durio_verify_mismatch_text
                        E2eeCancelReason.Timeout -> Res.string.durio_verify_timeout
                        else -> Res.string.durio_verify_cancelled
                    }
                ),
                color = if (state.reason == E2eeCancelReason.Mismatch) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (flow.state == E2eeSasState.Done) Text(stringResource(Res.string.durio_verify_done), style = MaterialTheme.typography.titleSmall)
        if ((flow.state as? E2eeSasState.Cancelled)?.reason == E2eeCancelReason.Mismatch) {
            Text(stringResource(Res.string.durio_verify_mismatch), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
        }
        if (flow.failed) Text(stringResource(Res.string.durio_verify_failed), color = MaterialTheme.colorScheme.error)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Codes(state: E2eeSasState.ShowCodes) {
    val names = stringArrayResource(Res.array.durio_sas_emoji)
    Text(stringResource(Res.string.durio_verify_compare))
    if (state.emoji.isNotEmpty()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            state.emoji.forEach { Emoji(it, names) }
        }
    } else {
        // emoji are optional in SAS: the numbers are always there
        Text(stringResource(Res.string.durio_verify_compare_numbers))
        Text(state.decimals.joinToString("  "), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
}

@Composable
private fun Emoji(emoji: E2eeSasEmoji, names: List<String>) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
        // the system font draws the emoji (no images: the same glyphs as on the other device)
        Text(emoji.symbol, fontSize = 32.sp)
        Text(
            names.getOrNull(emoji.index) ?: emoji.description,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private fun displayName(userId: String): String {
    val user = StoatAPI.userCache[userId]
    return user?.displayName ?: user?.username ?: userId
}
