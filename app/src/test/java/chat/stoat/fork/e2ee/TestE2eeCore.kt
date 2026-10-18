package chat.stoat.fork.e2ee

import chat.stoat.api.fork.e2ee.E2eeChannelSettings
import chat.stoat.api.fork.e2ee.E2eeCore
import chat.stoat.api.fork.e2ee.E2eeDecryptResult
import chat.stoat.api.fork.e2ee.E2eeDevice
import chat.stoat.api.fork.e2ee.E2eeIncomingMessage
import chat.stoat.api.fork.e2ee.E2eeLimits
import chat.stoat.api.fork.e2ee.E2eeRequest
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
    override fun close() {
        closed = true
    }
}
