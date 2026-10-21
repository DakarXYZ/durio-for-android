package chat.stoat.fork.polls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import chat.stoat.R
import chat.stoat.api.fork.routes.PollDraft
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.durio_poll_add_answer
import chat.stoat.shared.resources.durio_poll_allow_change
import chat.stoat.shared.resources.durio_poll_answer
import chat.stoat.shared.resources.durio_poll_create_title
import chat.stoat.shared.resources.durio_poll_hint
import chat.stoat.shared.resources.durio_poll_multiple
import chat.stoat.shared.resources.durio_poll_question
import chat.stoat.shared.resources.durio_poll_remove_answer
import chat.stoat.shared.resources.durio_poll_send
import org.jetbrains.compose.resources.stringResource

/**
 * A new poll: the question, 2–10 answers, one or several picks, votes that can be changed or not. [onSend] gets a draft that the
 * server takes (trimmed, blank answers left out); [busy] while it is on its way.
 */
@Composable
fun CreatePollSheet(busy: Boolean, onSend: (PollDraft) -> Unit) {
    var question by remember { mutableStateOf("") }
    val answers = remember { mutableStateListOf("", "") }
    var multiple by remember { mutableStateOf(false) }
    var allowChange by remember { mutableStateOf(true) }
    val draft = PollDraft(question, answers.toList(), multiple, finalVotes = !allowChange).cleaned()

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .imePadding()
            .navigationBarsPadding()
            .padding(bottom = 16.dp),
    ) {
        Text(stringResource(Res.string.durio_poll_create_title), style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = question,
            onValueChange = { question = it.take(PollDraft.MAX_QUESTION) },
            label = { Text(stringResource(Res.string.durio_poll_question)) },
            modifier = Modifier.fillMaxWidth(),
        )

        answers.forEachIndexed { index, answer ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = answer,
                    onValueChange = { answers[index] = it.take(PollDraft.MAX_ANSWER) },
                    label = { Text(stringResource(Res.string.durio_poll_answer, index + 1)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                if (answers.size > PollDraft.MIN_ANSWERS) {
                    IconButton(onClick = { answers.removeAt(index) }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close_24dp),
                            contentDescription = stringResource(Res.string.durio_poll_remove_answer),
                        )
                    }
                }
            }
        }

        if (answers.size < PollDraft.MAX_ANSWERS) {
            TextButton(onClick = { answers.add("") }) {
                Text(stringResource(Res.string.durio_poll_add_answer))
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.durio_poll_multiple),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = multiple, onCheckedChange = { multiple = it })
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.durio_poll_allow_change),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = allowChange, onCheckedChange = { allowChange = it })
        }

        Text(
            stringResource(Res.string.durio_poll_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = { draft?.let(onSend) },
            enabled = draft != null && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.durio_poll_send))
        }
    }
}
