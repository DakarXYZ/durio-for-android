package chat.stoat.fork.admin

import android.text.format.DateFormat
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import chat.stoat.R
import chat.stoat.api.fork.routes.AdminRequestException
import chat.stoat.api.fork.routes.AdminUser
import chat.stoat.api.fork.routes.DeviceSession
import chat.stoat.api.fork.routes.AdminInvite
import chat.stoat.api.fork.routes.createAdminInvite
import chat.stoat.api.fork.routes.elevateAdmin
import chat.stoat.api.fork.routes.fetchAdminInvites
import chat.stoat.api.fork.routes.revokeAdminInvite
import chat.stoat.core.model.fork.ActiveServer
import chat.stoat.fork.register.RegistrationInvite
import chat.stoat.api.fork.routes.endAdminElevation
import chat.stoat.api.fork.routes.endAllUserSessions
import chat.stoat.api.fork.routes.endUserSession
import chat.stoat.api.fork.routes.fetchAdminStatus
import chat.stoat.api.fork.routes.fetchUserSessions
import chat.stoat.api.fork.routes.findAdminUser
import chat.stoat.api.fork.routes.withAdminElevation
import chat.stoat.composables.mfa.MfaPromptSheet
import chat.stoat.composables.mfa.MfaPromptState
import chat.stoat.api.internals.ULID
import chat.stoat.fork.account.AccountManager
import chat.stoat.screens.settings.SettingsIcon
import chat.stoat.screens.settings.SettingsListItem
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.back
import chat.stoat.shared.resources.durio_admin_bot
import chat.stoat.shared.resources.durio_admin_end
import chat.stoat.shared.resources.durio_admin_end_all
import chat.stoat.shared.resources.durio_admin_end_all_title
import chat.stoat.shared.resources.durio_admin_end_self
import chat.stoat.shared.resources.durio_admin_end_text
import chat.stoat.shared.resources.durio_admin_end_title
import chat.stoat.shared.resources.durio_admin_ended
import chat.stoat.shared.resources.durio_admin_ended_all
import chat.stoat.shared.resources.durio_admin_failed
import chat.stoat.shared.resources.durio_admin_forbidden
import chat.stoat.shared.resources.durio_admin_hint
import chat.stoat.shared.resources.durio_admin_invalid_code
import chat.stoat.shared.resources.durio_admin_mode_end
import chat.stoat.shared.resources.durio_admin_mode_needed
import chat.stoat.shared.resources.durio_admin_mode_off
import chat.stoat.shared.resources.durio_admin_mode_on
import chat.stoat.shared.resources.durio_admin_mode_until
import chat.stoat.shared.resources.durio_admin_totp_required
import chat.stoat.shared.resources.durio_admin_not_found
import chat.stoat.shared.resources.durio_admin_search
import chat.stoat.shared.resources.durio_admin_search_label
import chat.stoat.shared.resources.durio_admin_sessions
import chat.stoat.shared.resources.durio_admin_sessions_none
import chat.stoat.shared.resources.durio_admin_title
import chat.stoat.shared.resources.durio_e2ee_cancel
import chat.stoat.shared.resources.durio_e2ee_devices_created
import chat.stoat.shared.resources.durio_e2ee_devices_last_seen
import chat.stoat.shared.resources.durio_e2ee_devices_this
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import logcat.LogPriority
import logcat.logcat
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import java.util.Date

const val ADMIN_ROUTE = "settings/admin"

/**
 * Whether the active account is an admin of its server (`GET /admin/@me`), remembered per
 * account so the settings entry does not flicker. Older servers have no admin routes: never.
 */
object AdminAccess {
    @Volatile
    private var known: Pair<String, Boolean>? = null

    fun cached(): Boolean {
        val key = AccountManager.active?.key ?: return false
        return known?.takeIf { it.first == key }?.second == true
    }

