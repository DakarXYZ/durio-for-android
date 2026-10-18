package chat.stoat.api.fork.e2ee

import chat.stoat.core.model.schemas.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The text of an encrypted message for its push notification (plan AN-29), with the account's
 * core — the account may not be the active one. Within the FCM budget: decrypt the envelope of
 * the push (milliseconds); if its key is missing, one mailbox pass (the key usually travels with
 * the message) and decrypt again.
 *
 * A push carries the envelope but not the open `replies`, so a reply always looks re-bound;
 * with [Message.replies] unknown that one check is skipped — the text itself is still
 * authenticated, and the notification does not show the reply.
 */
class E2eePushDecryptor(private val core: E2eeCore, private val sync: E2eeSync) {
    sealed interface Outcome {
        /** [content] may be null for a message with only files ([attachments] of them). */
        class Text(val content: String?, val attachments: Int) : Outcome {
            override fun toString() = "Text($attachments attachments)"
        }

        /** The key did not come in time: show a generic text and try again later. */
        data object Waiting : Outcome

        /** Never to be shown as text (tampered, replayed, broken, newer format). */
        data object NotReadable : Outcome
    }

    suspend fun decrypt(message: Message, repliesKnown: Boolean, mailboxTimeoutMs: Long = MAILBOX_TIMEOUT_MS): Outcome {
        if (!message.isDecryptable()) return Outcome.NotReadable
        val incoming = message.toIncoming()
        var result = decryptOne(incoming)
        if (result is E2eeDecryptResult.Failed && result.kind == E2eeFailureKind.WaitingForKey) {
            withTimeoutOrNull(mailboxTimeoutMs) { sync.syncOnce() }
            result = decryptOne(incoming)
        }
        return when (result) {
            is E2eeDecryptResult.Failed -> when (result.kind) {
                E2eeFailureKind.WaitingForKey, E2eeFailureKind.RetryLater -> Outcome.Waiting
                else -> Outcome.NotReadable
            }
            is E2eeDecryptResult.Decrypted -> {
                val onlyReplies = !result.isReplay && !result.staleEdit && !result.editMismatch &&
                    result.verification != E2eeVerification.Mismatch
                if (!result.isClean && !(onlyReplies && !repliesKnown)) return Outcome.NotReadable
                try {
                    val payload = E2eePayload.parse(result.payloadJson)
                    Outcome.Text(payload.content, payload.attachments.size)
                } catch (e: IllegalArgumentException) {
                    Outcome.NotReadable
                }
            }
        }
    }

    private suspend fun decryptOne(incoming: E2eeIncomingMessage): E2eeDecryptResult = try {
        core.decrypt(listOf(incoming)).single()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        E2eeDecryptResult.Failed(E2eeFailureKind.RetryLater, "Exception")
    }

    companion object {
        const val MAILBOX_TIMEOUT_MS = 5_000L
    }
}
