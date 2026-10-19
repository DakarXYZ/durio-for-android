package chat.stoat.fork.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * The column of the login and sign-up screens. Upstream gave the form `weight(1f)` in a column of
 * the screen's height: with the keyboard open (the window resizes, `adjustResize`) the form and
 * the text field being typed in were squeezed, and nothing scrolled.
 *
 * Here the column is at least the screen's height and scrolls once its content is taller. Free
 * space goes to `Spacer(Modifier.weight(1f))`s: with an unbounded height the weights share the
 * minimum height, so the spacers shrink to nothing and the content keeps its size.
 */
@Composable
fun ScrollingScreen(
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .then(modifier),
            horizontalAlignment = horizontalAlignment,
            content = content,
        )
    }
}
