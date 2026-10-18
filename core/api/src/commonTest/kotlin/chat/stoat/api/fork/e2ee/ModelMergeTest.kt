package chat.stoat.api.fork.e2ee

import chat.stoat.api.StoatJson
import chat.stoat.core.model.fork.ChannelEncryption
import chat.stoat.core.model.fork.EncryptedEnvelope
import chat.stoat.core.model.schemas.Channel
import chat.stoat.core.model.schemas.Message
import chat.stoat.core.model.schemas.SystemInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The model fields E2EE relies on (plan AN-25): parsed, and not lost by partial updates
class ModelMergeTest {
    @Test
    fun messageParsesTheEnvelopeAndKeepsItsFieldsOnUpdate() {
        val message = StoatJson.decodeFromString(
            Message.serializer(),
            """{"_id":"m","channel":"c","author":"a","content":"🔒","pinned":true,"flags":4,"system":{"type":"text"},
               "encrypted":{"v":1,"algorithm":"m.megolm.v1.aes-sha2","session_id":"s","device_id":"d","ciphertext":"CIPHERTEXT"}}"""
        )
        assertEquals(EncryptedEnvelope(sessionId = "s", deviceId = "d", ciphertext = "CIPHERTEXT"), message.encrypted)

        val merged = message.mergeWithPartial(Message(content = "edited"))
        assertEquals(true, merged.pinned)
        assertEquals(4, merged.flags)
        assertEquals(SystemInfo(type = "text"), merged.system)
        assertEquals(message.encrypted, merged.encrypted)
        assertTrue("CIPHERTEXT" !in message.encrypted.toString())
    }

    @Test
    fun channelEncryptionSurvivesPartialUpdates() {
        val channel = StoatJson.decodeFromString(
            Channel.serializer(),
            """{"_id":"c","channel_type":"Group","name":"g","recipients":["a"],
               "encryption":{"algorithm":"m.megolm.v1.aes-sha2","enabled_at":"2026-10-18T00:00:00Z","enabled_by":"a"}}"""
        )
        assertEquals("a", channel.encryption?.enabledBy)
        assertEquals(channel.encryption, channel.mergeWithPartial(Channel(name = "renamed")).encryption)

        val enabled = Channel(name = "g").mergeWithPartial(Channel(encryption = ChannelEncryption()))
        assertEquals(EncryptedEnvelope.MEGOLM_ALGORITHM, enabled.encryption?.algorithm)
    }
}
