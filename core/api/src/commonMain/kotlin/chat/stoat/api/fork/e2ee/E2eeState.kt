package chat.stoat.api.fork.e2ee

import androidx.compose.runtime.mutableStateMapOf

/** What the UI shows for an encrypted message instead of the server's placeholder (AN-25, AN-27). */
sealed interface DecryptionStatus {
    /** Shown as text; [editSeq] of the version shown (the next edit sends +1, CR-19). */
    data class Decrypted(val verification: E2eeVerification, val editSeq: Long) : DecryptionStatus

    /** "Waiting for the key…": decrypted again when it arrives. */
    data object Pending : DecryptionStatus

    /** "Could not decrypt". */
    data class Failed(val reason: Reason) : DecryptionStatus {
        enum class Reason {
            /** This account has no E2EE device (server without E2EE, device not ready). */
            NoDevice,

            /** The sender's device refused the key. */
            Withheld,

            /** A newer format. */
            UpdateApp,
            Broken,
        }
    }

    /** Tampered with or replayed by the server: never shown as text (T8, T16, T17). */
    data object Suspicious : DecryptionStatus
}

/**
 * Keys of one decrypted attachment (C8): `EncryptedFile` JSON of the blob and of its thumbnail.
 * Secret: in memory only, never in an Intent, a Bundle or on disk (AN-28); after process death
 * the message is decrypted again.
 */
class E2eeAttachmentKeys(
    val messageId: String,
    val name: String,
    val contentType: String,
    val size: Long,
    val fileJson: String,
    val thumbnailId: String?,
    val thumbnailFileJson: String?,
    val spoiler: Boolean,
) {
    override fun toString() = "E2eeAttachmentKeys($messageId)"
}

/**
 * Client-side E2EE state of the active account's messages, next to `StoatAPI.messageCache`
 * (plan AN-25): not in `Message` itself, which is an upstream data class. Snapshot state, so
 * Compose reads it directly; written from the socket thread like the message cache.
 */
object E2eeState {
    /** By message id; no entry for messages that are not encrypted. */
    val status = mutableStateMapOf<String, DecryptionStatus>()

    /** By autumn id of the blob. */
    val attachmentKeys = mutableStateMapOf<String, E2eeAttachmentKeys>()

    /** Account switch or logout: nothing of the old account stays. */
    fun clear() {
        status.clear()
        attachmentKeys.clear()
    }
}
