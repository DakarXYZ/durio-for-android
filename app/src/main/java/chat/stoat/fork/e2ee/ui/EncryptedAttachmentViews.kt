package chat.stoat.fork.e2ee.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import chat.stoat.R
import chat.stoat.api.fork.e2ee.E2eeAttachmentKeys
import chat.stoat.api.fork.e2ee.E2eeAttachments
import chat.stoat.api.fork.e2ee.E2eeFileKey
import chat.stoat.api.settings.LoadedSettings
import chat.stoat.api.settings.SyncedSettings
import chat.stoat.composables.chat.FileAttachment
import chat.stoat.composables.chat.VideoPlayButton
import chat.stoat.core.model.schemas.AutumnResource
import chat.stoat.fork.e2ee.media.EncryptedFileDataSource
import chat.stoat.fork.e2ee.media.EncryptedMedia
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.attachment_spoiler
import chat.stoat.shared.resources.back
import chat.stoat.shared.resources.durio_e2ee_attachment_failed
import chat.stoat.shared.resources.durio_e2ee_attachment_gone
import chat.stoat.shared.resources.durio_e2ee_saved
import chat.stoat.shared.resources.media_viewer_play
import chat.stoat.shared.resources.media_viewer_save
import chat.stoat.ui.theme.StoatTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max

/**
 * An attachment of a decrypted message (plan AN-28), called first by `MessageAttachment`:
 * returns false for files that are not encrypted. Images and videos show their encrypted
 * thumbnail and open in [EncryptedMediaViewer]; audio plays in place; other files are saved on
 * tap (there is no link to open: the server only has ciphertext).
 */
@Composable
fun EncryptedMessageAttachment(attachment: AutumnResource, onOpen: (AutumnResource) -> Unit): Boolean {
    val keys = EncryptedMedia.keys(attachment.id) ?: return false
    val id = attachment.id ?: return false
    when (attachment.metadata?.type) {
        "Image", "Video" -> EncryptedVisual(id, keys, attachment, onOpen)
        "Audio" -> EncryptedAudioPlayer(id, keys)
        else -> {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            Box(Modifier.clip(MaterialTheme.shapes.medium).clickable { scope.launch { save(context, id) } }) {
                FileAttachment(attachment)
            }
        }
    }
    return true
}

@Composable
private fun EncryptedVisual(id: String, keys: E2eeAttachmentKeys, attachment: AutumnResource, onOpen: (AutumnResource) -> Unit) {
    val width = attachment.metadata?.width?.toFloat()
    val height = attachment.metadata?.height?.toFloat()
    val ratio = if (width != null && height != null && width > 0 && height > 0) width / height else 16f / 9f
    var spoilerShown by remember { mutableStateOf(!keys.spoiler) }
    // the thumbnail when there is one (small); a video without one shows the play button only
    val previewId = keys.thumbnailId ?: id.takeIf { attachment.metadata?.type == "Image" }
    val previewJson = if (keys.thumbnailId != null) keys.thumbnailFileJson else keys.fileJson

    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable { if (spoilerShown) onOpen(attachment) else spoilerShown = true }
    ) {
        val shownWidth = width?.dp?.coerceAtMost(maxWidth) ?: maxWidth
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(shownWidth)
                .aspectRatio(ratio)
                .background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            if (previewId != null && previewJson != null) {
                DecryptedBitmap(previewId, previewJson, maxSidePx = 1280) { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = keys.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (spoilerShown) Modifier else Modifier.blur(24.dp))
                    )
                }
            }
            if (!spoilerShown) {
                Text(stringResource(Res.string.attachment_spoiler), modifier = Modifier.padding(8.dp))
            } else if (attachment.metadata?.type == "Video") {
                VideoPlayButton()
            }
        }
    }
}

/** Decrypts an image blob and decodes it no larger than [maxSidePx]; the bitmap stays in memory only. */
@Composable
private fun DecryptedBitmap(id: String, fileJson: String, maxSidePx: Int, content: @Composable (Bitmap) -> Unit) {
    val cacheKey = "$id@$maxSidePx"
    var bitmap by remember(id) { mutableStateOf<Bitmap?>(EncryptedMedia.bitmaps.get(cacheKey)) }
    var failed by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) {
        if (bitmap != null) return@LaunchedEffect
        try {
            val bytes = EncryptedMedia.bytes(id, fileJson)
            bitmap = withContext(Dispatchers.Default) { decodeSampled(bytes, maxSidePx) }
            bitmap?.let { EncryptedMedia.bitmaps.put(cacheKey, it) }
            failed = bitmap == null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        }
    }
    when {
        bitmap != null -> content(bitmap!!)
        failed -> Icon(painterResource(R.drawable.ic_error_24dp), contentDescription = stringResource(Res.string.durio_e2ee_attachment_failed))
        else -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
    }
}

