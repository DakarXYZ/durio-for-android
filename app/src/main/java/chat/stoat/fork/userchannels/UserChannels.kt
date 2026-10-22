package chat.stoat.fork.userchannels

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.stoat.R
import chat.stoat.api.StoatAPI
import chat.stoat.api.fork.routes.MAX_USER_CHANNEL_LIMIT
import chat.stoat.api.fork.routes.setUserChannelLimit
import chat.stoat.api.internals.ChannelUtils
import chat.stoat.api.routes.server.createServerChannel
import chat.stoat.api.routes.server.fetchMembers
import chat.stoat.composables.generic.UserAvatar
import chat.stoat.core.model.schemas.ChannelType
import chat.stoat.core.model.schemas.User
import chat.stoat.internals.extensions.rememberServerPermissions
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.durio_thread_cancel
import chat.stoat.shared.resources.durio_user_channel_create
import chat.stoat.shared.resources.durio_user_channel_limit
import chat.stoat.shared.resources.durio_user_channel_limit_heading
import chat.stoat.shared.resources.durio_user_channel_limit_none
import chat.stoat.shared.resources.durio_user_channel_make
import chat.stoat.shared.resources.durio_user_channel_name
import chat.stoat.shared.resources.durio_user_channels_of
import chat.stoat.shared.resources.durio_member_overrides
import chat.stoat.shared.resources.durio_member_overrides_empty
import chat.stoat.shared.resources.durio_member_overrides_find
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

// dur.io: channels members make for themselves, and permissions of single members

/** Name a member goes by on a server: nickname, else display name, else the id */
fun memberName(serverId: String, userId: String): String =
    StoatAPI.members.getMember(serverId, userId)?.nickname
        ?: StoatAPI.userCache[userId]?.let { User.resolveDefaultName(it) }
        ?: userId

/** Channel list: "Channels <avatar> <name>" over a member's own channels */
@Composable
fun UserChannelsHeader(serverId: String, ownerId: String) {
    val user = StoatAPI.userCache[ownerId]
    val name = memberName(serverId, ownerId)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp),
    ) {
        Text(
            stringResource(Res.string.durio_user_channels_of),
            style = MaterialTheme.typography.labelLarge,
            fontSize = 16.sp,
        )
        UserAvatar(
            username = name,
            userId = ownerId,
            avatar = StoatAPI.members.getMember(serverId, ownerId)?.avatar ?: user?.avatar,
            size = 18.dp,
        )
        Text(
            name,
            style = MaterialTheme.typography.labelLarge,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Channel list: "Make a channel of your own", when the server lets members and we have
 * room for one more (members without ManageChannel)
 */
@Composable
fun MakeOwnChannelRow(serverId: String, onCreated: (String) -> Unit) {
    val server = StoatAPI.serverCache[serverId] ?: return
    val permissions by rememberServerPermissions(serverId)
    if (!ChannelUtils.canMakeOwnChannel(server, permissions)) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_add_24dp),
            contentDescription = null,
            modifier = Modifier
                .size(18.dp)
                .alpha(0.8f),
        )
        Text(
            stringResource(Res.string.durio_user_channel_make),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .padding(start = 12.dp)
                .alpha(0.8f),
        )
    }

    if (open) {
        AlertDialog(
            onDismissRequest = { if (!busy) open = false },
            title = { Text(stringResource(Res.string.durio_user_channel_make)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(32) },
                    label = { Text(stringResource(Res.string.durio_user_channel_name)) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    enabled = !busy && name.isNotBlank(),
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val channel = createServerChannel(serverId, name.trim(), ChannelType.TextChannel)
                                open = false
                                name = ""
                                channel.id?.let(onCreated)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Toast.makeText(context, e.message ?: "", Toast.LENGTH_SHORT).show()
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text(stringResource(Res.string.durio_user_channel_create)) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { open = false }) {
                    Text(stringResource(Res.string.durio_thread_cancel))
                }
            },
        )
    }
}

/** Server settings: how many text channels of their own each member may make (ManageServer) */
@Composable
fun UserChannelLimitSetting(serverId: String) {
    val server = StoatAPI.serverCache[serverId] ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var choosing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val current = server.userChannelLimit ?: 0

    @Composable
    fun label(limit: Int) =
        if (limit == 0) stringResource(Res.string.durio_user_channel_limit_none) else limit.toString()

    Text(
        text = stringResource(Res.string.durio_user_channel_limit_heading),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy) { choosing = true }
            .padding(vertical = 8.dp, horizontal = 16.dp),
    ) {
        Text(stringResource(Res.string.durio_user_channel_limit), style = MaterialTheme.typography.labelLarge)
        Text(label(current), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.alpha(0.8f))
    }

    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(Res.string.durio_user_channel_limit)) },
            text = {
                Column {
                    (0..MAX_USER_CHANNEL_LIMIT).forEach { limit ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    choosing = false
                                    if (limit == current) return@clickable
                                    busy = true
                                    scope.launch {
                                        try {
                                            setUserChannelLimit(serverId, limit)
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            Toast.makeText(context, e.message ?: "", Toast.LENGTH_SHORT).show()
                                        } finally {
                                            busy = false
                                        }
                                    }
                                }
                                .padding(vertical = 4.dp),
                        ) {
                            RadioButton(selected = limit == current, onClick = null)
                            Text(label(limit), modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choosing = false }) {
                    Text(stringResource(Res.string.durio_thread_cancel))
                }
            },
        )
    }
}

/**
 * Channel permissions: members with permissions of their own here, and a search to give
 * someone their own (for example read-only, or no reactions)
 */
@Composable
fun MemberOverridesSection(channelId: String, onOpen: (String) -> Unit) {
    val channel = StoatAPI.channelCache[channelId] ?: return
    val serverId = channel.server ?: return
    var query by remember { mutableStateOf("") }

    // the member list, to find members by name
    LaunchedEffect(serverId) {
        runCatching { fetchMembers(serverId, includeOffline = true) }
    }

    val overrides = channel.userPermissions.orEmpty().keys.toList()
    val matches = remember(query, overrides, StoatAPI.userCache.size) {
        val search = query.trim()
        if (search.isEmpty()) emptyList()
        else StoatAPI.members.membersOf(serverId)
            .mapNotNull { it.id?.user }
            .filter { it !in overrides }
            .filter { userId ->
                listOfNotNull(
                    memberName(serverId, userId),
                    StoatAPI.userCache[userId]?.username,
                ).any { it.contains(search, ignoreCase = true) }
            }
            .take(8)
    }

    Text(
        text = stringResource(Res.string.durio_member_overrides),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
    if (overrides.isEmpty()) {
        Text(
            stringResource(Res.string.durio_member_overrides_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    overrides.forEach { userId -> MemberRow(serverId, userId) { onOpen(userId) } }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text(stringResource(Res.string.durio_member_overrides_find)) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
    matches.forEach { userId -> MemberRow(serverId, userId) { onOpen(userId) } }
    Spacer(Modifier.size(8.dp))
}

@Composable
private fun MemberRow(serverId: String, userId: String, onClick: () -> Unit) {
    val name = memberName(serverId, userId)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        UserAvatar(
            username = name,
            userId = userId,
            avatar = StoatAPI.members.getMember(serverId, userId)?.avatar ?: StoatAPI.userCache[userId]?.avatar,
            size = 32.dp,
        )
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            StoatAPI.userCache[userId]?.username?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
