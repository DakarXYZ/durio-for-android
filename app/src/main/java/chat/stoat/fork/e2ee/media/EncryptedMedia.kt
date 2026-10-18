package chat.stoat.fork.e2ee.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.os.Environment
import android.util.LruCache
import android.provider.MediaStore
import chat.stoat.api.fork.e2ee.E2eeAttachmentKeys
import chat.stoat.api.fork.e2ee.E2eeAttachments
import chat.stoat.api.fork.e2ee.E2eeState
import chat.stoat.api.fork.routes.downloadEncryptedAttachment
import chat.stoat.fork.e2ee.E2eeUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reading encrypted attachments (plan AN-28). Keys come from [E2eeState.attachmentKeys] by
 * autumn id — never from an Intent or a Bundle; after process death the message is decrypted
 * again before its files can be opened.
 *
 * Images and thumbnails are decrypted in memory (the hash is checked over the whole blob
 * first). Video and audio are downloaded to `cacheDir/e2ee/<id>.bin` (ciphertext, not
 * secret), checked once per process, and played through [EncryptedFileDataSource]. Glide is
 * not used for these images: its disk cache would hold the plaintext.
 */
object EncryptedMedia {
    private val mutex = Mutex()
    private val verified = HashSet<String>()

    fun keys(attachmentId: String?): E2eeAttachmentKeys? = attachmentId?.let { E2eeState.attachmentKeys[it] }

    /** Plaintext of the blob (or of its thumbnail). */
    suspend fun bytes(attachmentId: String, fileJson: String): ByteArray {
        val crypto = E2eeAttachments.crypto ?: throw E2eeUnavailableException()
        val ciphertext = downloadEncryptedAttachment(attachmentId)
        return withContext(Dispatchers.Default) { crypto.decrypt(ciphertext, fileJson) }
    }

    /** The checked ciphertext of a media attachment on disk, for [EncryptedFileDataSource]. */
    suspend fun verifiedCiphertext(context: Context, attachmentId: String, fileJson: String): File = mutex.withLock {
        val crypto = E2eeAttachments.crypto ?: throw E2eeUnavailableException()
        val file = File(File(context.cacheDir, CACHE_DIR).apply { mkdirs() }, "$attachmentId.bin")
        if (attachmentId in verified && file.isFile) return@withLock file
        withContext(Dispatchers.IO) {
            if (!file.isFile) {
                val partial = File(file.parentFile, "$attachmentId.part")
                partial.writeBytes(downloadEncryptedAttachment(attachmentId))
                partial.renameTo(file)
            }
            try {
                crypto.verifier(fileJson).use { verifier ->
                    file.inputStream().use { input ->
                        val buffer = ByteArray(CHUNK)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            verifier.update(if (n == buffer.size) buffer else buffer.copyOf(n))
                        }
                    }
                    verifier.finish()
                }
            } catch (e: Exception) {
                file.delete()
                throw e
            }
        }
        verified += attachmentId
        file
    }

    /**
     * "Save": the decrypted file to Pictures, Movies or Downloads — an explicit action of the
     * user (AN-28). Returns the new item's uri.
     */
    suspend fun saveToDevice(context: Context, attachmentId: String): Uri? {
        val keys = keys(attachmentId) ?: return null
        val plain = bytes(attachmentId, keys.fileJson)
        return withContext(Dispatchers.IO) {
            val type = keys.contentType
            val (collection, dir) = when {
                type.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_PICTURES
                type.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MOVIES
                type.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MUSIC
                else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_DOWNLOADS
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, keys.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, type)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/dur.io")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            ) ?: return@withContext null
            resolver.openOutputStream(uri)?.use { it.write(plain) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        }
    }

    /**
     * Decoded images and thumbnails, in memory only (1/16 of the heap): the list recycles its
     * items while scrolling, and Glide is not used for these because its disk cache would hold
     * the plaintext.
     */
    val bitmaps = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /** Logout or account switch: the downloaded ciphertext goes too. */
    suspend fun clearCache(context: Context) = mutex.withLock {
        bitmaps.evictAll()
        verified.clear()
        withContext(Dispatchers.IO) { File(context.cacheDir, CACHE_DIR).deleteRecursively() }
    }

    private const val CACHE_DIR = "e2ee"
    private const val CHUNK = 256 * 1024
}
