package chat.stoat.api.fork.e2ee

import chat.stoat.api.internals.ULID
import chat.stoat.core.model.fork.EncryptedEnvelope
import chat.stoat.core.model.schemas.Message
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// MessageDecryptor (plan AN-25, AN-34): payload → message, statuses, waiting index; the server's
// placeholder never survives
class MessageDecryptorTest {
    private val core = FakeE2eeCore()
    private val decryptor = MessageDecryptor(core)

    @BeforeTest
    fun reset() = E2eeState.clear()

    private fun encrypted(id: String = ULID.makeNext(), session: String = "s1", replies: List<String>? = null) = Message(
        id = id,
        channel = "01CHANNEL0000000000000000A",
        author = "01AUTHOR000000000000000000",
        content = "🔒 Encrypted message",
        replies = replies,
        encrypted = EncryptedEnvelope(sessionId = session, deviceId = "01DEVICE", ciphertext = "AwgA"),
    )

    @Test
    fun plainMessagesPassThroughWithoutTheCore() = runTest {
        val plain = Message(id = ULID.makeNext(), channel = "c", author = "a", content = "hi")
        assertSame(plain, decryptor.decrypt(listOf(plain)).single())
        assertTrue(core.calls.isEmpty())
        assertTrue(E2eeState.status.isEmpty())
    }

    @Test
    fun decryptedMessageTakesTextAttachmentsAndMentionsFromThePayload() = runTest {
        val message = encrypted(replies = listOf("01REPLY"))
        core.decryptResults[message.id!!] = decryptedResult(PAYLOAD_WITH_ATTACHMENT)

        val shown = decryptor.decrypt(listOf(message)).single()

        assertEquals("hello <@01HXYZXYZXYZXYZXYZXYZXYZXY>", shown.content)
        assertEquals(listOf("01HXYZXYZXYZXYZXYZXYZXYZXY"), shown.mentions)
        val attachment = shown.attachments!!.single()
        assertEquals("01FILE", attachment.id)
        assertEquals("SPOILER_photo.jpg", attachment.filename)
        assertEquals("Image", attachment.metadata?.type)
        assertEquals(640L, attachment.metadata?.width)
        assertEquals(1234L, attachment.size)
        assertEquals(DecryptionStatus.Decrypted(E2eeVerification.Unverified, 2), E2eeState.status[message.id])
        val keys = E2eeState.attachmentKeys["01FILE"]!!
        assertEquals("01THUMB", keys.thumbnailId)
        assertTrue(keys.spoiler)
        assertTrue(keys.fileJson.contains("\"v\":\"v2\""))
        // what went to the core: the envelope and the open replies
        val incoming = core.decrypted.single()
        assertEquals(listOf("01REPLY"), incoming.replies)
        assertTrue(incoming.envelopeJson.contains("\"session_id\":\"s1\""))
        assertEquals(ULID.asTimestamp(message.id!!), incoming.timestampMs)
    }

    @Test
    fun waitingMessageIsHiddenAndHandedOutWhenItsKeyArrives() = runTest {
        val a = encrypted(session = "s1")
        val b = encrypted(session = "s2")

        val shown = decryptor.decrypt(listOf(a, b))

        shown.forEach {
            assertNull(it.content)
            assertNull(it.attachments)
        }
        assertEquals(DecryptionStatus.Pending, E2eeState.status[a.id])
        assertEquals(setOf(a.id!!), decryptor.waitingFor(listOf(E2eeRoomKey("c", "s1"))))
        // handed out once
        assertTrue(decryptor.waitingFor(listOf(E2eeRoomKey("c", "s1"))).isEmpty())
        assertEquals(setOf(b.id!!), decryptor.waitingFor(listOf(E2eeRoomKey("c", "s2"))))
    }

    @Test
    fun storeTroubleIsRetriedOnAnyKey() = runTest {
        val message = encrypted()
        core.decryptThrows = E2eeException.Core("Store")

        val shown = decryptor.decrypt(listOf(message)).single()

        assertNull(shown.content)
        assertEquals(DecryptionStatus.Pending, E2eeState.status[message.id])
        assertEquals(setOf(message.id!!), decryptor.waitingFor(listOf(E2eeRoomKey("c", "other"))))
    }

