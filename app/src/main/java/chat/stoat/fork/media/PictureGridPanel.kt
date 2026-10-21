package chat.stoat.fork.media

import android.annotation.SuppressLint
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import chat.stoat.api.routes.microservices.january.asJanuaryProxyUrl
import chat.stoat.composables.generic.RemoteImage
import chat.stoat.core.model.data.STOAT_FILES

private val PANEL_MAX_WIDTH = 320.dp
private val CELL_GAP = 4.dp

/** Where a thumbnail of [picture] loads from. */
fun pictureUrl(picture: GridPicture): String? {
    picture.attachment?.let { return "$STOAT_FILES/attachments/${it.id}/${it.filename}" }
    return picture.embed?.url?.let { asJanuaryProxyUrl(it) }
}

/**
 * The panels of a message's picture grid: square, centre-cropped thumbnails. A tap opens the
 * picture; a long press acts on the message the picture belongs to.
 */
@Composable
fun PictureGridPanels(
    panels: List<List<GridPicture>>,
    onOpen: (GridPicture) -> Unit,
    onLongPress: (GridPicture) -> Unit,
) {
    panels.forEach { panel ->
        Spacer(modifier = Modifier.height(2.dp))
        PictureGridPanel(panel = panel, onOpen = onOpen, onLongPress = onLongPress)
        Spacer(modifier = Modifier.height(2.dp))
    }
}

@SuppressLint("UnusedBoxWithConstraintsScope")
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PictureGridPanel(
    panel: List<GridPicture>,
    onOpen: (GridPicture) -> Unit,
    onLongPress: (GridPicture) -> Unit,
) {
    val columns = PictureGrid.columns(panel.size)
    // a lone picture (the 13th of a run) keeps the size of a two-column cell
    val sizingColumns = maxOf(columns, 2)

    BoxWithConstraints {
        val panelWidth = minOf(maxWidth, PANEL_MAX_WIDTH)
        val cell = (panelWidth - CELL_GAP * (sizingColumns - 1)) / sizingColumns

        Column(verticalArrangement = Arrangement.spacedBy(CELL_GAP)) {
            panel.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(CELL_GAP)) {
                    row.forEach { picture ->
                        Box(
                            modifier = Modifier
                                .size(cell)
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .combinedClickable(
                                    onClick = { onOpen(picture) },
                                    onLongClick = { onLongPress(picture) }
                                )
                        ) {
                            pictureUrl(picture)?.let { url ->
                                RemoteImage(
                                    url = url,
                                    description = picture.attachment?.filename,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
