package chat.stoat.api.fork.e2ee

/**
 * Scripted [E2eeCore] for host-contract tests (plan AN-21, AN-34): records every call in order;
 * each pass hands out what [passes] holds, a request stays outstanding until marked sent.
 */
class FakeE2eeCore(
    override val userId: String = "01USER",
    override val deviceId: String = "01DEVICE",
) : E2eeCore {
    val calls = mutableListOf<String>()

    /** Requests the core wants sent; removed when marked sent. */
    val outstanding = mutableListOf<E2eeRequest>()

    /** Requests `prepareSend` hands out per channel, until marked sent. */
    val toPrepare = mutableMapOf<String, MutableList<E2eeRequest>>()

    /** Mailbox pages: what each `receiveToDevice` returns, in order. */
    val pages = ArrayDeque<ToDeviceOutcome>()

    var wakeup: Long? = null
    var failAction: E2eeAction? = null
    var closed = false
    var refuseMembers = false

    override suspend fun setServerLimits(limits: E2eeLimits) {
        calls += "limits"
    }

    override suspend fun outgoingRequests(): List<E2eeRequest> {
        calls += "pass"
        return outstanding.toList()
    }

    override suspend fun markRequestSent(request: E2eeRequest, body: String) {
        calls += "sent ${request.id} $body"
        outstanding.remove(request)
        toPrepare.values.forEach { it.remove(request) }
    }

    override suspend fun markRequestFailed(request: E2eeRequest, status: Int) {
        calls += "failed ${request.id} $status"
        failAction?.let { throw E2eeException.ActionRequired(it) }
    }

    override suspend fun wakeupInMs(): Long? = wakeup

    override suspend fun receiveToDevice(body: String): ToDeviceOutcome {
        calls += "toDevice $body"
        return pages.removeFirstOrNull() ?: ToDeviceOutcome(0, emptyList(), emptyList(), emptyList())
    }

    override suspend fun markAckSent(ids: List<String>) {
        calls += "acked ${ids.size}"
    }

    override suspend fun receiveDeviceListUpdate(users: List<String>) {
        calls += "deviceList ${users.joinToString(",")}"
    }

    override suspend fun receiveReconnect() {
        calls += "reconnect"
    }

    override suspend fun receiveOtkCounts(body: String) {
        calls += "otk $body"
    }

    override suspend fun setChannelMembers(channelId: String, members: List<String>, settings: E2eeChannelSettings) {
        calls += "members $channelId ${members.joinToString(",")}"
        if (refuseMembers) throw E2eeException.Core("InvalidId")
    }

    override suspend fun prepareSend(channelId: String): List<E2eeRequest> {
        calls += "prepare $channelId"
        return toPrepare[channelId].orEmpty().toList()
    }

    override suspend fun undeliveredDevices(): List<E2eeUndeliveredDevice> = emptyList()

    /** What `decrypt` answers per message id; unknown ids wait for their key. */
    val decryptResults = mutableMapOf<String, E2eeDecryptResult>()
    val decrypted = mutableListOf<E2eeIncomingMessage>()
    var decryptThrows: E2eeException? = null

    override suspend fun decrypt(messages: List<E2eeIncomingMessage>): List<E2eeDecryptResult> {
        calls += "decrypt ${messages.joinToString(",") { it.messageId }}"
        decryptThrows?.let { throw it }
        decrypted += messages
        return messages.map {
            decryptResults[it.messageId] ?: E2eeDecryptResult.Failed(E2eeFailureKind.WaitingForKey, "MissingRoomKey")
        }
    }

    override fun close() {
        closed = true
    }
}

/** A clean decryption of [payloadJson]. */
fun decryptedResult(
    payloadJson: String,
    verification: E2eeVerification = E2eeVerification.Unverified,
    isReplay: Boolean = false,
    staleEdit: Boolean = false,
    replyMismatch: Boolean = false,
) = E2eeDecryptResult.Decrypted(
    payloadJson = payloadJson,
    senderUser = "01AUTHOR",
    senderDevice = "01AUTHORDEVICE",
    verification = verification,
    sessionId = "s1",
    messageIndex = 0,
    isReplay = isReplay,
    staleEdit = staleEdit,
    editMismatch = false,
    replyMismatch = replyMismatch,
    isClean = !isReplay && !staleEdit && !replyMismatch && verification != E2eeVerification.Mismatch,
)

fun request(id: String, kind: E2eeRequestKind = E2eeRequestKind.KeysUpload, method: String = "POST", path: String = "/e2ee/keys/upload") =
    E2eeRequest(id, kind, method, path, """{"r":"$id"}""")
