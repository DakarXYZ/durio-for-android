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

    /** `markRequestFailed` drops the request for good (review O-2). */
    var rejectFailed = false
    var closed = false
    var refuseMembers = false

    /** Runs when a mailbox page comes in (a key arrives). */
    var onToDevice: (() -> Unit)? = null

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
        if (rejectFailed) {
            outstanding.remove(request)
            throw E2eeException.RequestRejected()
        }
    }

    override suspend fun wakeupInMs(): Long? = wakeup

    override suspend fun receiveToDevice(body: String): ToDeviceOutcome {
        calls += "toDevice $body"
        onToDevice?.invoke()
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

    /** Payloads given to `encrypt`; [notShared] times `RoomKeyNotShared` first. */
    val encrypted = mutableListOf<Pair<String, String>>()
    var notShared = 0
    val userDevices = mutableMapOf<String, List<E2eeDevice>>()

    override suspend fun encrypt(channelId: String, payloadJson: String): String {
        calls += "encrypt $channelId"
        if (notShared > 0) {
            notShared--
            throw E2eeException.RoomKeyNotShared()
        }
        encrypted += channelId to payloadJson
        return """{"v":1,"algorithm":"m.megolm.v1.aes-sha2","session_id":"s1","device_id":"$deviceId","ciphertext":"C${encrypted.size}","sender_key":"dropped"}"""
    }

    override suspend fun devices(userId: String): List<E2eeDevice> {
        calls += "devices $userId"
        return userDevices[userId].orEmpty()
    }

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

    var identity = E2eeIdentityKeys("curve", "ed25519key")
    val trust = mutableMapOf<String, E2eeTrust>()
    val flows = mutableMapOf<String, FakeSas>()

    override suspend fun identityKeys() = identity

    override suspend fun setDeviceTrust(userId: String, deviceId: String, trust: E2eeTrust) {
        calls += "trust $deviceId $trust"
        this.trust[deviceId] = trust
    }

    override suspend fun requestVerification(userId: String, deviceId: String): E2eeSas {
        calls += "verify $userId $deviceId"
        return FakeSas("flow-$deviceId", userId, deviceId, E2eeSasState.Requested).also { flows[it.flowId] = it }
    }

    override suspend fun verification(flowId: String): E2eeSas? = flows[flowId]

    override suspend fun verifications(): List<E2eeSas> = flows.values.filter { !it.current.isFinal }

    /** The backup as the fake server and core see it. */
    var recoveryKey = "EsTc LW2K PGiF wKEA 3As5 g5c4 BXwk qeeJ ZJV8 Q9fu gUMN UE4d"
    var backupVersion: String? = null
    var unlockedSecrets: E2eeRecoverySecrets? = null
    var restoreTotal = 3L

    override suspend fun createRecovery(passphrase: ByteArray?): E2eeRecoverySetup {
        calls += "createRecovery"
        return E2eeRecoverySetup(recoveryKey, request("bv1", E2eeRequestKind.BackupVersionCreate, "POST", "/e2ee/backup/version"))
    }

    override suspend fun rotateBackup(): E2eeRecoverySetup {
        calls += "rotateBackup"
        if (backupVersion == null) throw E2eeException.NoBackup()
        return E2eeRecoverySetup(null, request("bv2", E2eeRequestKind.BackupVersionCreate, "POST", "/e2ee/backup/version"))
    }

    override suspend fun unlockBackup(input: ByteArray, secrets: E2eeRecoverySecrets): String {
        calls += "unlock"
        unlockedSecrets = secrets
        if (input.decodeToString() != recoveryKey) throw E2eeException.WrongRecoveryKey()
        return "v1".also { backupVersion = it }
    }

    override suspend fun restoreBackup(keysBody: String): E2eeBackupRestore {
        calls += "restore $keysBody"
        if (backupVersion == null) throw E2eeException.NoBackup()
        return object : E2eeBackupRestore {
            var done = 0L
            override suspend fun progress() = E2eeRestoreProgress(done, restoreTotal, done, 0, done >= restoreTotal)
            override suspend fun next(max: Int): E2eeRestoreProgress {
                done = (done + 2).coerceAtMost(restoreTotal)
                return progress()
            }
            override fun close() {
                calls += "restore closed"
            }
        }
    }

    override suspend fun backupStatus() =
        E2eeBackupStatus(backupVersion, backupVersion != null, backupVersion != null, false, 10, 10)

    override suspend fun disableBackup() {
        backupVersion = null
    }

    override fun recoveryKeyCheck(input: ByteArray) =
        if (input.decodeToString() == recoveryKey) E2eeRecoveryKeyCheck.Valid else E2eeRecoveryKeyCheck.NotAKey

    /** Key files (CR-21): "exported" as the passphrase and channels; imports check the passphrase. */
    var exportFailure: E2eeException? = null
    var importKeys = listOf<E2eeRoomKey>()

    override suspend fun exportRoomKeys(passphrase: ByteArray, channelIds: List<String>): E2eeKeyExport {
        calls += "export ${channelIds.joinToString(",")}"
        exportFailure?.let { throw it }
        if (passphrase.isEmpty()) throw E2eeException.PassphraseRequired()
        return E2eeKeyExport("KEYS:" + passphrase.decodeToString(), 2)
    }

    override suspend fun importRoomKeys(file: String, passphrase: ByteArray): E2eeKeyImport {
        calls += "import"
        if (!file.startsWith("KEYS:")) throw E2eeException.InvalidKeyFile()
        if (file.removePrefix("KEYS:") != passphrase.decodeToString()) throw E2eeException.WrongPassphrase()
        return E2eeKeyImport(importKeys.size, importKeys.size, 0, importKeys)
    }

    var report = """{"format":1}"""

    override suspend fun debugReport() = report

    override fun close() {
        closed = true
    }
}

/** A scripted SAS flow: [current] is what [state] reports; actions are recorded. */
class FakeSas(
    override val flowId: String,
    override val otherUserId: String,
    private val device: String?,
    var current: E2eeSasState,
    override val isSelfVerification: Boolean = false,
) : E2eeSas {
    val actions = mutableListOf<String>()
    var closed = false

    override fun otherDeviceId() = device
    override fun state() = current
    override suspend fun accept() {
        actions += "accept"
    }
    override suspend fun start() {
        actions += "start"
    }
    override suspend fun confirm() {
        actions += "confirm"
    }
    override suspend fun mismatch() {
        actions += "mismatch"
    }
    override suspend fun cancel() {
        actions += "cancel"
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
