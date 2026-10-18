package chat.stoat.api.fork.e2ee

/**
 * An encrypted message as the client got it from the server (plan AN-25). [author], [replies]
 * and [timestampMs] are the server's open claims: the core checks them against the plaintext
 * (T8, CR-19).
 */
class E2eeIncomingMessage(
    val messageId: String,
    val channelId: String,
    val author: String,
    /** The message's `encrypted` object as JSON. */
    val envelopeJson: String,
    val replies: List<String>,
    val timestampMs: Long,
) {
    override fun toString() = "E2eeIncomingMessage($messageId in $channelId)"
}

/** Shield of a decrypted message (ADR 0001): who is proven to have sent it. */
enum class E2eeVerification {
    /** The sending device is verified (SAS, AN-31). */
    Verified,
    Unverified,

    /** The device is not (yet) known. */
    UnknownDevice,

    /** Key from the backup or forwarded: the author is not proven (Q22). */
    Unauthenticated,

    /** The device hint or the key owner does not match the author: suspicious. */
    Mismatch,
}

/** Why a message cannot be shown (the core's `DecryptFailureKind`). */
sealed interface E2eeFailureKind {
    /** "Waiting for the key…": decrypted again when the key arrives. */
    data object WaitingForKey : E2eeFailureKind

    /** The sender's device refused to share the key ([reason] is the core's code). */
    data class Withheld(val reason: String) : E2eeFailureKind

    /** A newer format: update the app. */
    data object UpdateApp : E2eeFailureKind

    /** Tampered with by the server: never shown as text. */
    data object Suspicious : E2eeFailureKind

    data object Broken : E2eeFailureKind

    /** Store trouble: try again later. */
    data object RetryLater : E2eeFailureKind
}

sealed interface E2eeDecryptResult {
    /**
     * [payloadJson] is the plaintext payload v1 (without padding): never log it, and never put
     * this object into a string — [toString] leaves the payload out on purpose.
     */
    class Decrypted(
        val payloadJson: String,
        val senderUser: String,
        val senderDevice: String?,
        val verification: E2eeVerification,
        val sessionId: String,
        val messageIndex: Long,
        /** Same (session, index) seen under another message id (CR-19). */
        val isReplay: Boolean,
        /** An older version of an edit than the one already shown. */
        val staleEdit: Boolean,
        /** `edit_of` does not name this message. */
        val editMismatch: Boolean,
        /** `in_reply_to` differs from the open `replies`. */
        val replyMismatch: Boolean,
        /** Nothing suspicious: show it as a normal message (the shield still applies). */
        val isClean: Boolean,
    ) : E2eeDecryptResult {
        override fun toString() = "Decrypted(session=$sessionId, index=$messageIndex, $verification, clean=$isClean)"
    }

    /** [code] is the core's stable error code (diagnostics). */
    data class Failed(val kind: E2eeFailureKind, val code: String) : E2eeDecryptResult
}
