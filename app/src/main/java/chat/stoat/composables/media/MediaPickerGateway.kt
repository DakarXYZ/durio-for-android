package chat.stoat.composables.media

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.stoat.R
import chat.stoat.fork.attach.RecentImages
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.durio_attach_gallery
import chat.stoat.shared.resources.durio_attach_recent_permission
import chat.stoat.shared.resources.durio_poll
import chat.stoat.shared.resources.file_picker_chip_camera
import chat.stoat.shared.resources.file_picker_chip_documents
import chat.stoat.shared.resources.file_picker_chip_photo_picker
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/**
 * The attachment panel. durio: on the left the five newest photos of the device (a tap attaches
 * one, a second tap takes it off) and "Gallery"; on the right Files, Poll and Camera.
 *
 * @param pickedRecent picker identifiers (the photo's URI) already attached from here
 * @param onCreatePoll null where polls are not possible (encrypted chats)
 */
@Composable
fun MediaPickerGateway(
    onOpenPhotoPicker: () -> Unit,
    onOpenDocumentPicker: () -> Unit,
    onOpenCamera: () -> Unit,
    modifier: Modifier = Modifier,
    onPickRecent: (Uri) -> Unit = {},
    pickedRecent: Set<String> = emptySet(),
    onCreatePoll: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxSize()
            .padding(16.dp)
            .navigationBarsPadding(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxHeight()
                .weight(.64f)
        ) {
            RecentPhotos(onOpenPhotoPicker, onPickRecent, pickedRecent)
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxHeight()
                .weight(.36f)
        ) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                Tile(
                    icon = R.drawable.ic_attach_file_24dp,
                    label = stringResource(Res.string.file_picker_chip_documents),
                    onClick = onOpenDocumentPicker,
                    modifier = Modifier.border(
                        width = 1.dp,
                        brush = SolidColor(LocalContentColor.current),
                        shape = MaterialTheme.shapes.medium
                    ),
                )
            }

            if (onCreatePoll != null) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSecondaryContainer) {
                    Tile(
                        icon = R.drawable.ic_poll_24dp,
                        label = stringResource(Res.string.durio_poll),
                        onClick = onCreatePoll,
                        modifier = Modifier.background(MaterialTheme.colorScheme.secondaryContainer),
                    )
                }
            }

            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onTertiaryContainer) {
                Tile(
                    icon = R.drawable.ic_camera_24dp,
                    label = stringResource(Res.string.file_picker_chip_camera),
                    onClick = onOpenCamera,
                    modifier = Modifier.background(MaterialTheme.colorScheme.tertiaryContainer),
                )
            }
        }
    }
}

/** A button of the panel: icon and label, sharing the column's height with its siblings. */
@Composable
private fun ColumnScope.Tile(icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .clip(MaterialTheme.shapes.medium)
            .then(modifier)
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Icon(painterResource(icon), contentDescription = null)
        Text(
            label,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}

/** Five newest photos in two rows of three, the sixth cell opens the gallery. */
@Composable
private fun ColumnScope.RecentPhotos(
    onOpenGallery: () -> Unit,
    onPick: (Uri) -> Unit,
    picked: Set<String>,
) {
    val context = LocalContext.current
    var canRead by remember { mutableStateOf(RecentImages.canRead(context)) }
    var recent by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val askPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { canRead = RecentImages.canRead(context) }

    LaunchedEffect(canRead) {
        recent = if (canRead) withContext(Dispatchers.IO) { RecentImages.query(context) } else emptyList()
    }

    if (!canRead) {
        // no access yet: the gallery as before, and a way to show the newest photos here
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSecondaryContainer) {
            Tile(
                icon = R.drawable.ic_photo_library_24dp,
                label = stringResource(Res.string.file_picker_chip_photo_picker),
                onClick = onOpenGallery,
                modifier = Modifier.background(MaterialTheme.colorScheme.secondaryContainer),
            )
        }
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
            Tile(
                icon = R.drawable.ic_photo_library_24dp,
                label = stringResource(Res.string.durio_attach_recent_permission),
                onClick = { askPermission.launch(RecentImages.permissions()) },
                modifier = Modifier.border(
                    width = 1.dp,
                    brush = SolidColor(LocalContentColor.current),
                    shape = MaterialTheme.shapes.medium
                ),
            )
        }
        return
    }

    val cells: List<Uri?> = recent.take(5) + null // null: the gallery
    cells.chunked(3).forEach { row ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            row.forEach { uri ->
                if (uri == null) {
                    GalleryCell(onOpenGallery)
                } else {
                    PhotoCell(uri, picked = uri.toString() in picked, onClick = { onPick(uri) })
                }
            }
            // keep the cells of a short last row the same width
            repeat(3 - row.size) { Box(Modifier.weight(1f)) }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun RowScope.PhotoCell(uri: Uri, picked: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(
                if (picked) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
                else Modifier
            )
            .clickable(onClick = onClick)
    ) {
        GlideImage(
            model = uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (picked) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            ) {
                Icon(
                    painterResource(R.drawable.ic_check_24dp),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun RowScope.GalleryCell(onClick: () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSecondaryContainer) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .clickable(onClick = onClick)
                .padding(4.dp)
        ) {
            Icon(painterResource(R.drawable.ic_photo_library_24dp), contentDescription = null)
            Text(
                stringResource(Res.string.durio_attach_gallery),
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
