package chat.stoat.core.crypto

import chat.stoat.api.fork.e2ee.E2eeAction
import chat.stoat.api.fork.e2ee.E2eeChannelSettings
import chat.stoat.api.fork.e2ee.E2eeCore
import chat.stoat.api.fork.e2ee.E2eeException
import chat.stoat.api.fork.e2ee.E2eeLimits
import chat.stoat.api.fork.e2ee.E2eeRequest
import chat.stoat.api.fork.e2ee.E2eeRequestKind
import chat.stoat.api.fork.e2ee.E2eeRoomKey
import chat.stoat.api.fork.e2ee.E2eeUndeliveredDevice
import chat.stoat.api.fork.e2ee.ToDeviceOutcome
import uniffi.stoat_crypto.ChannelEncryptionSettings
import uniffi.stoat_crypto.CryptoException
import uniffi.stoat_crypto.CryptoMachine
import uniffi.stoat_crypto.OutgoingRequest
import uniffi.stoat_crypto.RequestKind
import uniffi.stoat_crypto.ServerLimits

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

    override fun close() = machine.close()
}

private fun OutgoingRequest.toE2ee() = E2eeRequest(
    id = id,
    kind = kind.toE2ee(),
    method = method,
    path = path,
    body = body,
)

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
    else -> E2eeException.Core(this::class.simpleName ?: "Unknown", this)
}
