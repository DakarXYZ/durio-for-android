package chat.stoat.fork.e2ee.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import chat.stoat.api.fork.e2ee.E2eeAttachments
import chat.stoat.api.fork.e2ee.E2eePayloadAttachment
import chat.stoat.api.fork.e2ee.E2eePayloadThumbnail
import chat.stoat.api.fork.routes.uploadEncryptedAttachment
import chat.stoat.api.routes.microservices.autumn.FileArgs
import chat.stoat.fork.e2ee.E2eeUnavailableException
import chat.stoat.fork.upload.AttachmentLimits
import chat.stoat.fork.upload.AttachmentTooLargeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Files of an encrypted message (plan AN-28, C8, C9): everything the server would have read —
 * name, type, size, dimensions, the thumbnail — goes into the payload; autumn only gets
 * ciphertext. The thumbnail (images and videos, ≤ 320 px JPEG) is a blob of its own with its
 * own key; its id must be listed in the message's open `attachments` (E2eePayload.fileIds).
 *
 * GPS tags are removed from JPEGs before encryption: autumn does not strip metadata of
 * encrypted files (it cannot), so the client does.
 */
object OutgoingAttachments {
    const val THUMBNAIL_PX = 320
    private const val THUMBNAIL_QUALITY = 70

    /**
     * Encrypts and uploads [file]; [onProgress] gets bytes sent / total of the main blob.
     * @throws AttachmentTooLargeException over [AttachmentLimits.maxBytes], before it is read
     */
    suspend fun prepare(file: FileArgs, onProgress: (Long, Long) -> Unit = { _, _ -> }): E2eePayloadAttachment {
        val crypto = E2eeAttachments.crypto ?: throw E2eeUnavailableException()
        val max = AttachmentLimits.maxBytes(encrypted = true)
        if (file.file.length() > max) throw AttachmentTooLargeException(max)
        val (bytes, metadata, thumbnail) = withContext(Dispatchers.IO) {
            val source = withoutLocation(file)
            try {
                Triple(source.readBytes(), metadataOf(source, file.contentType), thumbnailOf(source, file.contentType))
            } finally {
                if (source != file.file) source.delete()
            }
        }

        val blob = withContext(Dispatchers.Default) { crypto.encrypt(bytes) }
        val id = uploadEncryptedAttachment(blob.ciphertext, onProgress)

        val thumb = thumbnail?.let { (jpeg, width, height) ->
            val encrypted = withContext(Dispatchers.Default) { crypto.encrypt(jpeg) }
            E2eePayloadThumbnail(
                id = uploadEncryptedAttachment(encrypted.ciphertext),
                width = width,
                height = height,
                file = encrypted.fileJson.toJsonObject(),
            )
        }

        return E2eePayloadAttachment(
            id = id,
            name = file.filename,
            contentType = file.contentType,
            size = bytes.size.toLong(),
            metadata = metadata,
            spoiler = file.spoiler,
            file = blob.fileJson.toJsonObject(),
            thumbnail = thumb,
        )
    }

    /** A copy of a JPEG without GPS tags; other files as they are. */
    private fun withoutLocation(file: FileArgs): File {
        if (!file.contentType.equals("image/jpeg", ignoreCase = true)) return file.file
        return try {
            val copy = File.createTempFile("e2ee-", ".jpg", file.file.parentFile)
            file.file.copyTo(copy, overwrite = true)
            val exif = ExifInterface(copy.path)
            GPS_TAGS.forEach { exif.setAttribute(it, null) }
            exif.saveAttributes()
            copy
        } catch (e: Exception) {
            // unreadable EXIF: the original, as upstream would upload it unencrypted
            file.file
        }
    }

    /** Stoat file metadata as autumn would compute it (`{"type":"Image","width",…}`). */
    internal fun metadataOf(file: File, contentType: String): JsonObject {
        val type = contentType.substringBefore(';').trim().lowercase()
        return when {
            type.startsWith("image/") -> {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, options)
                if (options.outWidth > 0 && options.outHeight > 0) {
                    val (w, h) = orientedSize(file, options.outWidth, options.outHeight)
                    sized("Image", w, h)
                } else {
                    plain("File")
                }
            }
            type.startsWith("video/") -> videoSize(file)?.let { (w, h) -> sized("Video", w, h) } ?: plain("File")
            type.startsWith("audio/") -> plain("Audio")
            type.startsWith("text/") -> plain("Text")
            else -> plain("File")
        }
    }

    private fun thumbnailOf(file: File, contentType: String): Triple<ByteArray, Int, Int>? {
        val type = contentType.lowercase()
        val bitmap = try {
            when {
                type.startsWith("image/") -> decodeSampled(file)
                type.startsWith("video/") -> MediaMetadataRetriever().run {
                    try {
                        setDataSource(file.path)
                        getFrameAtTime(0)
                    } finally {
                        release()
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        } ?: return null

        val scale = THUMBNAIL_PX.toFloat() / max(bitmap.width, bitmap.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1), (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
        } else {
            bitmap
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, out)
        return Triple(out.toByteArray(), scaled.width, scaled.height)
    }

    private fun decodeSampled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMBNAIL_PX) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun orientedSize(file: File, width: Int, height: Int): Pair<Int, Int> {
        val rotated = try {
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
                ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE -> true
                else -> false
            }
        } catch (e: Exception) {
            false
        }
        return if (rotated) height to width else width to height
    }

    private fun videoSize(file: File): Pair<Int, Int>? = try {
        MediaMetadataRetriever().run {
            try {
                setDataSource(file.path)
                val w = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                val h = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                val rotation = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (w == null || h == null) null else if (rotation % 180 != 0) h to w else w to h
            } finally {
                release()
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun sized(type: String, width: Int, height: Int) = buildJsonObject {
        put("type", type)
        put("width", width)
        put("height", height)
    }

    private fun plain(type: String) = buildJsonObject { put("type", type) }

    private fun String.toJsonObject() = chat.stoat.api.StoatJson.parseToJsonElement(this).jsonObject

    private val GPS_TAGS = listOf(
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_DEST_LATITUDE,
        ExifInterface.TAG_GPS_DEST_LONGITUDE,
        ExifInterface.TAG_GPS_SPEED,
        ExifInterface.TAG_GPS_IMG_DIRECTION,
    )
}
