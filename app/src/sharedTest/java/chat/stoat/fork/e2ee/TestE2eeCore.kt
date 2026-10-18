package chat.stoat.fork.e2ee

import chat.stoat.api.fork.e2ee.E2eeBackupRestore
import chat.stoat.api.fork.e2ee.E2eeBackupStatus
import chat.stoat.api.fork.e2ee.E2eeChannelSettings
import chat.stoat.api.fork.e2ee.E2eeCore
import chat.stoat.api.fork.e2ee.E2eeDecryptResult
import chat.stoat.api.fork.e2ee.E2eeDevice
import chat.stoat.api.fork.e2ee.E2eeIdentityKeys
import chat.stoat.api.fork.e2ee.E2eeIncomingMessage
import chat.stoat.api.fork.e2ee.E2eeLimits
import chat.stoat.api.fork.e2ee.E2eeRecoveryKeyCheck
import chat.stoat.api.fork.e2ee.E2eeRecoverySecrets
import chat.stoat.api.fork.e2ee.E2eeRecoverySetup
import chat.stoat.api.fork.e2ee.E2eeRequest
import chat.stoat.api.fork.e2ee.E2eeSas
import chat.stoat.api.fork.e2ee.E2eeTrust
import chat.stoat.api.fork.e2ee.E2eeUndeliveredDevice
import chat.stoat.api.fork.e2ee.ToDeviceOutcome

/** An [E2eeCore] that does nothing; app tests override what they look at. */
open class TestE2eeCore(
    override val userId: String = "u",
    override val deviceId: String = "d",
) : E2eeCore {
    var closed = false
    override suspend fun setServerLimits(limits: E2eeLimits) = Unit
    override suspend fun outgoingRequests() = emptyList<E2eeRequest>()
    override suspend fun markRequestSent(request: E2eeRequest, body: String) = Unit
    override suspend fun markRequestFailed(request: E2eeRequest, status: Int) = Unit
    override suspend fun wakeupInMs(): Long? = null
    override suspend fun receiveToDevice(body: String) = ToDeviceOutcome(0, emptyList(), emptyList(), emptyList())
    override suspend fun markAckSent(ids: List<String>) = Unit
    override suspend fun receiveDeviceListUpdate(users: List<String>) = Unit
    override suspend fun receiveReconnect() = Unit
    override suspend fun receiveOtkCounts(body: String) = Unit
    override suspend fun setChannelMembers(channelId: String, members: List<String>, settings: E2eeChannelSettings) = Unit
    override suspend fun prepareSend(channelId: String) = emptyList<E2eeRequest>()
    override suspend fun undeliveredDevices() = emptyList<E2eeUndeliveredDevice>()
    override suspend fun decrypt(messages: List<E2eeIncomingMessage>): List<E2eeDecryptResult> = emptyList()
    override suspend fun encrypt(channelId: String, payloadJson: String): String = error("not scripted")
    override suspend fun devices(userId: String) = emptyList<E2eeDevice>()
    override suspend fun identityKeys() = E2eeIdentityKeys("c", "e")
    override suspend fun setDeviceTrust(userId: String, deviceId: String, trust: E2eeTrust) = Unit
    override suspend fun requestVerification(userId: String, deviceId: String): E2eeSas = error("not scripted")
    override suspend fun verification(flowId: String): E2eeSas? = null
    override suspend fun verifications() = emptyList<E2eeSas>()
    override suspend fun createRecovery(passphrase: ByteArray?): E2eeRecoverySetup = error("not scripted")
    override suspend fun rotateBackup(): E2eeRecoverySetup = error("not scripted")
    override suspend fun unlockBackup(input: ByteArray, secrets: E2eeRecoverySecrets): String = error("not scripted")
    override suspend fun restoreBackup(keysBody: String): E2eeBackupRestore = error("not scripted")
    override suspend fun backupStatus() = E2eeBackupStatus(null, false, false, false, 0, 0)
    override suspend fun disableBackup() = Unit
    override fun recoveryKeyCheck(input: ByteArray) = E2eeRecoveryKeyCheck.NotAKey
    override fun close() {
        closed = true
    }
}
