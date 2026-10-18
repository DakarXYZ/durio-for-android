package chat.stoat.api.fork.realtime

import chat.stoat.api.StoatAPI
import chat.stoat.api.StoatJson
import chat.stoat.api.fork.e2ee.E2eeFrames
import co.touchlab.kermit.Logger
import kotlinx.serialization.Serializable

/**
 * `ChannelGroupJoin` / `ChannelGroupLeave {id, user}` (plan AN-24). Upstream had no handler:
 * the frames fell into "Unknown frame" and group members were only updated by a reload. The
 * backend sends no device-list event on join/leave, so the E2EE session hears about the new
 * member list from here and re-queries keys / rotates the group session.
 */
object GroupMembershipFrames {
    val TYPES = setOf("ChannelGroupJoin", "ChannelGroupLeave")

    @Serializable
    internal data class Frame(val id: String, val user: String)

    fun handle(type: String, rawFrame: String) {
        val frame = try {
            StoatJson.decodeFromString(Frame.serializer(), rawFrame)
        } catch (e: IllegalArgumentException) {
            Logger.w { "Malformed $type frame" }
            return
        }
        val channel = StoatAPI.channelCache[frame.id] ?: return
        val recipients = channel.recipients.orEmpty()
        val updated = when (type) {
            "ChannelGroupJoin" -> if (frame.user in recipients) recipients else recipients + frame.user
            else -> recipients - frame.user
        }
        if (updated == recipients) return

        StoatAPI.channelCache[frame.id] = channel.copy(recipients = updated)
        E2eeFrames.onChannelMembers(frame.id, updated)
    }
}
