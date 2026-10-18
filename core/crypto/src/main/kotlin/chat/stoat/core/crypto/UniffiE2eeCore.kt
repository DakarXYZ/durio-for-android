package chat.stoat.core.crypto

import chat.stoat.api.fork.e2ee.E2eeAction
import chat.stoat.api.fork.e2ee.E2eeChannelSettings
import chat.stoat.api.fork.e2ee.E2eeCore
import chat.stoat.api.fork.e2ee.E2eeDecryptResult
import chat.stoat.api.fork.e2ee.E2eeDevice
import chat.stoat.api.fork.e2ee.E2eeException
import chat.stoat.api.fork.e2ee.E2eeFailureKind
import chat.stoat.api.fork.e2ee.E2eeIncomingMessage
import chat.stoat.api.fork.e2ee.E2eeLimits
import chat.stoat.api.fork.e2ee.E2eeRequest
import chat.stoat.api.fork.e2ee.E2eeRequestKind
import chat.stoat.api.fork.e2ee.E2eeRoomKey
import chat.stoat.api.fork.e2ee.E2eeUndeliveredDevice
import chat.stoat.api.fork.e2ee.E2eeVerification
import chat.stoat.api.fork.e2ee.ToDeviceOutcome
import uniffi.stoat_crypto.ChannelEncryptionSettings
import uniffi.stoat_crypto.CryptoException
import uniffi.stoat_crypto.CryptoMachine
import uniffi.stoat_crypto.DecryptFailureKind
import uniffi.stoat_crypto.DecryptResult
import uniffi.stoat_crypto.DecryptedMessage
import uniffi.stoat_crypto.IncomingEncryptedMessage
import uniffi.stoat_crypto.OutgoingRequest
import uniffi.stoat_crypto.RequestKind
import uniffi.stoat_crypto.ServerLimits
import uniffi.stoat_crypto.Trust
import uniffi.stoat_crypto.Verification

/**
 * [E2eeCore] over the UniFFI [CryptoMachine] (plan AN-20). Only maps types and errors; the host
 * contract is driven by `E2eeSync`.
 *
 * Rules for this module (checked by scripts/check-crypto-hygiene.sh): no logging at all, and
 * generated data classes never reach a string — their `toString()` prints every field, keys and
 * plaintext included (`DecryptedMessage`, `StoreConfig`, `RecoverySetup`).
 */
internal class UniffiE2eeCore(private val machine: CryptoMachine) : E2eeCore {
    override val userId: String = machine.userId()
    override val deviceId: String = machine.deviceId()

    override suspend fun setServerLimits(limits: E2eeLimits) = core {
        machine.setServerLimits(
            ServerLimits(
                maxToDeviceBatch = limits.maxToDeviceBatch.toUInt(),
                maxRequestBytes = limits.maxRequestBytes.toUInt(),
            )
        )
    }

    override suspend fun outgoingRequests(): List<E2eeRequest> = core {
        machine.outgoingRequests().map { it.toE2ee() }
    }

    override suspend fun markRequestSent(request: E2eeRequest, body: String) = core {
        machine.markRequestSent(request.id, request.kind.toUniffi(), body)
    }

    override suspend fun markRequestFailed(request: E2eeRequest, status: Int) = core {
        machine.markRequestFailed(request.id, request.kind.toUniffi(), status.coerceIn(0, 0xFFFF).toUShort())
    }

    override suspend fun wakeupInMs(): Long? = core {
        machine.wakeupInMs()?.let { it.coerceAtMost(Long.MAX_VALUE.toULong()).toLong() }
    }

    override suspend fun receiveToDevice(body: String): ToDeviceOutcome = core {
        val result = machine.receiveToDevice(body)
        ToDeviceOutcome(
            fetched = result.fetched.toInt(),
            ackIds = result.ackIds,
            roomKeys = result.roomKeys.map { E2eeRoomKey(it.channelId, it.sessionId) },
            verifications = result.verifications,
        )
    }

    override suspend fun markAckSent(ids: List<String>) = core { machine.markAckSent(ids) }

    override suspend fun receiveDeviceListUpdate(users: List<String>) = core {
        machine.receiveDeviceListUpdate(users)
    }

    override suspend fun receiveReconnect() = core { machine.receiveReconnect() }

    override suspend fun receiveOtkCounts(body: String) = core { machine.receiveOtkCounts(body) }

    override suspend fun setChannelMembers(
        channelId: String,
        members: List<String>,
        settings: E2eeChannelSettings,
    ) = core {
        machine.setChannelMembers(
            channelId,
            members,
            ChannelEncryptionSettings(
                rotationPeriodMs = settings.rotationPeriodMs?.toULong(),
                rotationPeriodMsgs = settings.rotationPeriodMsgs?.toULong(),
            )
        )
    }

    override suspend fun prepareSend(channelId: String): List<E2eeRequest> = core {
        machine.prepareSend(channelId).map { it.toE2ee() }
    }

    override suspend fun undeliveredDevices(): List<E2eeUndeliveredDevice> = core {
        machine.undeliveredDevices().map {
            E2eeUndeliveredDevice(it.userId, it.deviceId, it.attempts.toInt(), it.gaveUp)
        }
    }

