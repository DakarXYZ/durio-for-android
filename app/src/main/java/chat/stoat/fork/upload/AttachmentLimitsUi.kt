package chat.stoat.fork.upload

import android.content.Context
import android.text.format.Formatter
import android.widget.Toast
import chat.stoat.internals.extensions.getStringBlocking
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.durio_attachment_too_large
import chat.stoat.shared.resources.durio_attachment_too_large_encrypted
import java.io.File

/**
 * The pickers' hook: a picked [file] over [AttachmentLimits.maxBytes] is deleted and not
 * attached, with a toast naming the limit. Returns whether it was refused.
 */
fun rejectTooLargeAttachment(context: Context, file: File, encrypted: Boolean): Boolean {
    if (!AttachmentLimits.tooLarge(file, encrypted)) return false
    file.delete()
    val max = Formatter.formatShortFileSize(context, AttachmentLimits.maxBytes(encrypted))
    // an encrypted chat may be held to less than the server allows (memory)
    val text = if (encrypted && AttachmentLimits.maxBytes(true) < AttachmentLimits.serverBytes()) {
        Res.string.durio_attachment_too_large_encrypted
    } else {
        Res.string.durio_attachment_too_large
    }
    Toast.makeText(context, getStringBlocking(text, max), Toast.LENGTH_LONG).show()
    return true
}
