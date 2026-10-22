package chat.stoat.api.fork.channels

import chat.stoat.api.internals.BitDefaults
import chat.stoat.api.internals.PermissionBit
import chat.stoat.api.internals.Roles
import chat.stoat.api.internals.hasPermission
import chat.stoat.core.model.schemas.Channel
import chat.stoat.core.model.schemas.PermissionDescription
import chat.stoat.core.model.schemas.Server
import chat.stoat.api.StoatJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Members' own channels: the maker runs them, moderators keep their server permissions
class UserChannelPermissionsTest {
    private val member = BitDefaults.Server

    @Test
    fun otherChannelsAreUntouched() {
        assertEquals(member, Roles.userChannelPermissions(member, null, "me", member))
    }

    @Test
    fun theMakerRunsTheirChannel() {
        val denied = member and PermissionBit.ViewChannel.value.inv()
        val result = Roles.userChannelPermissions(denied, "me", "me", member)
        assertTrue(result.hasPermission(PermissionBit.ViewChannel))
        assertTrue(result.hasPermission(PermissionBit.ManageMessages))
        assertTrue(result.hasPermission(PermissionBit.ManagePermissions))
        assertFalse(result.hasPermission(PermissionBit.KickMembers))
    }

    @Test
    fun othersGetNothingExtra() {
        val readOnly = member and PermissionBit.SendMessage.value.inv()
        assertEquals(readOnly, Roles.userChannelPermissions(readOnly, "maker", "me", member))
    }

    @Test
    fun moderatorsKeepTheirServerPermissions() {
        val moderator = member or PermissionBit.ManageChannel.value or PermissionBit.KickMembers.value
        val result = Roles.userChannelPermissions(0L, "maker", "me", moderator)
        assertTrue(result.hasPermission(PermissionBit.ViewChannel))
        assertTrue(result.hasPermission(PermissionBit.SendMessage))
        assertFalse(result.hasPermission(PermissionBit.ManageMessages))
    }

    @Test
    fun fieldsFromTheServer() {
        val channel = StoatJson.decodeFromString(
            Channel.serializer(),
            """{"_id":"c","channel_type":"TextChannel","server":"s","name":"x","owner":"u1",""" +
                    """"user_permissions":{"u2":{"a":0,"d":4194304}}}""",
        )
        assertEquals("u1", channel.owner)
        assertEquals(PermissionDescription(0, 4194304), channel.userPermissions?.get("u2"))
        val updated = channel.mergeWithPartial(Channel(userPermissions = emptyMap()))
        assertEquals(emptyMap(), updated.userPermissions)

        val server = StoatJson.decodeFromString(
            Server.serializer(),
            """{"_id":"s","owner":"o","name":"n","channels":[],"user_channel_limit":2}""",
        )
        assertEquals(2, server.userChannelLimit)
    }
}
