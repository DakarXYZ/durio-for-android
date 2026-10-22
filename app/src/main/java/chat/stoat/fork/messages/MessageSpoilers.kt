package chat.stoat.fork.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.stoat.core.model.fork.MessageSpoiler
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.durio_spoiler_alert
import chat.stoat.shared.resources.durio_spoiler_nsfw
import chat.stoat.shared.resources.durio_spoiler_tap
import org.jetbrains.compose.resources.stringResource

/**
 * A message a moderator hid behind a spoiler (dur.io servers): in place of its text, pictures,
 * files and poll, a cover saying "Spoiler Alert!" or "NSFW"; a tap shows the message.
 */
@Composable
fun SpoilerCover(spoiler: MessageSpoiler, onShow: () -> Unit) {
    val nsfw = spoiler.kind == MessageSpoiler.KIND_NSFW
    val background = if (nsfw) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val foreground = if (nsfw) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .padding(vertical = 4.dp)
            .widthIn(min = 200.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(background)
            .clickable(onClick = onShow)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text(
            stringResource(if (nsfw) Res.string.durio_spoiler_nsfw else Res.string.durio_spoiler_alert),
            color = foreground,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(Res.string.durio_spoiler_tap),
            color = foreground,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.alpha(0.8f),
        )
    }
}
