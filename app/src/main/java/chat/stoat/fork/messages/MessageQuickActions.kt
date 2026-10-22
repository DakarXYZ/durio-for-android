package chat.stoat.fork.messages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import chat.stoat.R
import chat.stoat.api.internals.isUlid
import chat.stoat.composables.generic.RemoteImage
import chat.stoat.core.model.data.STOAT_FILES
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.durio_emoji_pick
import chat.stoat.shared.resources.durio_message_quick_more
import chat.stoat.shared.resources.durio_message_quick_react
import chat.stoat.shared.resources.message_context_sheet_actions_copy
import chat.stoat.shared.resources.message_context_sheet_actions_reply
import org.jetbrains.compose.resources.stringResource

/**
 * The quick actions of a tapped message, as in Discord: the five emoji reacted with most recently,
 * copy, reply, and "…" for the full context sheet (which long press still opens too). A popup over
 * the top end of the message; under it when there is no room above.
 */
@Composable
fun MessageQuickActions(
    showReactions: Boolean,
    showCopy: Boolean,
    showReply: Boolean,
    onReact: (String) -> Unit,
    onCopy: () -> Unit,
    onReply: () -> Unit,
    onMore: () -> Unit,
    onDismiss: () -> Unit,
    // durio: the emoji picker, to react with any emoji
    onPickEmoji: (() -> Unit)? = null,
) {
    LaunchedEffect(Unit) { RecentEmoji.load() }
    val recent by RecentEmoji.emoji.collectAsState()
    // durio: one quick emoji fewer, the picker button fits
    val quick = remember(recent) { RecentEmoji.quick(recent, 4) }

    val margin = with(LocalDensity.current) { 8.dp.roundToPx() }
    val positionProvider = remember(margin) { AboveOrBelow(margin) }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                if (showReactions) {
                    quick.forEach { emoji -> QuickEmoji(emoji) { onReact(emoji) } }
                    onPickEmoji?.let {
                        QuickIcon(
                            R.drawable.ic_add_reaction_24dp,
                            stringResource(Res.string.durio_emoji_pick),
                            it,
                        )
                    }
                    VerticalDivider(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .height(24.dp)
                    )
                }
                if (showCopy) {
                    QuickIcon(
                        R.drawable.ic_content_copy_24dp,
                        stringResource(Res.string.message_context_sheet_actions_copy),
                        onCopy,
                    )
                }
                if (showReply) {
                    QuickIcon(
                        R.drawable.ic_reply_24dp,
                        stringResource(Res.string.message_context_sheet_actions_reply),
                        onReply,
                    )
                }
                QuickIcon(
                    R.drawable.ic_more_horiz_24dp,
                    stringResource(Res.string.durio_message_quick_more),
                    onMore,
                )
            }
        }
    }
}

@Composable
private fun QuickEmoji(emoji: String, onClick: () -> Unit) {
    val description = stringResource(Res.string.durio_message_quick_react, emoji)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        if (emoji.isUlid()) {
            RemoteImage(
                url = "$STOAT_FILES/emojis/$emoji",
                description = null,
                modifier = Modifier.size(24.dp)
            )
        } else {
            Text(
                text = emoji,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 22.sp,
                    textAlign = TextAlign.Center,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
            )
        }
    }
}

@Composable
private fun QuickIcon(icon: Int, description: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Over the top end of the message, or under it when the popup does not fit above. */
private class AboveOrBelow(private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = if (layoutDirection == LayoutDirection.Ltr) {
            anchorBounds.right - popupContentSize.width - margin
        } else {
            anchorBounds.left + margin
        }.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))

        val above = anchorBounds.top - popupContentSize.height / 2
        val y = if (above >= margin) {
            above
        } else {
            (anchorBounds.bottom - popupContentSize.height / 2)
                .coerceAtMost(windowSize.height - popupContentSize.height - margin)
        }
        return IntOffset(x, y.coerceAtLeast(0))
    }
}
