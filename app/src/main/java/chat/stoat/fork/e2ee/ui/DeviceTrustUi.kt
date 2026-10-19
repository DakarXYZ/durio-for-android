package chat.stoat.fork.e2ee.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import chat.stoat.R
import chat.stoat.api.StoatAPI
import chat.stoat.core.model.schemas.User
import chat.stoat.fork.e2ee.DeviceRow
import chat.stoat.fork.e2ee.DeviceTrust
import chat.stoat.fork.e2ee.E2eeDeviceList
import chat.stoat.fork.e2ee.E2eeDeviceTrust
import chat.stoat.fork.e2ee.E2eeManager
import chat.stoat.fork.e2ee.E2eeVerifications
import chat.stoat.fork.e2ee.TrustAction
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.durio_e2ee_cancel
import chat.stoat.shared.resources.durio_e2ee_chat_devices
import chat.stoat.shared.resources.durio_e2ee_chat_devices_none
import chat.stoat.shared.resources.durio_e2ee_chat_devices_text
import chat.stoat.shared.resources.durio_e2ee_chat_devices_you
import chat.stoat.shared.resources.durio_e2ee_devices_block
import chat.stoat.shared.resources.durio_e2ee_devices_block_text
import chat.stoat.shared.resources.durio_e2ee_devices_block_title
import chat.stoat.shared.resources.durio_e2ee_devices_trust_failed
import chat.stoat.shared.resources.durio_e2ee_devices_unblock
import chat.stoat.shared.resources.durio_e2ee_devices_unblock_text
import chat.stoat.shared.resources.durio_e2ee_devices_unblock_title
import chat.stoat.shared.resources.durio_verify_close
import chat.stoat.shared.resources.durio_verify_not_started
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** "Block" or "unblock" [row] of [userId], asked for and not yet confirmed. */
class DeviceTrustRequest(val userId: String, val row: DeviceRow, val action: TrustAction)

/**
 * Confirmation of blocking or unblocking a device; [onDone] gets whether it changed (reload
 * the list then). Nothing to show for a null [request].
 */
@Composable
fun DeviceTrustDialog(request: DeviceTrustRequest?, onDone: (changed: Boolean) -> Unit) {
    request ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember(request) { mutableStateOf(false) }
    val block = request.action == TrustAction.Block
    AlertDialog(
        onDismissRequest = { if (!busy) onDone(false) },
        icon = { Icon(painterResource(R.drawable.ic_block_24dp), contentDescription = null) },
        title = { Text(stringResource(if (block) Res.string.durio_e2ee_devices_block_title else Res.string.durio_e2ee_devices_unblock_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(request.row.name ?: request.row.deviceId, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(if (block) Res.string.durio_e2ee_devices_block_text else Res.string.durio_e2ee_devices_unblock_text))
            }
        },
        confirmButton = {
            Button(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val changed = E2eeDeviceTrust.set(request.userId, request.row.deviceId, request.action)
                    if (!changed) Toast.makeText(context, getString(Res.string.durio_e2ee_devices_trust_failed), Toast.LENGTH_SHORT).show()
                    busy = false
                    onDone(changed)
                }
            }) { Text(stringResource(if (block) Res.string.durio_e2ee_devices_block else Res.string.durio_e2ee_devices_unblock)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { onDone(false) }) { Text(stringResource(Res.string.durio_e2ee_cancel)) } },
    )
}

/**
 * "Devices in this chat" of an encrypted DM/group: every member's devices as the core knows
 * them (your own other devices first), with "verify" and "block"/"unblock".
 */
@Composable
fun ChannelDevicesDialog(channelId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var members by remember { mutableStateOf<List<Pair<String, List<DeviceRow>>>?>(null) }
    var trust by remember { mutableStateOf<DeviceTrustRequest?>(null) }

    LaunchedEffect(channelId, reload) {
        members = try {
            channelDevices(channelId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    DeviceTrustDialog(trust) { changed ->
        trust = null
        if (changed) reload++
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { BetaTitle(stringResource(Res.string.durio_e2ee_chat_devices)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(stringResource(Res.string.durio_e2ee_chat_devices_text), style = MaterialTheme.typography.bodySmall)
                val loaded = members
                when {
                    loaded == null -> CircularProgressIndicator()
                    loaded.all { it.second.isEmpty() } -> Text(stringResource(Res.string.durio_e2ee_chat_devices_none))
                    else -> loaded.filter { it.second.isNotEmpty() }.forEach { (userId, rows) ->
                        Text(
                            if (userId == StoatAPI.selfId) stringResource(Res.string.durio_e2ee_chat_devices_you) else userName(userId),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        rows.forEach { row ->
                            DeviceItem(
                                row = row,
                                onVerify = if (row.trust == DeviceTrust.Unverified) {
                                    {
                                        scope.launch {
                                            if (E2eeVerifications.controller.start(userId, row.deviceId)) {
                                                // the verification dialog takes over
                                                onDismiss()
                                            } else {
                                                Toast.makeText(context, getString(Res.string.durio_verify_not_started), Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                } else null,
                                onLogout = null,
                                onTrust = E2eeDeviceList.trustAction(row)?.let { action -> { trust = DeviceTrustRequest(userId, row, action) } },
                                padded = false,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.durio_verify_close)) } },
    )
}

/** Members of the chat (you first) and their devices; this device is left out. */
private suspend fun channelDevices(channelId: String): List<Pair<String, List<DeviceRow>>> {
    val active = E2eeManager.active ?: return emptyList()
    val selfId = active.account.userId
    val recipients = StoatAPI.channelCache[channelId]?.recipients.orEmpty()
    val users = listOf(selfId) + recipients.filter { it != selfId }.distinct()
    val undelivered = active.core.undeliveredDevices()
    return users.map { userId ->
        val devices = active.core.devices(userId).filter { it.deviceId != active.core.deviceId }
        userId to E2eeDeviceList.of(devices, undelivered)
    }
}

private fun userName(userId: String): String =
    StoatAPI.userCache[userId]?.let { User.resolveDefaultName(it, withDiscriminator = true) } ?: userId
