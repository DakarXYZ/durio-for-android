package chat.stoat.fork.upload

import chat.stoat.api.fork.http.ApiClient
import chat.stoat.api.fork.routes.fetchServerRoot
import chat.stoat.api.fork.server.ServerRootInfo
import chat.stoat.core.model.fork.ActiveServer
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * How large an attachment may be (the fork's server allows 100 MB): the server's own limit
 * (`GET /` → `features.limits.default.file_upload_size_limits.attachments`), and for an
 * encrypted chat at most [HEAP_SHARE]-th of this app's heap.
 *
 * Why the heap ceiling: an encrypted upload holds the whole plaintext and the whole ciphertext
 * as arrays (autumn takes one multipart body, the core encrypts chunk by chunk into one array),
 * so a file of N bytes needs 2N of heap at once; a recipient holds 2N too to show an encrypted
 * image (media, "save" and "share" decrypt from the ciphertext on disk). A quarter of the heap
 * per file keeps that peak at half of it, the rest for the UI and the image caches. A phone
 * with a 512 MiB heap sends the server's full 100 MB; one with 256 MiB sends up to 64 MiB.
 * Plain uploads have no such ceiling: they hold the file once and autumn checks the size.
 */
object AttachmentLimits {
    /** Upstream's limit, for a server that does not say. */
    const val FALLBACK_BYTES = 20_000_000L
    const val HEAP_SHARE = 4

    @Volatile
    private var known: Pair<String, Long>? = null

    /** The answer of `GET /` of [apiUrl] (the E2EE start asks for it anyway). */
    fun remember(apiUrl: String, info: ServerRootInfo) {
        known = apiUrl to (info.attachmentLimitBytes ?: FALLBACK_BYTES)
    }

    /** The active server's limit, asked once per server; [FALLBACK_BYTES] while unknown. */
    suspend fun load(): Long {
        val api = ActiveServer.currentOrNull?.api ?: return FALLBACK_BYTES
        known?.takeIf { it.first == api }?.let { return it.second }
        return try {
            remember(api, fetchServerRoot(ApiClient.Active))
            serverBytes()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FALLBACK_BYTES
        }
    }

    /** What is known of the active server's limit right now. */
    fun serverBytes(): Long {
        val api = ActiveServer.currentOrNull?.api
        return known?.takeIf { it.first == api }?.second ?: FALLBACK_BYTES
    }

    /** The largest file to send, in bytes. */
    fun maxBytes(encrypted: Boolean): Long = limit(serverBytes(), encrypted, Runtime.getRuntime().maxMemory())

    fun tooLarge(file: File, encrypted: Boolean): Boolean = file.length() > maxBytes(encrypted)

    internal fun limit(serverBytes: Long, encrypted: Boolean, heapBytes: Long): Long =
        if (encrypted) minOf(serverBytes, heapBytes / HEAP_SHARE) else serverBytes
}

/** The file is over [AttachmentLimits.maxBytes]: refused before it is read into memory. */
class AttachmentTooLargeException(val maxBytes: Long) : Exception("Attachment over $maxBytes bytes")
