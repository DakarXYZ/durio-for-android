package chat.stoat.composables.markdown.prose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.elements.MarkdownText
import org.intellij.markdown.IElementType
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType

internal const val SPOILER_LINK_TAG_PREFIX = "spoiler:"

/** durio: a closed (!)NSFW(!) spoiler (also starts with [SPOILER_LINK_TAG_PREFIX]) */
internal const val NSFW_SPOILER_LINK_TAG_PREFIX = "spoiler:nsfw:"

@Composable
internal fun SpoilerMarkdownText(
    content: String,
    node: ASTNode,
    style: TextStyle,
    spoilerColor: Color,
    modifier: Modifier = Modifier,
    contentChildType: IElementType? = null,
    isHeading: Boolean = false,
) {
    val childNode = contentChildType?.run(node::findChildOfType) ?: node
    val settings = annotatorSettings()
    val styledText = buildAnnotatedString {
        pushStyle(style.toSpanStyle())
        buildMarkdownAnnotatedString(
            content = content,
            node = childNode,
            annotatorSettings = settings,
        )
        pop()
    }
    val hiddenSpoilers = styledText.getLinkAnnotations(0, styledText.length).filter { range ->
        val link = range.item as? LinkAnnotation.Clickable
        link?.tag?.startsWith(SPOILER_LINK_TAG_PREFIX) == true
    }
    var layoutResult by remember(styledText) { mutableStateOf<TextLayoutResult?>(null) }
    // durio: NSFW spoilers are red and say so
    val nsfwColor = MaterialTheme.colorScheme.error
    val nsfwLabelStyle = TextStyle(
        color = MaterialTheme.colorScheme.onError,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
    )
    val textMeasurer = rememberTextMeasurer()
    val semanticsModifier = if (isHeading) Modifier.semantics { heading() } else Modifier

    MarkdownText(
        content = styledText,
        node = node,
        style = style,
        sourceContent = content,
        onTextLayout = { result, _ -> layoutResult = result },
        modifier = modifier
            .then(semanticsModifier)
            .drawWithContent {
                drawContent()
                val layout = layoutResult ?: return@drawWithContent
                hiddenSpoilers.forEach { spoiler ->
                    if (spoiler.start < spoiler.end) {
                        val nsfw = (spoiler.item as LinkAnnotation.Clickable).tag
                            .startsWith(NSFW_SPOILER_LINK_TAG_PREFIX)
                        val path = layout.getPathForRange(spoiler.start, spoiler.end)
                        drawPath(path = path, color = if (nsfw) nsfwColor else spoilerColor)
                        if (nsfw) {
                            val bounds = path.getBounds()
                            val label = textMeasurer.measure("NSFW", nsfwLabelStyle)
                            if (label.size.width <= bounds.width) {
                                drawText(
                                    textLayoutResult = label,
                                    topLeft = Offset(
                                        bounds.left + (bounds.width - label.size.width) / 2f,
                                        bounds.top + (bounds.height - label.size.height) / 2f,
                                    ),
                                )
                            }
                        }
                    }
                }
            },
    )
}
