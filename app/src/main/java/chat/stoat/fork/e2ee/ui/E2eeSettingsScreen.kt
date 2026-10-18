package chat.stoat.fork.e2ee.ui

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import chat.stoat.R
import chat.stoat.api.fork.e2ee.E2eeBackupStatus
import chat.stoat.api.fork.e2ee.E2eeIdentityKeys
import chat.stoat.api.fork.routes.fetchDeviceSessions
import chat.stoat.api.routes.auth.logoutSessionById
import chat.stoat.composables.generic.ListHeader
import chat.stoat.fork.e2ee.DeviceRow
import chat.stoat.fork.e2ee.DeviceTrust
import chat.stoat.fork.e2ee.E2eeDeviceList
import chat.stoat.fork.e2ee.E2eeManager
import chat.stoat.fork.e2ee.E2eeStatus
import chat.stoat.fork.e2ee.E2eeVerifications
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.back
import chat.stoat.shared.resources.durio_e2ee_beta
import chat.stoat.shared.resources.durio_e2ee_cancel
import chat.stoat.shared.resources.durio_e2ee_copied
import chat.stoat.shared.resources.durio_e2ee_devices_backup
import chat.stoat.shared.resources.durio_e2ee_devices_backup_keys
import chat.stoat.shared.resources.durio_e2ee_devices_backup_none
import chat.stoat.shared.resources.durio_e2ee_devices_backup_on
import chat.stoat.shared.resources.durio_e2ee_devices_backup_outdated
import chat.stoat.shared.resources.durio_e2ee_devices_created
import chat.stoat.shared.resources.durio_e2ee_devices_fingerprint
import chat.stoat.shared.resources.durio_e2ee_devices_last_seen
import chat.stoat.shared.resources.durio_e2ee_devices_load_failed
import chat.stoat.shared.resources.durio_e2ee_devices_logout
import chat.stoat.shared.resources.durio_e2ee_devices_logout_confirm
import chat.stoat.shared.resources.durio_e2ee_devices_logout_text
import chat.stoat.shared.resources.durio_e2ee_devices_misses_keys
import chat.stoat.shared.resources.durio_e2ee_devices_other
import chat.stoat.shared.resources.durio_e2ee_devices_reinstall_hint
import chat.stoat.shared.resources.durio_e2ee_devices_reset
import chat.stoat.shared.resources.durio_e2ee_devices_reset_text
import chat.stoat.shared.resources.durio_e2ee_devices_restore
import chat.stoat.shared.resources.durio_e2ee_devices_rotate
import chat.stoat.shared.resources.durio_e2ee_devices_set_up
import chat.stoat.shared.resources.durio_e2ee_devices_status_key_lost
import chat.stoat.shared.resources.durio_e2ee_devices_status_off
import chat.stoat.shared.resources.durio_e2ee_devices_status_ready
import chat.stoat.shared.resources.durio_e2ee_devices_status_starting
import chat.stoat.shared.resources.durio_e2ee_devices_status_unavailable
import chat.stoat.shared.resources.durio_e2ee_devices_this
import chat.stoat.shared.resources.durio_e2ee_devices_title
import chat.stoat.shared.resources.durio_e2ee_devices_trust_blocked
import chat.stoat.shared.resources.durio_e2ee_devices_trust_none
import chat.stoat.shared.resources.durio_e2ee_devices_trust_removed
import chat.stoat.shared.resources.durio_e2ee_devices_trust_unverified
import chat.stoat.shared.resources.durio_e2ee_devices_trust_verified
import chat.stoat.shared.resources.durio_e2ee_devices_unverified_hint
import chat.stoat.shared.resources.durio_e2ee_devices_verify
import chat.stoat.shared.resources.durio_e2ee_privacy
import chat.stoat.shared.resources.durio_verify_not_started
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import logcat.LogPriority
import logcat.logcat
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

class E2eeSettingsViewModel : ViewModel() {
    var loading by mutableStateOf(true)
    var failed by mutableStateOf(false)
    var identity by mutableStateOf<E2eeIdentityKeys?>(null)
    var devices by mutableStateOf<List<DeviceRow>>(emptyList())
    var backup by mutableStateOf<E2eeBackupStatus?>(null)

