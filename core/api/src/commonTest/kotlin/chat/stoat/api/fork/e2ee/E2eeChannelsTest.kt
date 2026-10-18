package chat.stoat.api.fork.e2ee

import chat.stoat.api.StoatAPI
import chat.stoat.core.model.fork.ChannelEncryption
import chat.stoat.core.model.schemas.Channel
import chat.stoat.core.model.schemas.ChannelType
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Local pin of the encryption flag (plan AN-39): never removed, survives a server downgrade
// and the SQLite hydration before Ready
class E2eeChannelsTest {
    private class Store(override val identity: Any = Any(), val pins: MutableSet<String> = mutableSetOf()) :
        E2eeChannels.PinStore {
        override fun load() = pins.toSet()
        override fun pin(channelId: String) {
            pins += channelId
        }
    }

    private var store = Store()

    @BeforeTest
    fun setUp() {
        StoatAPI.channelCache.clear()
        E2eeChannels.store = { store }
    }

    @AfterTest
    fun tearDown() {
        StoatAPI.channelCache.clear()
        E2eeChannels.store = { null }
    }

    private fun dm(id: String, encrypted: Boolean) = Channel(
        id = id,
        channelType = ChannelType.DirectMessage,
        recipients = listOf("me", "you"),
        encryption = if (encrypted) ChannelEncryption(enabledBy = "you") else null,
    )

    @Test
    fun encryptedChannelIsPinnedOnce() {
        assertTrue(E2eeChannels.observe(dm("c1", encrypted = true)))
        assertFalse(E2eeChannels.observe(dm("c1", encrypted = true)))
        assertEquals(setOf("c1"), store.pins)
    }

    @Test
    fun serverDroppingTheFlagDoesNotTurnEncryptionOff() {
        E2eeChannels.observe(dm("c1", encrypted = true))

        val stripped = dm("c1", encrypted = false)
        StoatAPI.channelCache["c1"] = stripped
        E2eeChannels.observe(stripped)

        assertTrue(E2eeChannels.isEncrypted("c1"))
        assertTrue(E2eeChannels.isEncrypted(stripped))
        assertTrue(E2eeChannels.isDowngraded("c1"))
        assertEquals(listOf("c1" to listOf("me", "you")), E2eeChannels.encryptedWithMembers())
    }

    @Test
    fun pinsFromTheDatabaseApplyBeforeReady() {
        // a new account (store) with a pin from an earlier run; the channel as hydrated from SQLite
        store = Store(pins = mutableSetOf("c9"))
        StoatAPI.channelCache["c9"] = dm("c9", encrypted = false)

        assertTrue(E2eeChannels.isEncrypted("c9"))
        assertFalse(E2eeChannels.isEncrypted("other"))
    }

    @Test
    fun serverChannelsAreNeverEncrypted() {
        val text = Channel(id = "t1", channelType = ChannelType.TextChannel, encryption = ChannelEncryption())
        assertFalse(E2eeChannels.observe(text))
        assertFalse(E2eeChannels.isEncrypted(text))
    }

    @Test
    fun enablingNotifiesTheMembersOnce() {
        val heard = mutableListOf<Pair<String, List<String>>>()
        E2eeFrames.listener = object : E2eeSocketListener {
            override fun onReady() = Unit
            override fun onToDevice(recipientDevice: String) = Unit
            override fun onDeviceListUpdate(user: String) = Unit
            override fun onKeysLow() = Unit
            override fun onChannelMembers(channelId: String, members: List<String>) {
                heard += channelId to members
            }
        }
        try {
            E2eeFrames.onServerChannel(dm("c3", encrypted = false))
            E2eeFrames.onServerChannel(dm("c3", encrypted = true))
            E2eeFrames.onServerChannel(dm("c3", encrypted = true))
            assertEquals(listOf("c3" to listOf("me", "you")), heard)
        } finally {
            E2eeFrames.listener = null
        }
    }
}
