package chat.stoat.core.crypto

import chat.stoat.api.fork.e2ee.E2eeAction
import chat.stoat.api.fork.e2ee.E2eeBackupRestore
import chat.stoat.api.fork.e2ee.E2eeBackupStatus
import chat.stoat.api.fork.e2ee.E2eeCancelReason
import chat.stoat.api.fork.e2ee.E2eeIdentityKeys
import chat.stoat.api.fork.e2ee.E2eeRecoveryKeyCheck
import chat.stoat.api.fork.e2ee.E2eeRecoverySecrets
import chat.stoat.api.fork.e2ee.E2eeRecoverySetup
import chat.stoat.api.fork.e2ee.E2eeRestoreProgress
import chat.stoat.api.fork.e2ee.E2eeSas
import chat.stoat.api.fork.e2ee.E2eeSasEmoji
import chat.stoat.api.fork.e2ee.E2eeSasState
import chat.stoat.api.fork.e2ee.E2eeTrust
import chat.stoat.api.fork.e2ee.E2eeChannelSettings
import chat.stoat.api.fork.e2ee.E2eeCore
import chat.stoat.api.fork.e2ee.E2eeDecryptResult
import chat.stoat.api.fork.e2ee.E2eeDevice
import chat.stoat.api.fork.e2ee.E2eeException
import chat.stoat.api.fork.e2ee.E2eeFailureKind
import chat.stoat.api.fork.e2ee.E2eeIncomingMessage
import chat.stoat.api.fork.e2ee.E2eeKeyExport
import chat.stoat.api.fork.e2ee.E2eeKeyImport
import chat.stoat.api.fork.e2ee.E2eeLimits
import chat.stoat.api.fork.e2ee.E2eeRequest
import chat.stoat.api.fork.e2ee.E2eeRequestKind
import chat.stoat.api.fork.e2ee.E2eeRoomKey
import chat.stoat.api.fork.e2ee.E2eeUndeliveredDevice
import chat.stoat.api.fork.e2ee.E2eeVerification
import chat.stoat.api.fork.e2ee.ToDeviceOutcome
import uniffi.stoat_crypto.BackupRestore
import uniffi.stoat_crypto.CancelReason
import uniffi.stoat_crypto.ChannelEncryptionSettings
import uniffi.stoat_crypto.CryptoException
import uniffi.stoat_crypto.CryptoMachine
import uniffi.stoat_crypto.DecryptFailureKind
import uniffi.stoat_crypto.DecryptResult
import uniffi.stoat_crypto.DecryptedMessage
import uniffi.stoat_crypto.IncomingEncryptedMessage
import uniffi.stoat_crypto.OutgoingRequest
import uniffi.stoat_crypto.RecoveryKeyCheck
import uniffi.stoat_crypto.RecoverySecrets
import uniffi.stoat_crypto.RestoreProgress
import uniffi.stoat_crypto.RequestKind
import uniffi.stoat_crypto.SasHandle
import uniffi.stoat_crypto.SasState
import uniffi.stoat_crypto.ServerLimits
import uniffi.stoat_crypto.Trust
import uniffi.stoat_crypto.Verification
import uniffi.stoat_crypto.recoveryKeyCheck as checkRecoveryKey

