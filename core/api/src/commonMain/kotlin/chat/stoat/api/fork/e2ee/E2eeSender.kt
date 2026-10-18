package chat.stoat.api.fork.e2ee

import chat.stoat.api.StoatJson
import chat.stoat.core.model.fork.EncryptedEnvelope

/** Nobody on the other side of a DM can read encrypted messages yet: they have to update the app. */
class E2eeNoRecipientDevicesException(val userId: String) : Exception("Recipient has no E2EE device")

/**
 * Encrypts one outgoing message or edit for a DM or group (plan AN-26; host contract step 7):
 * `prepareSend` runs the key queries, the claim and the room-key share through the account's
 * [sync], then `encrypt` — once, before any HTTP, so a retried request never spends another
 * Megolm index. `RoomKeyNotShared` (a member's devices changed meanwhile) goes back to
 * `prepareSend`.
 *
 * [members] go into the core first: it refuses a channel it does not know yet (`UnknownChannel`),
 * and the socket's member update may not have arrived.
 */
class E2eeSender(private val core: E2eeCore, private val sync: E2eeSync) {
    /**
     * @param requireDevicesOf in a DM, the other user: without a single E2EE device the message
     *   would reach nobody, so it is not sent ([E2eeNoRecipientDevicesException])
     * @throws E2eeSendPreparationException keys could not be shared (network, server)
     * @throws E2eeException.PayloadTooLarge
     */
    suspend fun encrypt(
        channelId: String,
        members: List<String>,
        payload: E2eePayload,
        requireDevicesOf: String? = null,
    ): EncryptedEnvelope {
        val json = payload.toJson()
        repeat(MAX_ATTEMPTS) {
            sync.prepareSend(channelId, members)
            if (requireDevicesOf != null && core.devices(requireDevicesOf).none { !it.deleted }) {
                throw E2eeNoRecipientDevicesException(requireDevicesOf)
            }
            try {
                return StoatJson.decodeFromString(EncryptedEnvelope.serializer(), core.encrypt(channelId, json))
            } catch (e: E2eeException.RoomKeyNotShared) {
                // a member's device list changed between the share and the encrypt: share again
            }
        }
        throw E2eeSendPreparationException(retryable = true)
    }

    companion object {
        private const val MAX_ATTEMPTS = 3

        /**
         * Payload v1 of a new message: [nonce] is the `nonce` of the send request (CR-19),
         * [replies] its open `replies`.
         */
        fun message(
            content: String?,
            nonce: String,
            replies: List<String> = emptyList(),
            attachments: List<E2eePayloadAttachment> = emptyList(),
            ts: Long,
        ) = E2eePayload(
            content = content?.takeIf { it.isNotEmpty() },
            attachments = attachments,
            clientNonce = nonce,
            inReplyTo = replies,
            ts = ts,
        )

        /**
         * Payload of an edit of [messageId]: the next [E2eePayload.editSeq] after the version
         * shown, the same replies and attachments (the server keeps the open ones; CR-19).
         */
        fun edit(
            messageId: String,
            content: String,
            nonce: String,
            previousEditSeq: Long,
            replies: List<String>,
            attachments: List<E2eePayloadAttachment>,
            ts: Long,
        ) = E2eePayload(
            content = content,
            attachments = attachments,
            clientNonce = nonce,
            editOf = messageId,
            editSeq = previousEditSeq + 1,
            inReplyTo = replies,
            ts = ts,
        )
    }
}