    fun load() {
        viewModelScope.launch {
            loading = true
            failed = false
            val active = E2eeManager.active
            try {
                val sessions = active?.let { fetchDeviceSessions(it.http) }.orEmpty()
                if (active == null) {
                    identity = null
                    backup = null
                    devices = emptyList()
                } else {
                    identity = active.core.identityKeys()
                    backup = active.core.backupStatus()
                    devices = E2eeDeviceList.own(
                        currentSessionId = active.account.sessionId,
                        sessions = sessions,
                        devices = active.core.devices(active.account.userId),
                        undelivered = active.core.undeliveredDevices(),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "Devices not loaded: ${e::class.simpleName}" }
                failed = true
            }
            loading = false
        }
    }

    fun logout(deviceId: String) {
        viewModelScope.launch {
            val active = E2eeManager.active ?: return@launch
            try {
                logoutSessionById(deviceId, active.http)
                // the backend removes the device and sends E2eeDeviceListUpdate (BE cascade)
                devices = devices.filter { it.deviceId != deviceId }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
            }
        }
    }
}

/**
 * "Devices and encryption" (plan AN-30): this device and its fingerprint, the account's other
 * devices (session = device, C22) with their verification and "log out", the key backup (AN-32),
 * the privacy options (AN-33) and "reset encryption" (AN-42).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun E2eeSettingsScreen(navController: NavController, viewModel: E2eeSettingsViewModel = viewModel()) {
    val status by E2eeManager.status.collectAsState()
    LaunchedEffect(status) { viewModel.load() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var logout by remember { mutableStateOf<DeviceRow?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    logout?.let { row ->
        AlertDialog(
            onDismissRequest = { logout = null },
            title = { Text(stringResource(Res.string.durio_e2ee_devices_logout_confirm)) },
            text = { Text(stringResource(Res.string.durio_e2ee_devices_logout_text)) },
            confirmButton = {
                Button(onClick = {
                    logout = null
                    viewModel.logout(row.deviceId)
                }) { Text(stringResource(Res.string.durio_e2ee_devices_logout)) }
            },
            dismissButton = { TextButton(onClick = { logout = null }) { Text(stringResource(Res.string.durio_e2ee_cancel)) } },
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                scrollBehavior = scrollBehavior,
                title = { Text(stringResource(Res.string.durio_e2ee_devices_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(painterResource(R.drawable.ic_arrow_back_24dp), contentDescription = stringResource(Res.string.back))
                    }
                },
            )
        },
    ) { pv ->
        LazyColumn(Modifier.padding(pv).fillMaxSize()) {
            item(key = "status") { StatusCard(status) { navController.navigate(E2eeRoutes.RESET) } }

            val current = viewModel.devices.firstOrNull { it.current }
            item(key = "this") { ListHeader { Text(stringResource(Res.string.durio_e2ee_devices_this)) } }
            item(key = "thisDevice") {
                ThisDevice(current?.name, viewModel.identity) {
                    viewModel.identity?.let {
                        // a public key: a plain copy is fine
                        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("", it.ed25519))
                        scope.launch { Toast.makeText(context, getString(Res.string.durio_e2ee_copied), Toast.LENGTH_SHORT).show() }
                    }
                }
            }

            if (status == E2eeStatus.Ready) {
                item(key = "backupHeader") { ListHeader { Text(stringResource(Res.string.durio_e2ee_devices_backup)) } }
                item(key = "backup") {
                    BackupCard(
                        status = viewModel.backup,
                        onSetUp = { navController.navigate(E2eeRoutes.SETUP) },
                        onRestore = { navController.navigate(E2eeRoutes.RESTORE) },
                        onRotate = { navController.navigate(E2eeRoutes.ROTATE) },
                    )
                }
            }

            item(key = "otherHeader") { ListHeader { Text(stringResource(Res.string.durio_e2ee_devices_other)) } }
            if (viewModel.failed) item(key = "failed") { Hint(stringResource(Res.string.durio_e2ee_devices_load_failed), warning = true) }
            if (E2eeDeviceList.needsVerification(viewModel.devices)) {
                item(key = "unverifiedHint") { Hint(stringResource(Res.string.durio_e2ee_devices_unverified_hint), warning = false) }
            }
            items(viewModel.devices.filter { !it.current }, key = { it.deviceId }) { row ->
                DeviceItem(
                    row = row,
                    onVerify = if (status == E2eeStatus.Ready && row.trust == DeviceTrust.Unverified) {
                        {
                            scope.launch {
                                val selfId = E2eeManager.active?.account?.userId ?: return@launch
                                if (!E2eeVerifications.controller.start(selfId, row.deviceId)) {
                                    Toast.makeText(context, getString(Res.string.durio_verify_not_started), Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } else null,
                    onLogout = if (row.trust != DeviceTrust.Removed) ({ logout = row }) else null,
                )
            }
            item(key = "reinstallHint") { Hint(stringResource(Res.string.durio_e2ee_devices_reinstall_hint), warning = false) }

            item(key = "privacyHeader") { ListHeader { Text(stringResource(Res.string.durio_e2ee_privacy)) } }
            item(key = "privacy") { E2eePrivacySettings() }

            item(key = "reset") {
                Column(Modifier.padding(16.dp)) {
                    OutlinedButton(onClick = { navController.navigate(E2eeRoutes.RESET) }) {
                        Text(stringResource(Res.string.durio_e2ee_devices_reset))
                    }
                    Text(
                        stringResource(Res.string.durio_e2ee_devices_reset_text),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusCard(status: E2eeStatus, onReset: () -> Unit) {
    val (text, warning) = when (status) {
        E2eeStatus.Ready -> Res.string.durio_e2ee_devices_status_ready to false
        E2eeStatus.Starting -> Res.string.durio_e2ee_devices_status_starting to false
        E2eeStatus.KeyLost -> Res.string.durio_e2ee_devices_status_key_lost to true
        E2eeStatus.Off -> Res.string.durio_e2ee_devices_status_off to false
        else -> Res.string.durio_e2ee_devices_status_unavailable to true
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp)
    ) {
        Icon(painterResource(R.drawable.ic_shield_lock_24dp), contentDescription = null)
        Column(Modifier.weight(1f)) {
            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(Res.string.durio_e2ee_beta), style = MaterialTheme.typography.labelSmall)
        }
        if (status == E2eeStatus.KeyLost) FilledTonalButton(onClick = onReset) { Text(stringResource(Res.string.durio_e2ee_devices_reset)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThisDevice(name: String?, identity: E2eeIdentityKeys?, onCopy: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (name != null) Text(name, style = MaterialTheme.typography.titleSmall)
        if (identity != null) {
            Text(stringResource(Res.string.durio_e2ee_devices_fingerprint), style = MaterialTheme.typography.labelMedium)
            Text(
                identity.fingerprint,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onCopy),
            )
        }
    }
}

@Composable
private fun BackupCard(status: E2eeBackupStatus?, onSetUp: () -> Unit, onRestore: () -> Unit, onRotate: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            status == null -> Unit
            status.outdated -> Hint(stringResource(Res.string.durio_e2ee_devices_backup_outdated), warning = true, padded = false)
            status.enabled -> {
                Text(stringResource(Res.string.durio_e2ee_devices_backup_on))
                Text(
                    stringResource(Res.string.durio_e2ee_devices_backup_keys, status.keysBackedUp, status.keysTotal),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            else -> Text(stringResource(Res.string.durio_e2ee_devices_backup_none))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (status?.enabled != true || status.outdated) FilledTonalButton(onClick = onRestore) { Text(stringResource(Res.string.durio_e2ee_devices_restore)) }
            FilledTonalButton(onClick = onSetUp) { Text(stringResource(Res.string.durio_e2ee_devices_set_up)) }
        }
        if (status?.recoverySetUp == true && !status.outdated) {
            TextButton(onClick = onRotate) { Text(stringResource(Res.string.durio_e2ee_devices_rotate)) }
        }
    }
}

@Composable
fun DeviceItem(row: DeviceRow, onVerify: (() -> Unit)?, onLogout: (() -> Unit)?) {
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.name ?: row.deviceId, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TrustBadge(row.trust)
            }
            if (onLogout != null) {
                IconButton(onClick = onLogout) {
                    Icon(painterResource(R.drawable.ic_logout_24dp), contentDescription = stringResource(Res.string.durio_e2ee_devices_logout))
                }
            }
        }
        val created = row.createdMs?.let { DateUtils.getRelativeTimeSpanString(it).toString() }
        val seen = row.lastSeenMs?.let { DateUtils.getRelativeTimeSpanString(it).toString() }
        if (created != null) Text(stringResource(Res.string.durio_e2ee_devices_created, created), style = MaterialTheme.typography.bodySmall)
        if (seen != null) Text(stringResource(Res.string.durio_e2ee_devices_last_seen, seen), style = MaterialTheme.typography.bodySmall)
        row.ed25519?.let {
            Text(E2eeIdentityKeys.fingerprint(it), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        if (row.missesKeys) Text(stringResource(Res.string.durio_e2ee_devices_misses_keys), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (onVerify != null) {
            Spacer(Modifier.height(4.dp))
            FilledTonalButton(onClick = onVerify) { Text(stringResource(Res.string.durio_e2ee_devices_verify)) }
        }
    }
}

@Composable
private fun TrustBadge(trust: DeviceTrust) {
    val (text, icon, warning) = when (trust) {
        DeviceTrust.Verified -> Triple(Res.string.durio_e2ee_devices_trust_verified, R.drawable.ic_verified_24dp__fill, false)
        DeviceTrust.Unverified -> Triple(Res.string.durio_e2ee_devices_trust_unverified, R.drawable.ic_shield_lock_24dp, true)
        DeviceTrust.Blocked -> Triple(Res.string.durio_e2ee_devices_trust_blocked, R.drawable.ic_block_24dp, true)
        DeviceTrust.NoEncryption -> Triple(Res.string.durio_e2ee_devices_trust_none, R.drawable.ic_lock_24dp, false)
        DeviceTrust.Removed -> Triple(Res.string.durio_e2ee_devices_trust_removed, R.drawable.ic_block_24dp, false)
    }
    val color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(stringResource(text), color = color, style = MaterialTheme.typography.labelMedium)
    }
}
