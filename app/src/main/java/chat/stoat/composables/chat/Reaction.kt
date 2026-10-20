package chat.stoat.composables.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.stoat.api.StoatAPI
import chat.stoat.api.internals.isUlid
import chat.stoat.composables.generic.RemoteImage
import chat.stoat.core.model.data.STOAT_FILES

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Reaction(
    emoji: String,
    members: List<String>,
    onClick: (Boolean) -> Unit,
    onLongClick: () -> Unit
) {
    val hasOwn = members.contains(StoatAPI.selfId)

    // durio: quieter than upstream's filled pills, the message stays in front: a faint tint,
    // own reactions outlined in primary
    val background by animateColorAsState(
        targetValue = if (hasOwn) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
        },
        label = "Reaction background"
    )
    val border by animateColorAsState(
        targetValue = if (hasOwn) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        } else {
            Color.Transparent
        },
        label = "Reaction border"
    )
    val foreground by animateColorAsState(
        targetValue = if (hasOwn) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "Reaction foreground"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(background)
            .border(1.dp, border, MaterialTheme.shapes.small)
            .combinedClickable(
                onClick = { onClick(hasOwn) },
                onLongClick = onLongClick,
            )
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        CompositionLocalProvider(LocalContentColor provides foreground) {
            if (emoji.isUlid()) {
                RemoteImage(
                    url = "$STOAT_FILES/emojis/${emoji}",
                    description = null,
                    modifier = Modifier.size(16.dp)
                )
            } else {
                Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = emoji,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            platformStyle = PlatformTextStyle(
                                includeFontPadding = false
                            )
                        ),
                        modifier = Modifier
                            .size(16.dp)
                    )
                }
            }

            Spacer(Modifier.width(6.dp))

            members.size.let { number ->
                number.toString()
                    .mapIndexed { index, c ->
                        ReactionDigit(
                            digitChar = c,
                            fullNumber = number,
                            place = index
                        )
                    }
                    .forEach {
                        AnimatedContent(
                            targetState = it,
                            transitionSpec = {
                                if (targetState > initialState) {
                                    slideInVertically { -it } togetherWith slideOutVertically { it }
                                } else {
                                    slideInVertically { it } togetherWith slideOutVertically { -it }
                                }
                            },
                            label = "Reaction count",
                        ) { target ->
                            Text(
                                text = target.digitChar.toString(),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                    fontFeatureSettings = "tnum"
                                )
                            )
                        }
                    }
            }
        }
    }
}

data class ReactionDigit(val digitChar: Char, val fullNumber: Int, val place: Int) {
    override fun equals(other: Any?): Boolean {
        return when (other) {
            is ReactionDigit -> digitChar == other.digitChar
            else -> super.equals(other)
        }
    }

    override fun hashCode(): Int {
        var result = digitChar.hashCode()
        result = 31 * result + fullNumber
        result = 31 * result + place
        return result
    }
}

operator fun ReactionDigit.compareTo(other: ReactionDigit): Int {
    return fullNumber.compareTo(other.fullNumber)
}