/**
 * [E2eeCore] over the UniFFI [CryptoMachine] (plan AN-20). Only maps types and errors; the host
 * contract is driven by `E2eeSync`.
 *
 * Rules for this module (checked by scripts/check-crypto-hygiene.sh): no logging at all, and
 * generated data classes never reach a string — their `toString()` prints every field, keys and
 * plaintext included (`DecryptedMessage`, `DecryptResult`, `StoreConfig`, `RecoverySetup`,
 * `EncryptedAttachment`, `KeyExport`).
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

    override suspend fun identityKeys(): E2eeIdentityKeys = core {
        val keys = machine.identityKeys()
        E2eeIdentityKeys(curve25519 = keys.curve25519, ed25519 = keys.ed25519)
    }

    // Review O-3: the core accepts VERIFIED here (its tests need it); the app never passes it —
    // E2eeTrust has no such value, and scripts/check-crypto-hygiene.sh keeps Trust.VERIFIED
    // to comparisons in this module. Verified comes from SAS Done only.
    override suspend fun setDeviceTrust(userId: String, deviceId: String, trust: E2eeTrust) = core {
        machine.setDeviceTrust(
            userId,
            deviceId,
            when (trust) {
                E2eeTrust.Unverified -> Trust.UNVERIFIED
                E2eeTrust.Blocked -> Trust.BLOCKED
            }
        )
    }

    override suspend fun requestVerification(userId: String, deviceId: String): E2eeSas = core {
        UniffiSas(machine.requestVerification(userId, deviceId))
    }

    override suspend fun verification(flowId: String): E2eeSas? = core {
        machine.verification(flowId)?.let(::UniffiSas)
    }

    override suspend fun verifications(): List<E2eeSas> = core {
        machine.verifications().map(::UniffiSas)
    }

    // RecoverySetup (generated) prints the recovery key in its toString(): mapped field by field
    override suspend fun createRecovery(passphrase: ByteArray?): E2eeRecoverySetup = core {
        val setup = machine.createRecovery(passphrase)
        E2eeRecoverySetup(setup.recoveryKey, setup.request.toE2ee())
    }

    override suspend fun rotateBackup(): E2eeRecoverySetup = core {
        val setup = machine.rotateBackup()
        E2eeRecoverySetup(setup.recoveryKey, setup.request.toE2ee())
    }

    override suspend fun unlockBackup(input: ByteArray, secrets: E2eeRecoverySecrets): String = core {
        machine.unlockBackup(
            input,
            RecoverySecrets(
                defaultKey = secrets.defaultKey,
                keyDescription = secrets.keyDescription,
                backupSecret = secrets.backupSecret,
                backupVersion = secrets.backupVersion,
            )
        )
    }

    override suspend fun restoreBackup(keysBody: String): E2eeBackupRestore = core {
        UniffiBackupRestore(machine.restoreBackup(keysBody))
    }

    override suspend fun backupStatus(): E2eeBackupStatus = core {
        val status = machine.backupStatus()
        E2eeBackupStatus(
            version = status.version,
            enabled = status.enabled,
            recoverySetUp = status.recoverySetUp,
            outdated = status.outdated,
            keysTotal = status.keysTotal.toLong(),
            keysBackedUp = status.keysBackedUp.toLong(),
        )
    }

    override suspend fun disableBackup() = core { machine.disableBackup() }

    override fun recoveryKeyCheck(input: ByteArray): E2eeRecoveryKeyCheck = core {
        when (checkRecoveryKey(input)) {
            RecoveryKeyCheck.VALID -> E2eeRecoveryKeyCheck.Valid
            RecoveryKeyCheck.TYPO -> E2eeRecoveryKeyCheck.Typo
            RecoveryKeyCheck.NOT_A_KEY -> E2eeRecoveryKeyCheck.NotAKey
        }
    }

    // KeyExport (generated) prints the file in its toString(): mapped field by field
    override suspend fun exportRoomKeys(passphrase: ByteArray, channelIds: List<String>): E2eeKeyExport = core {
        val export = machine.exportRoomKeys(passphrase, channelIds)
        E2eeKeyExport(export.data, export.sessions.toInt())
    }

    override suspend fun importRoomKeys(file: String, passphrase: ByteArray): E2eeKeyImport = core {
        val result = machine.importRoomKeys(file, passphrase)
        E2eeKeyImport(
            total = result.total.toInt(),
            imported = result.imported.toInt(),
            skipped = result.skipped.toInt(),
            roomKeys = result.roomKeys.map { E2eeRoomKey(it.channelId, it.sessionId) },
        )
    }

    override suspend fun debugReport(): String = core { machine.debugReport() }

    override fun close() = machine.close()
}

/** [E2eeSas] over the UniFFI handle; [close] releases its hold on the machine. */
private class UniffiSas(private val handle: SasHandle) : E2eeSas {
    override val flowId: String = handle.flowId()
    override val otherUserId: String = core { handle.otherUserId() }
    override val isSelfVerification: Boolean = core { handle.isSelfVerification() }

