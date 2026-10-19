package chat.stoat.fork.e2ee.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.stoat.R
import chat.stoat.api.StoatAPI
import chat.stoat.api.fork.e2ee.E2eeChannels
import chat.stoat.api.internals.PermissionBit
import chat.stoat.api.internals.has
import chat.stoat.core.model.schemas.SystemInfo
import chat.stoat.core.model.schemas.ChannelType
import chat.stoat.core.model.schemas.Message
import chat.stoat.fork.e2ee.ChannelEncryptionActions
import chat.stoat.fork.e2ee.E2eeManager
import chat.stoat.fork.e2ee.E2eeStatus
import chat.stoat.fork.e2ee.EnableEncryptionResult
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.durio_e2ee_badge
import chat.stoat.shared.resources.durio_e2ee_call_not_encrypted
import chat.stoat.shared.resources.durio_e2ee_cancel
import chat.stoat.shared.resources.durio_e2ee_downgraded
import chat.stoat.shared.resources.durio_e2ee_enable
import chat.stoat.shared.resources.durio_e2ee_enable_confirm
import chat.stoat.shared.resources.durio_e2ee_enable_failed
import chat.stoat.shared.resources.durio_e2ee_enable_no_permission
import chat.stoat.shared.resources.durio_e2ee_enable_not_encryptable
import chat.stoat.shared.resources.durio_e2ee_enable_text
import chat.stoat.shared.resources.durio_e2ee_enable_text_group
import chat.stoat.shared.resources.durio_e2ee_enable_title
import chat.stoat.shared.resources.durio_e2ee_enable_unavailable
import chat.stoat.shared.resources.durio_e2ee_enabled_info
import chat.stoat.shared.resources.durio_e2ee_suggest
import chat.stoat.shared.resources.durio_e2ee_system_enabled
import chat.stoat.shared.resources.durio_e2ee_search_unavailable
import chat.stoat.shared.resources.durio_e2ee_back
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** Lock and "beta" next to an encrypted DM/group's name (header, DM list; AN-27, O8). */
@Composable
fun EncryptedChannelBadge(channelId: String?, modifier: Modifier = Modifier) {
    if (!E2eeChannels.isEncrypted(channelId)) return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Icon(
            painter = painterResource(R.drawable.ic_lock_24dp),
            contentDescription = stringResource(Res.string.durio_e2ee_badge),
            modifier = Modifier.size(14.dp),
            tint = if (E2eeChannels.isDowngraded(channelId)) MaterialTheme.colorScheme.error else LocalContentColor.current,
        )
        Spacer(Modifier.width(2.dp))
        BetaLabel()
    }
}


/**
 * The channel sheet's E2EE row (AN-27): "encrypted (beta)" with what that means, or "enable"
 * for those with ManageChannel (O5) when this device has E2EE. DMs have no settings screen,
 * so it lives in the sheet both DMs and groups open.
 */
@Composable
fun EncryptionSheetItem(channelId: String, permissions: Long) {
    val channel = StoatAPI.channelCache[channelId] ?: return
    if (!E2eeChannels.isEncryptable(channel)) return
    val status by E2eeManager.status.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf(false) }

    if (E2eeChannels.isEncrypted(channel)) {
        InfoRow(
            text = stringResource(
                if (E2eeChannels.isDowngraded(channelId)) Res.string.durio_e2ee_downgraded else Res.string.durio_e2ee_enabled_info
            ),
            warning = E2eeChannels.isDowngraded(channelId),
            // who can read it, device by device: verify or block
            onClick = if (status == E2eeStatus.Ready) ({ devices = true }) else null,
        )
        if (devices) ChannelDevicesDialog(channelId) { devices = false }
        return
    }
    if (status != E2eeStatus.Ready || !(permissions has PermissionBit.ManageChannel)) return

    InfoRow(text = stringResource(Res.string.durio_e2ee_enable), warning = false, onClick = { confirm = true })
    if (confirm) EnableEncryptionDialog(channelId, group = channel.channelType == ChannelType.Group) { confirm = false }
}

/**
 * Over the messages of a DM/group (AN-27, AN-39): a downgrade warning, or — in an empty DM with
 * E2EE on this device — the offer to turn encryption on.
 */