    @Test
    fun failuresMapToTheirStatus() = runTest {
        val cases = mapOf(
            E2eeFailureKind.Suspicious to DecryptionStatus.Suspicious,
            E2eeFailureKind.UpdateApp to DecryptionStatus.Failed(DecryptionStatus.Failed.Reason.UpdateApp),
            E2eeFailureKind.Broken to DecryptionStatus.Failed(DecryptionStatus.Failed.Reason.Broken),
            E2eeFailureKind.Withheld("UNVERIFIED") to DecryptionStatus.Failed(DecryptionStatus.Failed.Reason.Withheld),
        )
        for ((kind, status) in cases) {
            val message = encrypted()
            core.decryptResults[message.id!!] = E2eeDecryptResult.Failed(kind, "code")
            val shown = decryptor.decrypt(listOf(message)).single()
            assertNull(shown.content, "$kind")
            assertEquals(status, E2eeState.status[message.id])
        }
    }

    @Test
    fun replayedOrRebindedMessagesAreSuspicious() = runTest {
        for (result in listOf(
            decryptedResult(PAYLOAD, isReplay = true),
            decryptedResult(PAYLOAD, replyMismatch = true),
            decryptedResult(PAYLOAD, verification = E2eeVerification.Mismatch),
        )) {
            val message = encrypted()
            core.decryptResults[message.id!!] = result
            val shown = decryptor.decrypt(listOf(message)).single()
            assertNull(shown.content)
            assertIs<DecryptionStatus.Suspicious>(E2eeState.status[message.id])
        }
    }

    @Test
    fun staleEditKeepsTheVersionShown() = runTest {
        val message = encrypted()
        core.decryptResults[message.id!!] = decryptedResult(PAYLOAD)
        val first = decryptor.decrypt(listOf(message)).single()

        val replayedOld = message.copy(encrypted = message.encrypted!!.copy(ciphertext = "Older"))
        core.decryptResults[message.id!!] = decryptedResult(PAYLOAD, staleEdit = true)
        val shown = decryptor.decrypt(listOf(replayedOld), mapOf(message.id!! to first)).single()

        assertSame(first, shown)
        // without a shown version it is suspicious
        val fresh = encrypted()
        core.decryptResults[fresh.id!!] = decryptedResult(PAYLOAD, staleEdit = true)
        assertNull(decryptor.decrypt(listOf(fresh)).single().content)
        assertEquals(DecryptionStatus.Suspicious, E2eeState.status[fresh.id])
    }

    @Test
    fun malformedPayloadIsBrokenNotShown() = runTest {
        val message = encrypted()
        core.decryptResults[message.id!!] = decryptedResult("""{"v":1,"content":"x"}""")
        assertNull(decryptor.decrypt(listOf(message)).single().content)
        assertEquals(DecryptionStatus.Failed(DecryptionStatus.Failed.Reason.Broken), E2eeState.status[message.id])
    }

    @Test
    fun payloadToStringLeavesTheTextOut() {
        val payload = E2eePayload.parse(PAYLOAD_WITH_ATTACHMENT)
        assertTrue("hello" !in payload.toString())
        assertTrue("photo" !in payload.attachments.single().toString())
        assertEquals(listOf("01FILE", "01THUMB"), payload.fileIds())
    }

    companion object {
        const val FILE = """{"v":"v2","key":{"kty":"oct","key_ops":["encrypt","decrypt"],"alg":"A256CTR","k":"k","ext":true},"iv":"iv","hashes":{"sha256":"h"}}"""

        const val PAYLOAD = """{"v":1,"content":"hi","client_nonce":"n","edit_seq":0,"ts":1,"pad":"000"}"""

        const val PAYLOAD_WITH_ATTACHMENT = """{"v":1,"content":"hello <@01HXYZXYZXYZXYZXYZXYZXYZXY>",
            "attachments":[{"id":"01FILE","name":"photo.jpg","content_type":"image/jpeg","size":1234,
            "metadata":{"type":"Image","width":640,"height":480},"spoiler":true,"file":$FILE,
            "thumbnail":{"id":"01THUMB","width":320,"height":240,"file":$FILE}}],
            "client_nonce":"n","edit_seq":2,"in_reply_to":["01REPLY"],"ts":1,"pad":"00","future_field":1}"""
    }
}
