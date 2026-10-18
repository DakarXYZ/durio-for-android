package chat.stoat.api.fork.e2ee

import chat.stoat.api.StoatAPI
import chat.stoat.api.fork.realtime.GroupMembershipFrames
import chat.stoat.core.model.schemas.Channel
import chat.stoat.core.model.schemas.ChannelType
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Socket frames for the E2EE session (plan AN-24; frame shapes of the stage 2 backend)
class E2eeFramesTest {
    private val heard = mutableListOf<String>()

    private val listener = object : E2eeSocketListener {
        override fun onReady() {
            heard += "ready"
        }

        override fun onToDevice(recipientDevice: String) {
            heard += "toDevice $recipientDevice"
        }

        override fun onDeviceListUpdate(user: String) {
            heard += "deviceList $user"
        }

        override fun onKeysLow() {
            heard += "keysLow"
        }

        override fun onChannelMembers(channelId: String, members: List<String>) {
            heard += "members $channelId ${members.joinToString(",")}"
        }
    }

    @BeforeTest
    fun install() {
        E2eeFrames.listener = listener
    }

    @AfterTest
    fun uninstall() {
        E2eeFrames.listener = null
        StoatAPI.channelCache.clear()
    }

    @Test
    fun framesReachTheListener() {
        E2eeFrames.handle(
            "E2eeToDevice",
            """{"type":"E2eeToDevice","id":"01M","recipient_device":"01S","sender":"@01u:x","sender_device":"01D","event_type":"m.room.encrypted"}"""
        )
        E2eeFrames.handle("E2eeDeviceListUpdate", """{"type":"E2eeDeviceListUpdate","user":"01U"}""")
        E2eeFrames.handle("E2eeKeysLow", """{"type":"E2eeKeysLow","device":"01S","count":3}""")
        E2eeFrames.onReady()

        assertEquals(listOf("toDevice 01S", "deviceList 01U", "keysLow", "ready"), heard)
    }

    @Test
    fun malformedFramesAreDropped() {
        E2eeFrames.handle("E2eeToDevice", """{"type":"E2eeToDevice"}""")
        E2eeFrames.handle("E2eeDeviceListUpdate", "not json")
        assertEquals(emptyList(), heard)
    }

    @Test
    fun withoutListenerNothingHappens() {
        E2eeFrames.listener = null
        E2eeFrames.handle("E2eeDeviceListUpdate", """{"type":"E2eeDeviceListUpdate","user":"01U"}""")
        E2eeFrames.onReady()
        assertEquals(emptyList(), heard)
    }

    @Test
    fun groupJoinAndLeaveUpdateMembers() {
        StoatAPI.channelCache["g1"] = Channel(id = "g1", channelType = ChannelType.Group, recipients = listOf("a", "b"))

        GroupMembershipFrames.handle("ChannelGroupJoin", """{"type":"ChannelGroupJoin","id":"g1","user":"c"}""")
        // repeated join: no change, no event
        GroupMembershipFrames.handle("ChannelGroupJoin", """{"type":"ChannelGroupJoin","id":"g1","user":"c"}""")
        GroupMembershipFrames.handle("ChannelGroupLeave", """{"type":"ChannelGroupLeave","id":"g1","user":"a"}""")
        // unknown channel
        GroupMembershipFrames.handle("ChannelGroupJoin", """{"type":"ChannelGroupJoin","id":"g2","user":"c"}""")

        assertEquals(listOf("b", "c"), StoatAPI.channelCache["g1"]?.recipients)
        assertEquals(listOf("members g1 a,b,c", "members g1 b,c"), heard)
    }
}