    override suspend fun decrypt(messages: List<E2eeIncomingMessage>): List<E2eeDecryptResult> = core {
        if (messages.isEmpty()) return@core emptyList()
        val incoming = messages.map {
            IncomingEncryptedMessage(
                messageId = it.messageId,
                channelId = it.channelId,
                author = it.author,
                envelopeJson = it.envelopeJson,
                replies = it.replies,
                timestamp = it.timestampMs.coerceAtLeast(0).toULong(),
            )
        }
        // the generated DecryptedMessage carries the plaintext in its toString(): mapped field
        // by field and dropped right here
        machine.decryptBatch(incoming).map { result ->
            when (result) {
                is DecryptResult.Decrypted -> result.message.toE2ee()
                is DecryptResult.Failed -> E2eeDecryptResult.Failed(result.kind.toE2ee(), result.code)
            }
        }
    }

    override suspend fun encrypt(channelId: String, payloadJson: String): String = core {
        machine.encrypt(channelId, payloadJson)
    }

    override suspend fun devices(userId: String): List<E2eeDevice> = core {
        machine.devices(userId).map {
            E2eeDevice(
                userId = it.userId,
                deviceId = it.deviceId,
                ed25519 = it.ed25519,
                verified = it.trust == Trust.VERIFIED,
                blocked = it.trust == Trust.BLOCKED,
                deleted = it.deleted,
            )
        }
    }

    override fun close() = machine.close()
}

private fun OutgoingRequest.toE2ee() = E2eeRequest(
    id = id,
    kind = kind.toE2ee(),
    method = method,
    path = path,
    body = body,
)

private fun DecryptedMessage.toE2ee() = E2eeDecryptResult.Decrypted(
    payloadJson = payloadJson,
    senderUser = senderUser,
    senderDevice = senderDevice,
    verification = when (verification) {
        Verification.VERIFIED -> E2eeVerification.Verified
        Verification.UNVERIFIED -> E2eeVerification.Unverified
        Verification.UNKNOWN_DEVICE -> E2eeVerification.UnknownDevice
        Verification.UNAUTHENTICATED -> E2eeVerification.Unauthenticated
        Verification.MISMATCH -> E2eeVerification.Mismatch
    },
    sessionId = sessionId,
    messageIndex = messageIndex.toLong(),
    isReplay = isReplay,
    staleEdit = staleEdit,
    editMismatch = editMismatch,
    replyMismatch = replyMismatch,
    isClean = isClean,
)

private fun DecryptFailureKind.toE2ee(): E2eeFailureKind = when (this) {
    DecryptFailureKind.WaitingForKey -> E2eeFailureKind.WaitingForKey
    is DecryptFailureKind.Withheld -> E2eeFailureKind.Withheld(reason.name)
    DecryptFailureKind.UpdateApp -> E2eeFailureKind.UpdateApp
    DecryptFailureKind.Suspicious -> E2eeFailureKind.Suspicious
    DecryptFailureKind.Broken -> E2eeFailureKind.Broken
    DecryptFailureKind.RetryLater -> E2eeFailureKind.RetryLater
}

private fun RequestKind.toE2ee() = when (this) {
    RequestKind.KEYS_UPLOAD -> E2eeRequestKind.KeysUpload
    RequestKind.KEYS_QUERY -> E2eeRequestKind.KeysQuery
    RequestKind.KEYS_CLAIM -> E2eeRequestKind.KeysClaim
    RequestKind.TO_DEVICE -> E2eeRequestKind.ToDevice
    RequestKind.BACKUP_VERSION_CREATE -> E2eeRequestKind.BackupVersionCreate
    RequestKind.BACKUP_KEYS -> E2eeRequestKind.BackupKeys
    RequestKind.SECRET_PUT -> E2eeRequestKind.SecretPut
}

private fun E2eeRequestKind.toUniffi() = when (this) {
    E2eeRequestKind.KeysUpload -> RequestKind.KEYS_UPLOAD
    E2eeRequestKind.KeysQuery -> RequestKind.KEYS_QUERY
    E2eeRequestKind.KeysClaim -> RequestKind.KEYS_CLAIM
    E2eeRequestKind.ToDevice -> RequestKind.TO_DEVICE
    E2eeRequestKind.BackupVersionCreate -> RequestKind.BACKUP_VERSION_CREATE
    E2eeRequestKind.BackupKeys -> RequestKind.BACKUP_KEYS
    E2eeRequestKind.SecretPut -> RequestKind.SECRET_PUT
}

/** Core errors → [E2eeException]; the cause is kept for its type, its message has no secrets. */
internal inline fun <T> core(block: () -> T): T = try {
    block()
} catch (e: CryptoException) {
    throw e.toE2ee()
}

internal fun CryptoException.toE2ee(): E2eeException = when (this) {
    is CryptoException.StoreLocked -> E2eeException.StoreLocked(this)
    is CryptoException.StoreMismatch, is CryptoException.Store -> E2eeException.StoreUnusable(this)
    is CryptoException.MfaRequired -> E2eeException.ActionRequired(E2eeAction.MfaRequired, this)
    is CryptoException.BackupVersionChanged ->
        E2eeException.ActionRequired(E2eeAction.BackupVersionChanged, this)
    is CryptoException.DeviceKeysConflict ->
        E2eeException.ActionRequired(E2eeAction.DeviceKeysConflict, this)
    is CryptoException.RoomKeyNotShared -> E2eeException.RoomKeyNotShared(this)
    is CryptoException.PayloadTooLarge -> E2eeException.PayloadTooLarge(this)
    is CryptoException.UnknownChannel -> E2eeException.UnknownChannel(this)
    is CryptoException.AttachmentHashMismatch -> E2eeException.AttachmentHashMismatch(this)
    else -> E2eeException.Core(this::class.simpleName ?: "Unknown", this)
}
