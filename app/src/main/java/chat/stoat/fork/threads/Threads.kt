package chat.stoat.fork.threads

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.stoat.R
import chat.stoat.api.StoatAPI
import chat.stoat.api.fork.routes.MAX_THREAD_NAME
import chat.stoat.api.fork.routes.cachedThreads
import chat.stoat.api.fork.routes.createThread
import chat.stoat.api.fork.routes.editThread
import chat.stoat.api.fork.routes.fetchThreads
import chat.stoat.api.fork.routes.isArchivedThread
import chat.stoat.api.fork.routes.threadNameFrom
import chat.stoat.api.internals.PermissionBit
import chat.stoat.api.internals.Roles
import chat.stoat.api.internals.ULID
import chat.stoat.api.internals.has
import chat.stoat.callbacks.Action
import chat.stoat.callbacks.ActionChannel
import chat.stoat.core.model.schemas.Channel
import chat.stoat.core.model.schemas.ChannelType
import chat.stoat.core.model.schemas.Message
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.durio_thread_active_section
import chat.stoat.shared.resources.durio_thread_archive
import chat.stoat.shared.resources.durio_thread_archived
import chat.stoat.shared.resources.durio_thread_archived_section
import chat.stoat.shared.resources.durio_thread_cancel
import chat.stoat.shared.resources.durio_thread_create_action
import chat.stoat.shared.resources.durio_thread_failed
import chat.stoat.shared.resources.durio_thread_messages
import chat.stoat.shared.resources.durio_thread_name
import chat.stoat.shared.resources.durio_thread_new
import chat.stoat.shared.resources.durio_thread_none
import chat.stoat.shared.resources.durio_thread_unarchive
import chat.stoat.shared.resources.durio_threads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Threads of server channels (dur.io servers): helpers for the UI. */
object Threads {
    /** Permission the user has in a channel */
    fun permission(channel: Channel): Long =
        Roles.permissionFor(channel, StoatAPI.userCache[StoatAPI.selfId])

    /** A thread can start in a server text channel that is not a thread itself */
    fun canStartIn(channel: Channel?): Boolean =
        channel != null &&
            channel.channelType == ChannelType.TextChannel &&
            channel.thread == null &&
            permission(channel) has PermissionBit.SendMessage

    /** Its starter, or ManageChannel, may archive a thread */
    fun canManage(thread: Channel): Boolean =
        thread.thread?.owner == StoatAPI.selfId || permission(thread) has PermissionBit.ManageChannel

    /** Time of the last activity of a thread, epoch ms */
    fun lastActivity(thread: Channel): Long? =
        (thread.lastMessageID ?: thread.id)?.let { runCatching { ULID.asTimestamp(it) }.getOrNull() }

    /** Open a thread (or any channel) as the current channel */
    suspend fun open(channelId: String) {
        ActionChannel.send(Action.SwitchChannel(channelId))
    }
}

/** "N messages · 2 hours ago · archived" for a thread */
@Composable
private fun threadMeta(thread: Channel): String {
    val count = (thread.thread?.messageCount ?: 0).toInt()
    val parts = mutableListOf(pluralStringResource(Res.plurals.durio_thread_messages, count, count))
    Threads.lastActivity(thread)?.let {
        parts += DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            .toString()
    }
    if (isArchivedThread(thread, System.currentTimeMillis())) {
        parts += stringResource(Res.string.durio_thread_archived)
    }
    return parts.joinToString(" · ")
}

/** Under a message: the thread started from it, which opens on a tap */
@Composable
fun ThreadChip(message: Message) {
    val threadId = message.thread ?: return
    val thread = StoatAPI.channelCache[threadId] ?: return
    val scope = rememberCoroutineScope()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .padding(top = 6.dp, bottom = 2.dp)
            .widthIn(max = 420.dp)
            .clip(MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .clickable { scope.launch { Threads.open(threadId) } }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_forum_24dp),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Column {
            Text(
                thread.name ?: "",
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                threadMeta(thread),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.alpha(0.7f),
            )
        }
    }
}

/** Name a new thread in [parentId] and start it, from [message] or on its own */
@Composable
fun CreateThreadDialog(parentId: String, message: Message?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(threadNameFrom(message?.content)) }
    var busy by remember { mutableStateOf(false) }
    val valid = name.trim().length in 1..MAX_THREAD_NAME

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(Res.string.durio_thread_new)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MAX_THREAD_NAME) },
                label = { Text(stringResource(Res.string.durio_thread_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                enabled = valid && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val thread = createThread(parentId, name, message?.id)
                            onDismiss()
                            thread.id?.let { Threads.open(it) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Toast.makeText(
                                context,
                                getString(Res.string.durio_thread_failed, e.message ?: ""),
                                Toast.LENGTH_LONG,
                            ).show()
                            busy = false
                        }
                    }
                },
            ) { Text(stringResource(Res.string.durio_thread_create_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(Res.string.durio_thread_cancel))
            }
        },
    )
}

/** The threads of a channel, active first, and a button to start one (in a bottom sheet) */
@Composable
fun ThreadsSheet(parentId: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    // threads archived on the server may not have reached this client yet
    LaunchedEffect(parentId) { runCatching { fetchThreads(parentId) } }

    val now = System.currentTimeMillis()
    val threads = cachedThreads(parentId)
    val active = threads.filterNot { isArchivedThread(it, now) }
    val archived = threads.filter { isArchivedThread(it, now) }

    if (creating) {
        CreateThreadDialog(parentId, null) {
            creating = false
            onDismiss()
        }
    }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                stringResource(Res.string.durio_threads),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (Threads.canStartIn(StoatAPI.channelCache[parentId])) {
                FilledTonalButton(onClick = { creating = true }) {
                    Text(stringResource(Res.string.durio_thread_new))
                }
            }
        }

        if (threads.isEmpty()) {
            Text(
                stringResource(Res.string.durio_thread_none),
                modifier = Modifier
                    .padding(16.dp)
                    .alpha(0.7f),
            )
        }

        if (active.isNotEmpty()) SectionTitle(stringResource(Res.string.durio_thread_active_section))
        active.forEach { thread ->
            ThreadRow(thread) {
                scope.launch {
                    onDismiss()
                    thread.id?.let { Threads.open(it) }
                }
            }
        }
        if (archived.isNotEmpty()) SectionTitle(stringResource(Res.string.durio_thread_archived_section))
        archived.forEach { thread ->
            ThreadRow(thread) {
                scope.launch {
                    onDismiss()
                    thread.id?.let { Threads.open(it) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
            .alpha(0.7f),
    )
}

@Composable
private fun ThreadRow(thread: Channel, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Icon(painter = painterResource(R.drawable.ic_forum_24dp), contentDescription = null)
        Column {
            Text(
                thread.name ?: "",
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(threadMeta(thread), style = MaterialTheme.typography.labelSmall, modifier = Modifier.alpha(0.7f))
        }
    }
}

/** Top bar action of a thread: archive it, or bring it back */
@Composable
fun ThreadArchiveAction(thread: Channel) {
    val info = thread.thread ?: return
    if (!Threads.canManage(thread)) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    androidx.compose.material3.IconButton(onClick = {
        scope.launch {
            try {
                editThread(thread.id ?: return@launch, archived = !info.archived)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "", Toast.LENGTH_SHORT).show()
            }
        }
    }) {
        Icon(
            painter = painterResource(if (info.archived) R.drawable.ic_unarchive_24dp else R.drawable.ic_archive_24dp),
            contentDescription = stringResource(
                if (info.archived) Res.string.durio_thread_unarchive else Res.string.durio_thread_archive,
            ),
        )
    }
}