    suspend fun check(): Boolean {
        val key = AccountManager.active?.key ?: return false
        val admin = try {
            fetchAdminStatus().admin
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // offline: what we knew
            return cached()
        }
        known = key to admin
        return admin
    }
}

class AdminViewModel : ViewModel() {
    sealed interface Confirm {
        data class One(val session: DeviceSession) : Confirm
        data object All : Confirm
    }

    var query by mutableStateOf("")
    var busy by mutableStateOf(false)
    var user by mutableStateOf<AdminUser?>(null)
    var sessions by mutableStateOf<List<DeviceSession>?>(null)
    var problem by mutableStateOf<StringResource?>(null)
    var confirm by mutableStateOf<Confirm?>(null)

    /** Admin mode (2FA, about an hour, this session only): its end in epoch ms, null when off. */
    var elevatedUntilMs by mutableStateOf<Long?>(null)
    val mfa = MfaPromptState()

    /** Opening the screen: admin mode is asked for at once when it is off. */
    fun enter() {
        request {
            val status = fetchAdminStatus()
            elevatedUntilMs = status.elevatedUntilMs?.takeIf { status.elevated(System.currentTimeMillis()) }
            if (elevatedUntilMs == null && !elevate()) return@request
            invites = fetchAdminInvites()
        }
    }

    // ---- invite links (sign-up on an invite-only server)

    /** Active invites made in the app, newest first; null until loaded. */
    var invites by mutableStateOf<List<AdminInvite>?>(null)
    var inviteHours by mutableIntStateOf(AdminInvite.LIFETIMES_HOURS.last())
    var inviteMultiUse by mutableStateOf(false)
    var revoke by mutableStateOf<AdminInvite?>(null)

    /** `https://<host>/invite/<code>` on the active server's host. */
    fun inviteLink(code: String): String =
        AdminInvite.link(RegistrationInvite.hostOf(ActiveServer.currentOrNull?.api.orEmpty()), code)

    /** Creates an invite with the chosen lifetime and kind; [onCreated] gets its link. */
    fun createInvite(onCreated: (String) -> Unit) = request {
        val invite = createAdminInvite(inviteHours, inviteMultiUse)
        onCreated(inviteLink(invite.code))
        invites = fetchAdminInvites()
    }

    fun revokeInvite(invite: AdminInvite, onDone: () -> Unit) {
        revoke = null
        request {
            try {
                revokeAdminInvite(invite.code)
            } catch (e: AdminRequestException) {
                // gone already (expired or used up meanwhile)
                if (e.status != 404) throw e
            }
            onDone()
            invites = fetchAdminInvites()
        }
    }

    fun turnOn() = request { elevate() }

    fun endAdminMode() = request {
        endAdminElevation()
        elevatedUntilMs = null
    }

    /** The MFA sheet (an authenticator code first), then `POST /admin/elevate`; false when cancelled. */
    private suspend fun elevate(): Boolean {
        val ticket = mfa.request()?.token ?: return false
        elevatedUntilMs = elevateAdmin(ticket)
        return true
    }

    /** Our own session: ending it signs this device out. */
    val currentSessionId: String?
        get() = AccountManager.active?.sessionId

    val isSelf: Boolean
        get() = user?.id != null && user?.id == AccountManager.active?.userId

    fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        request {
            user = null
            sessions = null
            val found = findAdminUser(q)
            when {
                found == null -> problem = Res.string.durio_admin_not_found
                found.bot -> {
                    user = found
                    problem = Res.string.durio_admin_bot
                }
                else -> {
                    user = found
                    sessions = sorted(fetchUserSessions(found.id))
                }
            }
        }
    }

    fun end(target: Confirm, onDone: (StringResource) -> Unit) {
        val found = user ?: return
        confirm = null
        request {
            when (target) {
                is Confirm.One -> endUserSession(found.id, target.session.id)
                Confirm.All -> endAllUserSessions(found.id)
            }
            onDone(if (target == Confirm.All) Res.string.durio_admin_ended_all else Res.string.durio_admin_ended)
            sessions = sorted(fetchUserSessions(found.id))
        }
    }

    private fun request(block: suspend () -> Unit) {
        busy = true
        problem = null
        viewModelScope.launch {
            try {
                withAdminElevation(::elevate) { block() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AdminRequestException) {
                logcat(LogPriority.WARN) { "Admin request refused: ${e.status} ${e.type}" }
                if (e.needsElevation) elevatedUntilMs = null
                problem = when {
                    e.invalidTicket -> Res.string.durio_admin_invalid_code
                    e.needsTotp -> Res.string.durio_admin_totp_required
                    e.needsElevation -> Res.string.durio_admin_mode_needed
                    e.status == 403 -> Res.string.durio_admin_forbidden
                    else -> Res.string.durio_admin_failed
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "Admin request failed: ${e::class.simpleName}" }
                problem = Res.string.durio_admin_failed
            }
            busy = false
        }
    }

    private fun sorted(list: List<DeviceSession>) =
        list.sortedByDescending { it.lastSeenMs ?: created(it.id) ?: 0 }
}

internal fun created(id: String): Long? = try {
    ULID.asTimestamp(id)
} catch (e: Exception) {
    null
}

/** `MainActivity`'s hook: the admin screen. */
fun NavGraphBuilder.adminScreens(navController: NavController) {
    composable(ADMIN_ROUTE) { AdminScreen(navController) }
}

/** The settings list's hook: "Administration", only for admins of the active account's server. */
@Composable
fun AdminSettingsEntry(navController: NavController) {
    var admin by remember { mutableStateOf(AdminAccess.cached()) }
    LaunchedEffect(Unit) { admin = AdminAccess.check() }
    if (!admin) return
    Spacer(Modifier.height(8.dp))
    SettingsListItem(
        first = true,
        last = true,
        headlineContent = { Text(stringResource(Res.string.durio_admin_title)) },
        leadingContent = {
            SettingsIcon { Icon(painterResource(R.drawable.ic_shield_lock_24dp), contentDescription = null) }
        },
        modifier = Modifier.clickable { navController.navigate(ADMIN_ROUTE) }
    )
}

