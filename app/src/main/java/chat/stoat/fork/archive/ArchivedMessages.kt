package chat.stoat.fork.archive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.stoat.core.model.data.STOAT_FILES
import chat.stoat.core.model.fork.MessageImport
import chat.stoat.core.model.schemas.AutumnResource
import chat.stoat.core.model.schemas.Message
import chat.stoat.core.model.schemas.User
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.durio_archive_badge
import chat.stoat.shared.resources.durio_archive_badge_discord
import org.jetbrains.compose.resources.stringResource

/**
 * Messages imported from an archive (e.g. Discord). They are authored by a webhook standing in
 * for the original author: no user, profile or DM exists behind it.
 */
object ArchivedMessages {
    fun isImported(message: Message): Boolean = message.imported != null

    /** The webhook's avatar as a resource [chat.stoat.composables.generic.UserAvatar] shows. */
    fun webhookAvatar(message: Message): AutumnResource? =
        message.webhook?.avatar?.takeIf { it.isNotBlank() }?.let { AutumnResource(id = it, tag = "avatars") }

    fun webhookAvatarUrl(message: Message): String? =
        webhookAvatar(message)?.let { "$STOAT_FILES/avatars/${it.id}" }

    /** The stand-in user of a webhook's message, so it renders without a `/users` lookup. */
    fun webhookAuthorPlaceholder(message: Message): User? =
        message.author?.takeIf { message.webhook != null }?.let { User.getPlaceholder(it) }
}

/** A small «Archive» badge next to the author's name of an imported message. */
@Composable
fun ArchiveBadge(
    imported: MessageImport,
    modifier: Modifier = Modifier,
    colour: Color = Color.Unspecified,
    fontSize: TextUnit = 10.sp,
) {
    val text = if (imported.source == MessageImport.SOURCE_DISCORD) {
        stringResource(Res.string.durio_archive_badge_discord)
    } else {
        stringResource(Res.string.durio_archive_badge)
    }
    val tint = colour.takeOrElse { LocalContentColor.current }
    Text(
        text = text,
        color = tint,
        fontSize = fontSize,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = modifier
            .background(tint.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}
