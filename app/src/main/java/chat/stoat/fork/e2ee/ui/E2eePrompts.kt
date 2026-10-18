package chat.stoat.fork.e2ee.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import chat.stoat.R
import chat.stoat.api.StoatAPI
import chat.stoat.fork.e2ee.DeviceRow
import chat.stoat.fork.e2ee.DeviceTrust
import chat.stoat.fork.e2ee.E2eeDeviceList
import chat.stoat.fork.e2ee.E2eeManager
import chat.stoat.fork.e2ee.E2eeOnboarding
import chat.stoat.fork.e2ee.E2eeStatus
import chat.stoat.fork.e2ee.E2eeVerifications
import chat.stoat.screens.settings.SettingsIcon
import chat.stoat.screens.settings.SettingsListItem
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.durio_e2ee_devices_title
import chat.stoat.shared.resources.durio_keylost_action
import chat.stoat.shared.resources.durio_keylost_later
import chat.stoat.shared.resources.durio_keylost_text
import chat.stoat.shared.resources.durio_keylost_title
import chat.stoat.shared.resources.durio_onboarding_later
import chat.stoat.shared.resources.durio_onboarding_new_device
import chat.stoat.shared.resources.durio_onboarding_new_device_title
import chat.stoat.shared.resources.durio_onboarding_restore
import chat.stoat.shared.resources.durio_onboarding_set_up
import chat.stoat.shared.resources.durio_onboarding_set_up_text
import chat.stoat.shared.resources.durio_onboarding_set_up_title
import chat.stoat.shared.resources.durio_onboarding_verify
import chat.stoat.shared.resources.durio_user_devices
import chat.stoat.shared.resources.durio_verify_not_started
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** Routes of the E2EE screens (`settings/...` like the others, AN-30). */
object E2eeRoutes {
    const val DEVICES = "settings/e2ee"
    const val SETUP = "settings/e2ee/recovery"
    const val ROTATE = "settings/e2ee/recovery/rotate"
    const val RESTORE = "settings/e2ee/restore"
    const val RESET = "settings/e2ee/reset"
}

/** `MainActivity`'s hook: the screens of AN-30..32 and AN-42. */
fun NavGraphBuilder.e2eeScreens(navController: NavController) {
    composable(E2eeRoutes.DEVICES) { E2eeSettingsScreen(navController) }
    composable(E2eeRoutes.SETUP) { RecoverySetupScreen(navController, rotate = false) }
    composable(E2eeRoutes.ROTATE) { RecoverySetupScreen(navController, rotate = true) }
    composable(E2eeRoutes.RESTORE) { RecoveryRestoreScreen(navController) }
    composable(E2eeRoutes.RESET) { KeyLostScreen(navController) }
}

/** The settings list's hook: "Devices and encryption" under "Sessions". */
@Composable
fun E2eeSettingsEntry(navController: NavController) {
    Spacer(Modifier.height(8.dp))
    SettingsListItem(
        first = true,
        last = true,
        headlineContent = { Text(stringResource(Res.string.durio_e2ee_devices_title)) },
        leadingContent = {
            SettingsIcon { Icon(painterResource(R.drawable.ic_shield_lock_24dp), contentDescription = null) }
        },
        modifier = Modifier.clickable { navController.navigate(E2eeRoutes.DEVICES) }
    )
}

/**
 * Over every screen (`AppEntrypoint`'s hook): the verification dialog (AN-31), the first prompt
 * of a device (verify or restore on a new one, set up recovery on the first; AN-31/32) and the
 * "encryption on this device was reset" prompt (AN-42).
 */