/**
 * "Administration" (dur.io server admins): find a user by ID or `username#1234`, see their
 * sessions and end one or all of them, e.g. after a lost phone. Ending a session removes its
 * E2EE device too, which the confirmation says.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(navController: NavController, viewModel: AdminViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { viewModel.enter() }
    MfaPromptSheet(viewModel.mfa)
    RevokeInviteDialog(viewModel)

    viewModel.confirm?.let { target ->
        val user = viewModel.user
        AlertDialog(
            onDismissRequest = { viewModel.confirm = null },
            title = {
                Text(
                    when (target) {
                        is AdminViewModel.Confirm.One -> stringResource(Res.string.durio_admin_end_title)
                        AdminViewModel.Confirm.All -> stringResource(Res.string.durio_admin_end_all_title, user?.tag.orEmpty())
                    }
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (target is AdminViewModel.Confirm.One) Text(target.session.name, style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(Res.string.durio_admin_end_text))
                    val ownSession = target is AdminViewModel.Confirm.One && target.session.id == viewModel.currentSessionId
                    if (ownSession || (target == AdminViewModel.Confirm.All && viewModel.isSelf)) {
                        Text(stringResource(Res.string.durio_admin_end_self), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        viewModel.end(target) { done ->
                            scope.launch { Toast.makeText(context, getString(done), Toast.LENGTH_SHORT).show() }
                        }
                    },
                ) {
                    Text(stringResource(if (target == AdminViewModel.Confirm.All) Res.string.durio_admin_end_all else Res.string.durio_admin_end))
                }
            },
            dismissButton = { TextButton(onClick = { viewModel.confirm = null }) { Text(stringResource(Res.string.durio_e2ee_cancel)) } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.durio_admin_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(painterResource(R.drawable.ic_arrow_back_24dp), contentDescription = stringResource(Res.string.back))
                    }
                },
            )
        }
    ) { pv ->
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(pv)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            item(key = "hint") { Text(stringResource(Res.string.durio_admin_hint), style = MaterialTheme.typography.bodySmall) }
            item(key = "mode") {
                AdminModeRow(viewModel.elevatedUntilMs, enabled = !viewModel.busy, onTurnOn = viewModel::turnOn, onEnd = viewModel::endAdminMode)
            }
            item(key = "search") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = viewModel.query,
                        onValueChange = { viewModel.query = it },
                        label = { Text(stringResource(Res.string.durio_admin_search_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
                        modifier = Modifier.weight(1f),
                    )
                    Button(enabled = !viewModel.busy && viewModel.query.isNotBlank(), onClick = { viewModel.search() }) {
                        Text(stringResource(Res.string.durio_admin_search))
                    }
                }
            }
            if (viewModel.busy) item(key = "busy") { CircularProgressIndicator() }
            viewModel.problem?.let { problem ->
                item(key = "problem") { Text(stringResource(problem), color = MaterialTheme.colorScheme.error) }
            }
            viewModel.user?.let { user ->
                item(key = "user") {
                    Column(Modifier.padding(top = 8.dp)) {
                        Text(user.displayName ?: user.username, style = MaterialTheme.typography.titleMedium)
                        Text("${user.tag} · ${user.id}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            viewModel.sessions?.let { sessions ->
                item(key = "sessionsHeader") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(Res.string.durio_admin_sessions), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (sessions.isNotEmpty()) {
                            OutlinedButton(enabled = !viewModel.busy, onClick = { viewModel.confirm = AdminViewModel.Confirm.All }) {
                                Text(stringResource(Res.string.durio_admin_end_all))
                            }
                        }
                    }
                }
                if (sessions.isEmpty()) item(key = "none") { Text(stringResource(Res.string.durio_admin_sessions_none)) }
                items(sessions, key = { it.id }) { session ->
                    SessionItem(
                        session = session,
                        current = session.id == viewModel.currentSessionId,
                        enabled = !viewModel.busy,
                        onEnd = { viewModel.confirm = AdminViewModel.Confirm.One(session) },
                    )
                }
            }
            adminInvites(viewModel)
        }
    }
}

/** "Admin mode until 14:05" and "End admin mode", or "off" and "Turn on" (2FA). */
@Composable
private fun AdminModeRow(untilMs: Long?, enabled: Boolean, onTurnOn: () -> Unit, onEnd: () -> Unit) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(if (untilMs != null) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            if (untilMs != null) {
                stringResource(Res.string.durio_admin_mode_until, DateFormat.getTimeFormat(context).format(Date(untilMs)))
            } else {
                stringResource(Res.string.durio_admin_mode_off)
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (untilMs != null) {
            TextButton(enabled = enabled, onClick = onEnd) { Text(stringResource(Res.string.durio_admin_mode_end)) }
        } else {
            TextButton(enabled = enabled, onClick = onTurnOn) { Text(stringResource(Res.string.durio_admin_mode_on)) }
        }
    }
}

@Composable
private fun SessionItem(session: DeviceSession, current: Boolean, enabled: Boolean, onEnd: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(session.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (current) Text(stringResource(Res.string.durio_e2ee_devices_this), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            created(session.id)?.let {
                Text(stringResource(Res.string.durio_e2ee_devices_created, DateUtils.getRelativeTimeSpanString(it).toString()), style = MaterialTheme.typography.bodySmall)
            }
            session.lastSeenMs?.let {
                Text(stringResource(Res.string.durio_e2ee_devices_last_seen, DateUtils.getRelativeTimeSpanString(it).toString()), style = MaterialTheme.typography.bodySmall)
            }
        }
        IconButton(enabled = enabled, onClick = onEnd) {
            Icon(painterResource(R.drawable.ic_logout_24dp), contentDescription = stringResource(Res.string.durio_admin_end))
        }
    }
}
