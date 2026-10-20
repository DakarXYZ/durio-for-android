package chat.stoat.fork.polls

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.stoat.R
import chat.stoat.api.StoatAPI
import chat.stoat.api.fork.routes.votePoll
import chat.stoat.core.model.fork.Poll
import chat.stoat.core.model.schemas.Message
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.durio_poll
import chat.stoat.shared.resources.durio_poll_closed
import chat.stoat.shared.resources.durio_poll_kind_multiple
import chat.stoat.shared.resources.durio_poll_no_votes
import chat.stoat.shared.resources.durio_poll_vote_failed
import chat.stoat.shared.resources.durio_poll_votes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * The poll of a message: the question, a row per answer with a bar of its share of the voters,
 * the own picks ticked. A tap votes (the server's update is what stays), a long press opens the
 * message's context sheet like the rest of the message.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PollCard(message: Message, poll: Poll, onLongPress: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selfId = StoatAPI.selfId

    // the picks just made, until the server's poll replaces it
    var pending by remember(poll) { mutableStateOf<Poll?>(null) }
    val shown = pending ?: poll
    val picks = PollLogic.picks(shown, selfId)
    val voters = PollLogic.voters(shown)
    val canVote = !shown.closed && selfId != null && message.channel != null && message.id != null

    fun vote(answerId: String) {
        val self = selfId ?: return
        val channelId = message.channel ?: return
        val messageId = message.id ?: return
        val next = PollLogic.nextPicks(shown, self, answerId)
        pending = PollLogic.withPicks(shown, self, next)
        scope.launch {
            try {
                votePoll(channelId, messageId, next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pending = null
                Toast.makeText(
                    context,
                    getString(Res.string.durio_poll_vote_failed, e.message ?: ""),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .padding(vertical = 4.dp)
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_poll_24dp),
                contentDescription = stringResource(Res.string.durio_poll),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = shown.question,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }

        shown.answers.forEach { answer ->
            val picked = answer.id in picks
            val percent = PollLogic.percent(shown, answer.id)
            val shape = RoundedCornerShape(8.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .clip(shape)
                    .border(
                        width = 1.dp,
                        color = if (picked) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        shape = shape,
                    )
                    .combinedClickable(
                        enabled = true,
                        onClick = { if (canVote) vote(answer.id) },
                        onLongClick = onLongPress,
                    ),
            ) {
                // the share of the voters, as a bar behind the text
                Box(Modifier.matchParentSize()) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(percent / 100f)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = if (picked) 0.22f else 0.10f)
                            )
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    if (picked) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check_24dp),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                    }
                    Text(
                        text = answer.text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (voters > 0) {
                        Text(
                            text = "$percent% · ${answer.votes.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        val footer = buildList {
            add(
                if (voters == 0) stringResource(Res.string.durio_poll_no_votes)
                else stringResource(Res.string.durio_poll_votes, voters)
            )
            if (shown.multiple) add(stringResource(Res.string.durio_poll_kind_multiple))
            if (shown.closed) add(stringResource(Res.string.durio_poll_closed))
        }
        Text(
            text = footer.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
