package chat.stoat.fork.e2ee

import chat.stoat.api.StoatAPI
import chat.stoat.api.fork.e2ee.DecryptionStatus
import chat.stoat.api.fork.e2ee.E2eeChannels
import chat.stoat.api.fork.e2ee.E2eeException
import chat.stoat.api.fork.e2ee.E2eeNoRecipientDevicesException
import chat.stoat.api.fork.e2ee.E2eePayloadAttachment
import chat.stoat.api.fork.e2ee.E2eeSendPreparationException
import chat.stoat.api.fork.e2ee.E2eeSender
import chat.stoat.api.fork.e2ee.E2eeState
import chat.stoat.api.fork.http.ApiClient
import chat.stoat.api.fork.routes.EncryptedSendException
import chat.stoat.api.fork.routes.editEncryptedMessage
import chat.stoat.api.fork.routes.sendEncryptedMessage
import chat.stoat.api.internals.ULID
import chat.stoat.api.routes.channel.SendMessageReply
import chat.stoat.core.model.schemas.Channel
import chat.stoat.fork.account.AccountManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.datetime.Clock
import logcat.LogPriority
import logcat.logcat

/** The account has no usable E2EE device: an encrypted message cannot be sent from here. */
class E2eeUnavailableException(cause: Throwable? = null) : Exception("E2EE is not available for this account", cause)

/** Why an encrypted message was not sent, for the user (AN-26). */
enum class E2eeSendError {
    /** The other side of the DM has no E2EE device: they need to update the app. */
    NoRecipientDevices,
    TooLong,

    /** This device has no E2EE (not set up, key lost, other server). */
    Unavailable,

    /** Keys could not be shared or the server did not answer: try again. */
    Network,

    /** The server refused (e.g. old plaintext message, attachment mismatch). */
    Refused,
}

/**
 * The send and edit path of encrypted DMs and groups, shared by the channel screen, the share
 * sheet and notification replies (plan AN-26): payload v1 → `E2eeSender` → the fork routes. The
 * caller decides with [isEncrypted]; plaintext never goes to an encrypted channel from here.
 *
 * Failures are thrown (the screen shows the message as failed) and also published on [errors]
 * with the reason the user can act on.
 */
object OutgoingMessages {
    private val _errors = MutableSharedFlow<E2eeSendError>(extraBufferCapacity = 8)
    val errors: SharedFlow<E2eeSendError> = _errors.asSharedFlow()

    fun isEncrypted(channelId: String?): Boolean = E2eeChannels.isEncrypted(channelId)

    /**
     * A new message of the active account (or of [accountKey] over [client], AN-43). [nonce]
     * is the request nonce and the payload's `client_nonce`; [fileIds] the open `attachments`
     * (blobs and thumbnails, C9) that belong to [attachments].
     *
     * Encrypted once: retries send the same envelope with the same nonce (block 5E), and
     * [resend] says an earlier run already sent this nonce (a retried notification reply).
     */
    suspend fun send(
        channel: Channel,
        content: String?,
        nonce: String,
        replies: List<SendMessageReply> = emptyList(),
        attachments: List<E2eePayloadAttachment> = emptyList(),
        idempotencyKey: String = nonce,
        resend: Boolean = false,
        accountKey: String? = AccountManager.active?.key,
        selfId: String? = StoatAPI.selfId,
        client: ApiClient = ApiClient.Active,
    ) = reported {
        val channelId = channel.id ?: error("Channel without id")
        val payload = E2eeSender.message(
            content = content,
            nonce = nonce,
            replies = replies.map { it.id },
            attachments = attachments,
            ts = Clock.System.now().toEpochMilliseconds(),
        )
        val envelope = E2eeManager.withSession(accountKey ?: throw E2eeUnavailableException()) { core, sync ->
            E2eeSender(core, sync).encrypt(
                channelId = channelId,
                members = E2eeChannels.members(channel),
                payload = payload,
                requireDevicesOf = E2eeChannels.partner(channel, selfId),
            )
        }
        sendEncryptedMessage(
            channelId = channelId,
            envelope = envelope,
            nonce = nonce,
            replies = replies,
            attachments = payload.fileIds(),
            idempotencyKey = idempotencyKey,
            resend = resend,
            selfId = selfId,
            client = client,
        )
    }

    /** An edit of an own encrypted message of the active account (CR-19: `edit_of`, `edit_seq` + 1). */
    suspend fun edit(channel: Channel, messageId: String, content: String) = reported {
        val channelId = channel.id ?: error("Channel without id")
        val shown = StoatAPI.messageCache[messageId]
        val previousSeq = (E2eeState.status[messageId] as? DecryptionStatus.Decrypted)?.editSeq
            ?: throw EncryptedSendException(400, "NotDecrypted") // an old plaintext message, or one not readable here
        val payload = E2eeSender.edit(
            messageId = messageId,
            content = content,
            nonce = ULID.makeNext(),
            previousEditSeq = previousSeq,
            replies = shown?.replies.orEmpty(),
            attachments = shown?.attachments.orEmpty().mapNotNull { a -> a.id?.let { E2eeState.attachmentKeys[it]?.attachment } },
            ts = Clock.System.now().toEpochMilliseconds(),
        )
        val envelope = E2eeManager.withSession(AccountManager.active?.key ?: throw E2eeUnavailableException()) { core, sync ->
            E2eeSender(core, sync).encrypt(channelId, E2eeChannels.members(channel), payload)
        }
        editEncryptedMessage(channelId, messageId, envelope)
    }

    private suspend fun <T> reported(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val reason = reason(e)
        logcat(LogPriority.WARN) { "Encrypted send failed: $reason (${e::class.simpleName})" }
        _errors.tryEmit(reason)
        throw e
    }

    internal fun reason(e: Exception): E2eeSendError = when (e) {
        is E2eeNoRecipientDevicesException -> E2eeSendError.NoRecipientDevices
        is E2eeException.PayloadTooLarge -> E2eeSendError.TooLong
        is E2eeUnavailableException, is E2eeException.StoreLocked -> E2eeSendError.Unavailable
        is E2eeSendPreparationException -> if (e.retryable) E2eeSendError.Network else E2eeSendError.Refused
        is EncryptedSendException -> when {
            e.status == 422 -> E2eeSendError.TooLong
            // still failing after the outbox's retries (block 5E): try again later
            e.status >= 500 || e.status == 429 -> E2eeSendError.Network
            else -> E2eeSendError.Refused
        }
        else -> E2eeSendError.Network
    }
}
