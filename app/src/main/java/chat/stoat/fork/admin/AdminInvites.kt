package chat.stoat.fork.admin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import chat.stoat.api.fork.routes.AdminInvite
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.durio_admin_invite_copied
import chat.stoat.shared.resources.durio_admin_invite_copy
import chat.stoat.shared.resources.durio_admin_invite_create
import chat.stoat.shared.resources.durio_admin_invite_expires
import chat.stoat.shared.resources.durio_admin_invite_hours
import chat.stoat.shared.resources.durio_admin_invite_multi
import chat.stoat.shared.resources.durio_admin_invite_multi_uses
import chat.stoat.shared.resources.durio_admin_invite_revoke
import chat.stoat.shared.resources.durio_admin_invite_revoke_text
import chat.stoat.shared.resources.durio_admin_invite_revoke_title
import chat.stoat.shared.resources.durio_admin_invite_revoked
import chat.stoat.shared.resources.durio_admin_invite_share
import chat.stoat.shared.resources.durio_admin_invite_single
import chat.stoat.shared.resources.durio_admin_invites
import chat.stoat.shared.resources.durio_admin_invites_none
import chat.stoat.shared.resources.durio_admin_invites_text
import chat.stoat.shared.resources.durio_e2ee_cancel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * The admin screen's "Invites": invite links for signing up on this (invite-only) server, for
 * 1–24 hours, for one person or several; the active ones with copy, share and revoke.
 */
internal fun LazyListScope.adminInvites(viewModel: AdminViewModel) {
    item(key = "invitesHeader") {
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(Res.string.durio_admin_invites), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(Res.string.durio_admin_invites_text), style = MaterialTheme.typography.bodySmall)
        }
    }
    item(key = "invitesCreate") { CreateInvite(viewModel) }
    val invites = viewModel.invites ?: return
    if (invites.isEmpty()) item(key = "invitesNone") { Text(stringResource(Res.string.durio_admin_invites_none)) }
    items(invites, key = { "invite-${it.code}" }) { InviteItem(viewModel, it) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateInvite(viewModel: AdminViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AdminInvite.LIFETIMES_HOURS.forEach { hours ->
                FilterChip(
                    selected = viewModel.inviteHours == hours,
                    onClick = { viewModel.inviteHours = hours },
                    label = { Text(stringResource(Res.string.durio_admin_invite_hours, hours)) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = viewModel.inviteMultiUse, onCheckedChange = { viewModel.inviteMultiUse = it })
            Text(stringResource(Res.string.durio_admin_invite_multi))
        }
        Button(enabled = !viewModel.busy, onClick = {
            viewModel.createInvite { link ->
                scope.launch {
                    copy(context, link)
                    share(context, link)
                }
            }
        }) { Text(stringResource(Res.string.durio_admin_invite_create)) }
    }
}

@Composable
private fun InviteItem(viewModel: AdminViewModel, invite: AdminInvite) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val link = viewModel.inviteLink(invite.code)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp)
    ) {
        Text(shortCode(invite.code), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleSmall)
        invite.expiresAtMs?.let {
            Text(stringResource(Res.string.durio_admin_invite_expires, formatExpiry(context, it)), style = MaterialTheme.typography.bodySmall)
        }
        Text(
            if (invite.multiUse) {
                stringResource(Res.string.durio_admin_invite_multi_uses, invite.uses)
            } else {
                stringResource(Res.string.durio_admin_invite_single)
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { scope.launch { copy(context, link) } }) { Text(stringResource(Res.string.durio_admin_invite_copy)) }
            TextButton(onClick = { share(context, link) }) { Text(stringResource(Res.string.durio_admin_invite_share)) }
            TextButton(enabled = !viewModel.busy, onClick = { viewModel.revoke = invite }) {
                Text(stringResource(Res.string.durio_admin_invite_revoke), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** "Revoke this invite?" for [AdminViewModel.revoke]. */
@Composable
internal fun RevokeInviteDialog(viewModel: AdminViewModel) {
    val invite = viewModel.revoke ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { viewModel.revoke = null },
        title = { Text(stringResource(Res.string.durio_admin_invite_revoke_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(shortCode(invite.code), fontFamily = FontFamily.Monospace)
                Text(stringResource(Res.string.durio_admin_invite_revoke_text))
            }
        },
        confirmButton = {
            Button(
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                onClick = {
                    viewModel.revokeInvite(invite) {
                        scope.launch { Toast.makeText(context, getString(Res.string.durio_admin_invite_revoked), Toast.LENGTH_SHORT).show() }
                    }
                },
            ) { Text(stringResource(Res.string.durio_admin_invite_revoke)) }
        },
        dismissButton = { TextButton(onClick = { viewModel.revoke = null }) { Text(stringResource(Res.string.durio_e2ee_cancel)) } },
    )
}

/** The start of a code: enough to tell invites apart on screen, the link carries all of it. */
internal fun shortCode(code: String) = if (code.length <= 8) code else code.take(6) + "…"

private fun formatExpiry(context: Context, ms: Long): String {
    val flags = DateUtils.FORMAT_SHOW_TIME or if (DateUtils.isToday(ms)) 0 else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
    return DateUtils.formatDateTime(context, ms, flags)
}

private suspend fun copy(context: Context, link: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("", link))
    Toast.makeText(context, getString(Res.string.durio_admin_invite_copied), Toast.LENGTH_SHORT).show()
}

private fun share(context: Context, link: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, link)
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