@Composable
fun EncryptionBanner(channelId: String, empty: Boolean) {
    val channel = StoatAPI.channelCache[channelId] ?: return
    if (!E2eeChannels.isEncryptable(channel)) return
    if (E2eeChannels.isDowngraded(channelId)) {
        Banner(stringResource(Res.string.durio_e2ee_downgraded), warning = true)
        return
    }
    val status by E2eeManager.status.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    if (!empty || E2eeChannels.isEncrypted(channel) || status != E2eeStatus.Ready ||
        channel.channelType != ChannelType.DirectMessage
    ) return

    Banner(stringResource(Res.string.durio_e2ee_suggest), warning = false, onClick = { confirm = true })
    if (confirm) EnableEncryptionDialog(channelId, group = false) { confirm = false }
}

@Composable
private fun EnableEncryptionDialog(channelId: String, group: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(painterResource(R.drawable.ic_shield_lock_24dp), contentDescription = null) },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(Res.string.durio_e2ee_enable_title))
                BetaLabel()
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.durio_e2ee_enable_text))
                if (group) Text(stringResource(Res.string.durio_e2ee_enable_text_group))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(Res.string.durio_e2ee_cancel)) }
        },
        confirmButton = {
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val result = ChannelEncryptionActions.enable(channelId)
                        busy = false
                        onDismiss()
                        failureText(result)?.let { Toast.makeText(context, getString(it), Toast.LENGTH_LONG).show() }
                    }
                }
            ) { Text(stringResource(Res.string.durio_e2ee_enable_confirm)) }
        },
    )
}

private fun failureText(result: EnableEncryptionResult) = when (result) {
    EnableEncryptionResult.Enabled, EnableEncryptionResult.AlreadyEnabled -> null
    EnableEncryptionResult.NoPermission -> Res.string.durio_e2ee_enable_no_permission
    EnableEncryptionResult.NotEncryptable -> Res.string.durio_e2ee_enable_not_encryptable
    EnableEncryptionResult.Unavailable -> Res.string.durio_e2ee_enable_unavailable
    EnableEncryptionResult.Failed -> Res.string.durio_e2ee_enable_failed
}

@Composable
private fun InfoRow(text: String, warning: Boolean, onClick: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Icon(
            painter = painterResource(if (warning) R.drawable.ic_error_24dp else R.drawable.ic_shield_lock_24dp),
            contentDescription = null,
            tint = if (warning) MaterialTheme.colorScheme.error else LocalContentColor.current,
        )
        Spacer(Modifier.width(16.dp))
        Text(text, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        BetaLabel()
    }
}

@Composable
private fun Banner(text: String, warning: Boolean, onClick: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Icon(
            painter = painterResource(if (warning) R.drawable.ic_error_24dp else R.drawable.ic_shield_lock_24dp),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        BetaLabel()
    }
}

/**
 * System message `encryption_enabled {by}` (C12): handed to upstream's system message view as a
 * `text` one, localized here (upstream draws unknown types as "unsupported").
 */
@Composable
fun encryptionEnabledAsText(message: Message): Message {
    val system = message.system ?: return message
    if (system.type != "encryption_enabled") return message
    val text = stringResource(Res.string.durio_e2ee_system_enabled, "<@${system.by}>")
    return message.copy(system = SystemInfo(type = "text", content = text))
}

/** Calls are not end-to-end encrypted (O7, Q32): said once per call start in an encrypted chat. */
suspend fun showCallNotEncryptedNotice(context: Context, channelId: String) {
    if (!E2eeChannels.isEncrypted(channelId)) return
    Toast.makeText(context, getString(Res.string.durio_e2ee_call_not_encrypted), Toast.LENGTH_LONG).show()
}

/** The search screen of an encrypted chat, if reached anyway (a link): why there is none. */
@Composable
fun SearchUnavailableInEncryptedChat(onBack: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp)
    ) {
        Icon(painterResource(R.drawable.ic_shield_lock_24dp), contentDescription = null, modifier = Modifier.size(48.dp))
        Text(stringResource(Res.string.durio_e2ee_search_unavailable))
        TextButton(onClick = onBack) { Text(stringResource(Res.string.durio_e2ee_back)) }
    }
}
