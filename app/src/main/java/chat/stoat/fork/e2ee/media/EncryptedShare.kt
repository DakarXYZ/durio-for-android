package chat.stoat.fork.e2ee.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import chat.stoat.activities.ShareTargetActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * "Share file" and "Forward" of an encrypted attachment (plan AN-44). A link would point at
 * ciphertext, so the decrypted file is shared: a copy in `cache/attachments/e2ee/<random>/`
 * (published by the FileProvider, `file_paths.xml`), deleted after [TTL_MS] — by a delayed job,
 * and by a sweep on every new share in case the job did not run. Forwarding hands the copy to
 * our own share target, which uploads it again (encrypted again for an encrypted chat): the
 * blob's id and keys are never reused.
 */
object EncryptedShare {
    const val TTL_MS = 10 * 60 * 1000L
    private const val DIR = "attachments/e2ee"
    private const val CLEANUP = "durio-e2ee-share-cleanup"

    /** Decrypts the attachment into a shareable copy; null without its keys. */
    suspend fun prepare(context: Context, attachmentId: String): Pair<Uri, String>? {
        val keys = EncryptedMedia.keys(attachmentId) ?: return null
        val file = withContext(Dispatchers.IO) {
            val root = File(context.cacheDir, DIR)
            sweep(root, System.currentTimeMillis())
            val dir = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
            File(dir, safeName(keys.name))
        }
        try {
            withContext(Dispatchers.IO) { file.outputStream() }.use { EncryptedMedia.decryptTo(context, attachmentId, keys.fileJson, it) }
        } catch (e: Exception) {
            file.parentFile?.deleteRecursively()
            throw e
        }
        scheduleCleanup(context, file.parentFile!!.name)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        return uri to keys.contentType.ifBlank { "application/octet-stream" }
    }

    /**
     * A text file of the app (the diagnostic report, CR-22) shared the same way: same directory,
     * same cleanup after [TTL_MS].
     */
    suspend fun prepareText(context: Context, name: String, text: String): Uri {
        val file = withContext(Dispatchers.IO) {
            val root = File(context.cacheDir, DIR)
            sweep(root, System.currentTimeMillis())
            val dir = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
            File(dir, safeName(name)).apply { writeText(text) }
        }
        scheduleCleanup(context, file.parentFile!!.name)
        return FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    }

    /** The system share sheet with the decrypted copy. */
    fun share(context: Context, uri: Uri, type: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            this.type = type
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Into another chat of this app: a new upload, encrypted again if that chat is. */
    fun forward(context: Context, uri: Uri, type: String) {
        context.startActivity(
            Intent(context, ShareTargetActivity::class.java).apply {
                action = Intent.ACTION_SEND
                this.type = type
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    /** Deletes the copies older than [ttlMs]; all of them with `ttlMs = 0` (logout). */
    fun sweep(root: File, now: Long, ttlMs: Long = TTL_MS) {
        root.listFiles()?.forEach { dir ->
            if (now - dir.lastModified() >= ttlMs) dir.deleteRecursively()
        }
    }

    fun clear(context: Context) = sweep(File(context.cacheDir, DIR), Long.MAX_VALUE, 0)

    /**
     * A file name from the sender's payload, safe on this device: no path, no control
     * characters, not empty, not too long.
     */
    fun safeName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
            .filter { it >= ' ' && it != '\u007f' && it !in "<>:\"|?*" }
            .trim()
            .trimStart('.')
        val limited = if (base.length <= 100) base else base.take(80) + base.takeLast(20)
        return limited.ifEmpty { "file" }
    }

    private fun scheduleCleanup(context: Context, dir: String) {
        val request = OneTimeWorkRequestBuilder<EncryptedShareCleanupWorker>()
            .setInputData(workDataOf(EncryptedShareCleanupWorker.DIR to dir))
            .setInitialDelay(TTL_MS, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("$CLEANUP-$dir", ExistingWorkPolicy.REPLACE, request)
    }

    internal fun root(context: Context) = File(context.cacheDir, DIR)
}

/** Deletes the shared decrypted copies once their time is up (AN-44). */
class EncryptedShareCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        withContext(Dispatchers.IO) {
            val root = EncryptedShare.root(applicationContext)
            // a random directory name, nothing else (never a path from the input)
            inputData.getString(DIR)?.takeIf { it.matches(Regex("[0-9a-f-]{36}")) }?.let { File(root, it).deleteRecursively() }
            EncryptedShare.sweep(root, System.currentTimeMillis())
        }
        return Result.success()
    }

    companion object {
        const val DIR = "dir"
    }
}
