package chat.stoat.api.fork.e2ee

import chat.stoat.api.StoatJson
import co.touchlab.kermit.Logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.concurrent.Volatile

/**
 * What the active account's E2EE session hears from the socket (plan AN-24). The app installs
 * it as [E2eeFrames.listener]; nothing is installed for servers without E2EE.
 *
 * Called on `realtimeContext`, one frame after the other: hand the work to the account's
 * `E2eeSync` and return, never wait for the network here.
 */
interface E2eeSocketListener {
    /** `Ready` was handled (caches filled), after every connect. */
    fun onReady()

    /** Mailbox doorbell; [recipientDevice] is a session id (the server sends only ours). */
    fun onToDevice(recipientDevice: String)

    fun onDeviceListUpdate(user: String)

    fun onKeysLow()

    /** The member list of a DM or group changed (`ChannelGroupJoin/Leave`). */
    fun onChannelMembers(channelId: String, members: List<String>)
}

/**
 * E2EE frames of the socket, routed from one line in `RealtimeSocket.handleFrame`
 * (C3; server side: 10-backend "Заметки реализации этапа 2"):
 *
 * - `E2eeToDevice {id, recipient_device, sender, sender_device, event_type}` — a doorbell only;
 * - `E2eeDeviceListUpdate {user}`;
 * - `E2eeKeysLow {device, count}` — in the plan, not sent by the backend yet; harmless.
 *
 * The frames carry no content, but are not logged anyway.
 */
object E2eeFrames {
    val TYPES = setOf("E2eeToDevice", "E2eeDeviceListUpdate", "E2eeKeysLow")

    @Volatile
    var listener: E2eeSocketListener? = null

    @Serializable
    internal data class ToDeviceFrame(
        val id: String? = null,
        @SerialName("recipient_device") val recipientDevice: String,
    )

    @Serializable
    internal data class DeviceListUpdateFrame(val user: String)

    fun handle(type: String, rawFrame: String) {
        val target = listener ?: return
        try {
            when (type) {
                "E2eeToDevice" -> target.onToDevice(
                    StoatJson.decodeFromString(ToDeviceFrame.serializer(), rawFrame).recipientDevice
                )
                "E2eeDeviceListUpdate" -> target.onDeviceListUpdate(
                    StoatJson.decodeFromString(DeviceListUpdateFrame.serializer(), rawFrame).user
                )
                "E2eeKeysLow" -> target.onKeysLow()
            }
        } catch (e: IllegalArgumentException) {
            Logger.w { "Malformed $type frame" }
        }
    }

    /** End of the `Ready` handler. */
    fun onReady() {
        listener?.onReady()
    }

    fun onChannelMembers(channelId: String, members: List<String>) {
        listener?.onChannelMembers(channelId, members)
    }
}
