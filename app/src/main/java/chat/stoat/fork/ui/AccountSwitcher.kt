package chat.stoat.fork.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import chat.stoat.R
import chat.stoat.composables.generic.RemoteImage
import chat.stoat.fork.account.Account
import chat.stoat.fork.account.AccountManager
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.cancel
import chat.stoat.shared.resources.durio_accounts
import chat.stoat.shared.resources.durio_accounts_active
import chat.stoat.shared.resources.durio_accounts_add
import chat.stoat.shared.resources.durio_accounts_leave_call_body
import chat.stoat.shared.resources.durio_accounts_leave_call_confirm
import chat.stoat.shared.resources.durio_accounts_leave_call_title
import chat.stoat.shared.resources.durio_accounts_switching
import chat.stoat.voice.VoiceCallManager
import org.jetbrains.compose.resources.stringResource

/**
 * Accounts on this device with "Add account" (plan AN-10). Tapping another account switches to
 * it (AN-09); during a call the user confirms first, the switch ends the call.
 */
@Composable
fun AccountSwitcherList(modifier: Modifier = Modifier) {
    val state by AccountManager.state.collectAsState()
    val busy by AccountManager.busy.collectAsState()
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun whenCallAllows(action: () -> Unit) {
        if (VoiceCallManager.activeChannelId != null) pending = action else action()
    }

    pending?.let { action ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(Res.string.durio_accounts_leave_call_title)) },
            text = { Text(stringResource(Res.string.durio_accounts_leave_call_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    action()
                }) {
                    Text(stringResource(Res.string.durio_accounts_leave_call_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        )
    }

    // two accounts with the same name are told apart by their server
    val duplicateNames = state.accounts.groupBy { it.displayName }.filterValues { it.size > 1 }.keys

    Column(modifier) {
        state.accounts.sortedBy { it.addedAt }.forEach { account ->
            val isActive = account.key == state.activeKey
            AccountRow(
                account = account,
                isActive = isActive,
                showHost = true,
                highlightHost = account.displayName in duplicateNames,
                enabled = !busy,
                onClick = { if (!isActive) whenCallAllows { AccountManager.switchTo(account.key) } },
            )
        }

        ListItem(
            headlineContent = { Text(stringResource(Res.string.durio_accounts_add)) },
            leadingContent = {
                Icon(
                    painter = painterResource(R.drawable.ic_add_24dp),
                    contentDescription = null,
                    modifier = Modifier.padding(8.dp)
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !busy) { whenCallAllows { AccountManager.beginAddAccount() } }
        )

        if (busy) {
            ListItem(
                headlineContent = { Text(stringResource(Res.string.durio_accounts_switching)) },
                leadingContent = { CircularProgressIndicator(Modifier.size(24.dp)) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

@Composable
private fun AccountRow(
    account: Account,
    isActive: Boolean,
    showHost: Boolean,
    highlightHost: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val name = account.displayName ?: account.userId
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = if (showHost) {
            {
                Text(
                    text = account.host,
                    color = if (highlightHost) MaterialTheme.colorScheme.primary else Color.Unspecified
                )
            }
        } else null,
        leadingContent = {
            RemoteImage(
                // the avatar URL is absolute, the default avatar comes from the account's own server
                url = account.avatarUrl ?: "${account.endpoints.api}/users/${account.userId}/default_avatar",
                description = name,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
            )
        },
        trailingContent = if (isActive) {
            { Text(stringResource(Res.string.durio_accounts_active), style = MaterialTheme.typography.labelMedium) }
        } else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
    )
}

/** Below the status sheet that opens on a long press of the own avatar in the server rail. */
@Composable
fun AccountSwitcherSection() {
    Column {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(
            text = stringResource(Res.string.durio_accounts),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        AccountSwitcherList(Modifier.padding(bottom = 16.dp))
    }
}

/** Top of Settings: the active account; tapping it opens the switcher (plan AN-10). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsSettingsItem() {
    val state by AccountManager.state.collectAsState()
    var showSheet by remember { mutableStateOf(false) }
    val active = state.active ?: return

    AccountRow(
        account = active,
        isActive = false,
        showHost = true,
        highlightHost = false,
        enabled = true,
        onClick = { showSheet = true },
    )

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Text(
                text = stringResource(Res.string.durio_accounts),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            AccountSwitcherList(Modifier.padding(bottom = 16.dp))
        }
    }
}

