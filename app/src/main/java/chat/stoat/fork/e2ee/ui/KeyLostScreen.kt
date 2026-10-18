package chat.stoat.fork.e2ee.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavController
import chat.stoat.fork.account.AccountManager
import chat.stoat.fork.e2ee.E2eeManager
import chat.stoat.fork.e2ee.E2eeStatus
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.durio_e2ee_cancel
import chat.stoat.shared.resources.durio_keylost_action
import chat.stoat.shared.resources.durio_keylost_confirm
import chat.stoat.shared.resources.durio_keylost_confirm_text
import chat.stoat.shared.resources.durio_keylost_steps
import chat.stoat.shared.resources.durio_keylost_text
import chat.stoat.shared.resources.durio_keylost_title
import chat.stoat.shared.resources.durio_reset_manual
import org.jetbrains.compose.resources.stringResource

/**
 * "Encryption on this device was reset" (plan AN-42): the store key or the store is gone, or the
 * server has other keys for this session. Device keys on the server cannot change (C22), so the
 * only way back is a new session: log out (the store and its key are deleted, the session
 * revoked) and log in again — a new device, which then restores the backup or is verified with
 * another device.
 */
@Composable
fun KeyLostScreen(
    navController: NavController,
    accountKey: String? = AccountManager.active?.key,
    logout: (String?) -> Unit = AccountManager::logout,
) {
    var confirm by remember { mutableStateOf(false) }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(Res.string.durio_keylost_confirm)) },
            text = { Text(stringResource(Res.string.durio_keylost_confirm_text)) },
            confirmButton = {
                Button(onClick = {
                    confirm = false
                    logout(accountKey)
                }) { Text(stringResource(Res.string.durio_keylost_action)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(Res.string.durio_e2ee_cancel)) } },
        )
    }
    RecoveryScaffold(Res.string.durio_keylost_title, onBack = { navController.popBackStack() }) {
        val status by E2eeManager.status.collectAsState()
        Text(stringResource(if (status == E2eeStatus.KeyLost) Res.string.durio_keylost_text else Res.string.durio_reset_manual))
        Text(stringResource(Res.string.durio_keylost_steps))
        OutlinedButton(enabled = accountKey != null, onClick = { confirm = true }) { Text(stringResource(Res.string.durio_keylost_action)) }
    }
}
