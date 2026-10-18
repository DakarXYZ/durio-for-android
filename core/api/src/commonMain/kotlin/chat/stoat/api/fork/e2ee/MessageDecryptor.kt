package chat.stoat.api.fork.e2ee

import chat.stoat.api.StoatJson
import chat.stoat.api.internals.ULID
import chat.stoat.core.model.fork.EncryptedEnvelope
import chat.stoat.core.model.schemas.AutumnResource
import chat.stoat.core.model.schemas.Message
import chat.stoat.core.model.schemas.Metadata
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one place encrypted messages are turned into displayable ones (plan AN-25, AN-46): the
 * socket, history pages, single messages, pins and replies all come through here.
 *
 * A decrypted message gets `content` and `attachments` from its payload (the real names and
 * metadata; thumbnails are not listed, C9) and its keys go to [E2eeState.attachmentKeys]. A
 * message that cannot be shown gets `content = null` and no attachments — the server's
 * placeholder never reaches the UI — and a [DecryptionStatus] that says why. Messages waiting for
 * their key are remembered by Megolm session and handed out again by [waitingFor].
 */
class MessageDecryptor(
    private val core: E2eeCore,
    private val state: E2eeState = E2eeState,
) {
    private val mutex = Mutex()

    // Megolm session id → messages waiting for its key; and store failures, retried on any key
    private val waiting = HashMap<String, MutableSet<String>>()
    private val retry = LinkedHashSet<String>()

    /**
     * Decrypts the encrypted ones of [messages] (results in input order; others pass through).
     * [previous] — the versions shown so far by id: a stale edit (the server replaying an older
     * version) keeps the previous one instead of turning it suspicious.
     */
    suspend fun decrypt(messages: List<Message>, previous: Map<String, Message> = emptyMap()): List<Message> {
        val targets = messages.indices.filter { messages[it].isDecryptable() }
        if (targets.isEmpty()) return messages

        val incoming = targets.map { messages[it].toIncoming() }
        val results = try {
            core.decrypt(incoming)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w { "E2EE decrypt failed: ${e::class.simpleName}" }
            incoming.map { E2eeDecryptResult.Failed(E2eeFailureKind.RetryLater, "Exception") }
        }

        val out = messages.toMutableList()
        mutex.withLock {
            targets.forEachIndexed { i, index ->
                val message = messages[index]
                out[index] = apply(message, results[i], previous[message.id])
            }
        }
        return out
    }

    /** Ids of the messages the new [keys] may unlock; they are forgotten here until decrypted again. */
    suspend fun waitingFor(keys: List<E2eeRoomKey>): Set<String> = mutex.withLock {
        val ids = LinkedHashSet<String>()
        for (key in keys) waiting.remove(key.sessionId)?.let { ids += it }
        ids += retry
        retry.clear()
        ids
    }

    private fun apply(message: Message, result: E2eeDecryptResult, previous: Message?): Message {
        val id = message.id!!
        forget(id)
        return when (result) {
            is E2eeDecryptResult.Decrypted -> decrypted(message, result, previous)
            is E2eeDecryptResult.Failed -> {
                val status = when (val kind = result.kind) {
                    E2eeFailureKind.WaitingForKey -> {
                        waiting.getOrPut(message.encrypted!!.sessionId) { LinkedHashSet() } += id
                        DecryptionStatus.Pending
                    }
                    E2eeFailureKind.RetryLater -> {
                        retry += id
                        DecryptionStatus.Pending
                    }
                    is E2eeFailureKind.Withheld -> DecryptionStatus.Failed(DecryptionStatus.Failed.Reason.Withheld)
                    E2eeFailureKind.UpdateApp -> DecryptionStatus.Failed(DecryptionStatus.Failed.Reason.UpdateApp)
                    E2eeFailureKind.Broken -> DecryptionStatus.Failed(DecryptionStatus.Failed.Reason.Broken)
                    E2eeFailureKind.Suspicious -> DecryptionStatus.Suspicious
                }
                hidden(message, status)
            }
        }
    }

    private fun decrypted(message: Message, result: E2eeDecryptResult.Decrypted, previous: Message?): Message {
        val id = message.id!!
        val onlyStale = result.staleEdit && !result.isReplay && !result.editMismatch && !result.replyMismatch &&
            result.verification != E2eeVerification.Mismatch
        if (onlyStale && previous != null && state.status[id] is DecryptionStatus.Decrypted) return previous
        if (!result.isClean) return hidden(message, DecryptionStatus.Suspicious)

        val payload = try {
            E2eePayload.parse(result.payloadJson)
        } catch (e: IllegalArgumentException) {
            return hidden(message, DecryptionStatus.Failed(DecryptionStatus.Failed.Reason.Broken))
        }

        val attachments = payload.attachments.map { a ->
            state.attachmentKeys[a.id] = E2eeAttachmentKeys(messageId = id, attachment = a)
            AutumnResource(
                id = a.id,
                tag = "attachments",
                // upstream marks spoilers by the file name (ChannelScreenViewModel, ImageAttachment)
                filename = if (a.spoiler) "$SPOILER_PREFIX${a.name}" else a.name,
                metadata = a.metadata(),
                contentType = a.contentType,
                size = a.size,
                messageID = id,
            )
        }
        state.status[id] = DecryptionStatus.Decrypted(result.verification, payload.editSeq)
        return message.copy(
            content = payload.content,
            attachments = attachments,
            embeds = null,
            // the server does not know who is mentioned in an encrypted message (C10, Q16)
            mentions = (message.mentions.orEmpty() + mentionedUsers(payload.content)).distinct()
                .takeIf { it.isNotEmpty() },
        )
    }

    private fun hidden(message: Message, status: DecryptionStatus): Message {
        state.status[message.id!!] = status
        return message.copy(content = null, attachments = null, embeds = null)
    }

    private fun forget(id: String) {
        waiting.values.forEach { it.remove(id) }
        waiting.entries.removeAll { it.value.isEmpty() }
        retry.remove(id)
    }

    companion object {
        const val SPOILER_PREFIX = "SPOILER_"

        private val MENTION = Regex("<@([0-9A-HJKMNP-TV-Z]{26})>")

        fun mentionedUsers(content: String?): List<String> =
            content?.let { c -> MENTION.findAll(c).map { it.groupValues[1] }.toList() }.orEmpty()
    }
}

/** An encrypted message the core can be asked about (the server always sends these fields). */
internal fun Message.isDecryptable() = encrypted != null && id != null && channel != null && author != null

internal fun Message.toIncoming() = E2eeIncomingMessage(
    messageId = id!!,
    channelId = channel!!,
    author = author!!,
    envelopeJson = StoatJson.encodeToString(EncryptedEnvelope.serializer(), encrypted!!),
    replies = replies.orEmpty(),
    timestampMs = try {
        ULID.asTimestamp(id!!)
    } catch (e: Exception) {
        0L
    },
)

private fun E2eePayloadAttachment.metadata(): Metadata? = try {
    StoatJson.decodeFromJsonElement(Metadata.serializer(), metadata)
} catch (e: IllegalArgumentException) {
    null
}