@Composable
fun E2eePrompts(navController: NavController) {
    val context = LocalContext.current
    val status by E2eeManager.status.collectAsState()
    val onboarding by E2eeOnboarding.prompt.collectAsState()
    var keyLostSeen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(status) {
        if (status == E2eeStatus.Ready) E2eeOnboarding.check(context)
    }

    VerificationDialog()

    if (status == E2eeStatus.KeyLost && !keyLostSeen) {
        AlertDialog(
            onDismissRequest = { keyLostSeen = true },
            icon = { Icon(painterResource(R.drawable.ic_error_24dp), contentDescription = null) },
            title = { Text(stringResource(Res.string.durio_keylost_title)) },
            text = { Text(stringResource(Res.string.durio_keylost_text)) },
            confirmButton = {
                Button(onClick = {
                    keyLostSeen = true
                    navController.navigate(E2eeRoutes.RESET)
                }) { Text(stringResource(Res.string.durio_keylost_action)) }
            },
            dismissButton = { TextButton(onClick = { keyLostSeen = true }) { Text(stringResource(Res.string.durio_keylost_later)) } },
        )
        return
    }

    when (onboarding) {
        E2eeOnboarding.Prompt.NewDevice -> AlertDialog(
            onDismissRequest = { E2eeOnboarding.dismiss(context) },
            icon = { Icon(painterResource(R.drawable.ic_shield_lock_24dp), contentDescription = null) },
            title = { Text(stringResource(Res.string.durio_onboarding_new_device_title)) },
            text = { Text(stringResource(Res.string.durio_onboarding_new_device)) },
            confirmButton = {
                Column {
                    Button(onClick = {
                        E2eeOnboarding.dismiss(context)
                        navController.navigate(E2eeRoutes.DEVICES)
                    }) { Text(stringResource(Res.string.durio_onboarding_verify)) }
                    TextButton(onClick = {
                        E2eeOnboarding.dismiss(context)
                        navController.navigate(E2eeRoutes.RESTORE)
                    }) { Text(stringResource(Res.string.durio_onboarding_restore)) }
                }
            },
            dismissButton = { TextButton(onClick = { E2eeOnboarding.dismiss(context) }) { Text(stringResource(Res.string.durio_onboarding_later)) } },
        )
        E2eeOnboarding.Prompt.SetUpRecovery -> AlertDialog(
            onDismissRequest = { E2eeOnboarding.dismiss(context) },
            icon = { Icon(painterResource(R.drawable.ic_key_24dp), contentDescription = null) },
            title = { Text(stringResource(Res.string.durio_onboarding_set_up_title)) },
            text = { Text(stringResource(Res.string.durio_onboarding_set_up_text)) },
            confirmButton = {
                Button(onClick = {
                    E2eeOnboarding.dismiss(context)
                    navController.navigate(E2eeRoutes.SETUP)
                }) { Text(stringResource(Res.string.durio_onboarding_set_up)) }
            },
            dismissButton = { TextButton(onClick = { E2eeOnboarding.dismiss(context) }) { Text(stringResource(Res.string.durio_onboarding_later)) } },
        )
        null -> Unit
    }
}

/** The profile sheets' hook (AN-30): devices of another user and "verify", if E2EE knows them. */
fun LazyStaggeredGridScope.e2eeUserDevices(userId: String) {
    if (userId == StoatAPI.selfId) return
    item(key = "durio-e2ee-devices", span = StaggeredGridItemSpan.FullLine) { UserDevices(userId) }
}

@Composable
private fun UserDevices(userId: String) {
    val status by E2eeManager.status.collectAsState()
    if (status != E2eeStatus.Ready) return
    var rows by remember(userId) { mutableStateOf<List<DeviceRow>?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(userId) {
        rows = try {
            E2eeManager.active?.core?.let { E2eeDeviceList.of(it.devices(userId), it.undeliveredDevices()) }
        } catch (e: Exception) {
            null
        }
    }
    val devices = rows ?: return
    // users we share no encrypted chat with: the core does not track them
    if (devices.isEmpty()) return
    Column {
        Text(stringResource(Res.string.durio_user_devices), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
        devices.forEach { row ->
            DeviceItem(
                row = row,
                onVerify = if (row.trust == DeviceTrust.Unverified) {
                    {
                        scope.launch {
                            if (!E2eeVerifications.controller.start(userId, row.deviceId)) {
                                Toast.makeText(context, getString(Res.string.durio_verify_not_started), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else null,
                onLogout = null,
            )
        }
    }
}