private fun decodeSampled(bytes: ByteArray, maxSidePx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSidePx) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** Audio of an encrypted message: played from the checked ciphertext, never a plaintext file. */
@OptIn(UnstableApi::class)
@Composable
private fun EncryptedAudioPlayer(id: String, keys: E2eeAttachmentKeys) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var player by remember(id) { mutableStateOf<ExoPlayer?>(null) }
    var playing by remember(id) { mutableStateOf(false) }
    var loading by remember(id) { mutableStateOf(false) }
    var progress by remember(id) { mutableFloatStateOf(0f) }

    DisposableEffect(id) { onDispose { player?.release() } }
    LaunchedEffect(player, playing) {
        val p = player ?: return@LaunchedEffect
        while (playing) {
            progress = if (p.duration > 0) p.currentPosition.toFloat() / p.duration else 0f
            kotlinx.coroutines.delay(200)
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(8.dp)
    ) {
        IconButton(onClick = {
            val current = player
            if (current != null) {
                if (current.isPlaying) current.pause() else current.play()
                return@IconButton
            }
            loading = true
            scope.launch {
                try {
                    player = mediaPlayer(context, id, keys).apply {
                        addListener(object : androidx.media3.common.Player.Listener {
                            override fun onIsPlayingChanged(isPlaying: Boolean) {
                                playing = isPlaying
                            }
                        })
                        play()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Toast.makeText(context, getString(Res.string.durio_e2ee_attachment_failed), Toast.LENGTH_SHORT).show()
                } finally {
                    loading = false
                }
            }
        }) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                Icon(
                    painter = painterResource(if (playing) R.drawable.ic_pause_24dp else R.drawable.ic_play_arrow_24dp__fill),
                    contentDescription = stringResource(Res.string.media_viewer_play)
                )
            }
        }
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(keys.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@OptIn(UnstableApi::class)
private suspend fun mediaPlayer(context: android.content.Context, id: String, keys: E2eeAttachmentKeys): ExoPlayer {
    val file = EncryptedMedia.verifiedCiphertext(context, id, keys.fileJson)
    val key = E2eeFileKey.parse(keys.fileJson)
    val source = ProgressiveMediaSource.Factory(EncryptedFileDataSource.Factory(file, key))
        .createMediaSource(MediaItem.fromUri(Uri.fromFile(file)))
    return ExoPlayer.Builder(context).build().apply {
        setMediaSource(source)
        prepare()
    }
}

private suspend fun save(context: android.content.Context, id: String) {
    val uri = try {
        EncryptedMedia.saveToDevice(context, id)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    val text = if (uri != null) Res.string.durio_e2ee_saved else Res.string.durio_e2ee_attachment_failed
    Toast.makeText(context, getString(text), Toast.LENGTH_SHORT).show()
}

/**
 * The image and video viewers for encrypted attachments (AN-28), shown instead of upstream's
 * `ImageViewActivity`/`VideoViewActivity` screens, which load and share by URL. No "share
 * link" (a link to ciphertext is useless); "save" writes the decrypted file on request.
 */
object EncryptedMediaViewer {
    /** Shows the viewer if [resource] is an encrypted attachment; false: the upstream screen. */
    fun show(activity: ComponentActivity, resource: AutumnResource): Boolean {
        val id = resource.id ?: return false
        // after process death the keys are gone, but the tag says it was encrypted: never its URL
        if (EncryptedMedia.keys(id) == null && resource.tag != E2eeAttachments.DECRYPTED_TAG) return false
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        activity.setContent {
            StoatTheme(
                requestedTheme = LoadedSettings.theme,
                requestedUserInterfaceFont = LoadedSettings.font,
                colourOverrides = SyncedSettings.android.colourOverrides
            ) {
                ViewerScreen(id, resource, onClose = activity::finish)
            }
        }
        return true
    }
}

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
private fun ViewerScreen(id: String, resource: AutumnResource, onClose: () -> Unit) {
    val keys = EncryptedMedia.keys(id)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(keys?.name ?: resource.filename.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_arrow_back_24dp), contentDescription = stringResource(Res.string.back))
                    }
                },
                actions = {
                    if (keys != null) IconButton(onClick = { scope.launch { save(context, id) } }) {
                        Icon(painterResource(R.drawable.ic_download_24dp), contentDescription = stringResource(Res.string.media_viewer_save))
                    }
                }
            )
        }
    ) { pv ->
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(pv)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            when {
                keys == null -> Text(stringResource(Res.string.durio_e2ee_attachment_gone), modifier = Modifier.padding(32.dp))
                keys.contentType.startsWith("video/") || resource.metadata?.type == "Video" -> VideoPlayer(id, keys)
                else -> DecryptedBitmap(id, keys.fileJson, maxSidePx = 4096) { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = keys.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .zoomable(rememberZoomableState())
                    )
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPlayer(id: String, keys: E2eeAttachmentKeys) {
    val context = LocalContext.current
    var player by remember(id) { mutableStateOf<ExoPlayer?>(null) }
    var failed by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) {
        try {
            player = mediaPlayer(context, id, keys).apply { playWhenReady = true }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        }
    }
    DisposableEffect(id) { onDispose { player?.release() } }
    when {
        failed -> Text(stringResource(Res.string.durio_e2ee_attachment_failed))
        player == null -> CircularProgressIndicator()
        else -> AndroidView(
            factory = { PlayerView(it).apply { this.player = player } },
            modifier = Modifier.fillMaxSize()
        )
    }
    Spacer(Modifier.size(0.dp))
}