    override fun otherDeviceId(): String? = core { handle.otherDeviceId() }

    override fun state(): E2eeSasState = core {
        when (val state = handle.state()) {
            SasState.Requested -> E2eeSasState.Requested
            SasState.Incoming -> E2eeSasState.Incoming
            SasState.Ready -> E2eeSasState.Ready
            SasState.Started -> E2eeSasState.Started
            is SasState.ShowCodes -> E2eeSasState.ShowCodes(
                emoji = state.emoji.map { E2eeSasEmoji(it.index.toInt(), it.symbol, it.description) },
                decimals = state.decimals.map { it.toInt() },
            )
            SasState.Confirmed -> E2eeSasState.Confirmed
            SasState.Done -> E2eeSasState.Done
            is SasState.Cancelled -> E2eeSasState.Cancelled(
                reason = when (state.reason) {
                    CancelReason.USER -> E2eeCancelReason.User
                    CancelReason.TIMEOUT -> E2eeCancelReason.Timeout
                    CancelReason.MISMATCH -> E2eeCancelReason.Mismatch
                    CancelReason.ACCEPTED_ELSEWHERE -> E2eeCancelReason.AcceptedElsewhere
                    CancelReason.OTHER -> E2eeCancelReason.Other
                },
                code = state.code,
                byUs = state.byUs,
            )
        }
    }

    override suspend fun accept() = core { handle.accept() }
    override suspend fun start() = core { handle.start() }
    override suspend fun confirm() = core { handle.confirm() }
    override suspend fun mismatch() = core { handle.mismatch() }
    override suspend fun cancel() = core { handle.cancel() }
    override fun close() = handle.close()
}

private class UniffiBackupRestore(private val restore: BackupRestore) : E2eeBackupRestore {
    override suspend fun progress() = core { restore.progress().toE2ee() }
    override suspend fun next(max: Int) = core { restore.next(max.coerceAtLeast(0).toUInt()).toE2ee() }
    override fun close() = restore.close()
}

private fun RestoreProgress.toE2ee() = E2eeRestoreProgress(
    done = done.toLong(),
    total = total.toLong(),
    imported = imported.toLong(),
    failed = failed.toLong(),
    finished = finished,
)

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
    is CryptoException.StoreMismatch -> E2eeException.StoreUnusable(this, otherDevice = true)
    is CryptoException.Store -> E2eeException.StoreUnusable(this)
    is CryptoException.MfaRequired -> E2eeException.ActionRequired(E2eeAction.MfaRequired, this)
    is CryptoException.BackupVersionChanged ->
        E2eeException.ActionRequired(E2eeAction.BackupVersionChanged, this)
    is CryptoException.DeviceKeysConflict ->
        E2eeException.ActionRequired(E2eeAction.DeviceKeysConflict, this)
    is CryptoException.RoomKeyNotShared -> E2eeException.RoomKeyNotShared(this)
    is CryptoException.PayloadTooLarge -> E2eeException.PayloadTooLarge(this)
    is CryptoException.UnknownChannel -> E2eeException.UnknownChannel(this)
    is CryptoException.AttachmentHashMismatch -> E2eeException.AttachmentHashMismatch(this)
    is CryptoException.RecoveryKeyTypo -> E2eeException.RecoveryKeyTypo(this)
    is CryptoException.WrongRecoveryKey -> E2eeException.WrongRecoveryKey(this)
    is CryptoException.BackupKeyMismatch, is CryptoException.InvalidRecoveryData ->
        E2eeException.BackupKeyMismatch(this)
    is CryptoException.NoBackup, is CryptoException.NoRecovery -> E2eeException.NoBackup(this)
    is CryptoException.PassphraseRequired -> E2eeException.PassphraseRequired(this)
    is CryptoException.InvalidKeyFile -> E2eeException.InvalidKeyFile(this)
    is CryptoException.WrongPassphrase -> E2eeException.WrongPassphrase(this)
    is CryptoException.RequestRejected -> E2eeException.RequestRejected(this)
    else -> E2eeException.Core(this::class.simpleName ?: "Unknown", this)
}
