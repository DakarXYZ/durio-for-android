package chat.stoat.api.fork.e2ee

import app.cash.turbine.test
import chat.stoat.api.StoatAPI
import chat.stoat.api.StoatJson
import chat.stoat.api.fork.http.ApiClient
import chat.stoat.api.internals.ULID
import chat.stoat.api.realtime.frames.receivable.MessageUpdateFrame
import chat.stoat.core.model.fork.EncryptedEnvelope
import chat.stoat.core.model.schemas.Message
import chat.stoat.core.model.schemas.MessagesInChannel
import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

// Entry points of encrypted messages (plan AN-25, AN-46): socket, updates, history, late keys
class E2eeMessagesTest {
    private val core = FakeE2eeCore()

    @BeforeTest
    fun setUp() = runTest {
        StoatAPI.messageCache.clear()
        E2eeMessages.install(MessageDecryptor(core))
    }

    @AfterTest
    fun tearDown() = runTest {
        E2eeMessages.install(null)
        StoatAPI.messageCache.clear()
    }

    private fun encrypted(id: String = ULID.makeNext(), ciphertext: String = "AwgA", content: String = PLACEHOLDER) = Message(
        id = id,
        channel = "01CHANNEL0000000000000000A",
        author = "01AUTHOR000000000000000000",
        content = content,
        encrypted = EncryptedEnvelope(sessionId = "s1", deviceId = "01DEVICE", ciphertext = ciphertext),
    )

    @Test
    fun withoutADeviceEncryptedMessagesAreHidden() = runTest {
        E2eeMessages.install(null)
        val message = encrypted()

        val shown = E2eeMessages.incoming(message)

        assertNull(shown.content)
        assertEquals(DecryptionStatus.Failed(DecryptionStatus.Failed.Reason.NoDevice), E2eeState.status[message.id])
    }

    @Test
    fun historyIsDecryptedOnlyForTheActiveAccount() = runTest {
        val message = encrypted()
        core.decryptResults[message.id!!] = decryptedResult(MessageDecryptorTest.PAYLOAD)
        val page = MessagesInChannel(messages = listOf(message))

        assertEquals("hi", E2eeMessages.history(page).messages!!.single().content)

        val other = object : ApiClient {
            override val http = HttpClient()
            override fun api(path: String) = path
        }
        assertSame(page, E2eeMessages.history(page, other))
        assertSame(message, E2eeMessages.single(message, other))
    }

    @Test
    fun pinnedPageIsDecryptedAndStaysPinned() = runTest {
        // searchChannel(pinned = true) goes through the same hook (plan AN-40)
        val pinned = encrypted().copy(pinned = true)
        val waiting = encrypted().copy(pinned = true)
        core.decryptResults[pinned.id!!] = decryptedResult(MessageDecryptorTest.PAYLOAD)

        val page = E2eeMessages.history(MessagesInChannel(messages = listOf(pinned, waiting))).messages!!

        assertEquals("hi", page[0].content)
        assertEquals(true, page[0].pinned)
        assertNull(page[1].content)
        assertEquals(DecryptionStatus.Pending, E2eeState.status[waiting.id])
    }

    @Test
    fun editIsDecryptedAndTheUiFrameCarriesThePlaintext() = runTest {
        val old = encrypted()
        core.decryptResults[old.id!!] = decryptedResult(MessageDecryptorTest.PAYLOAD)
        val shown = E2eeMessages.incoming(old)

        val edit = encrypted(id = old.id!!, ciphertext = "Edited")
        core.decryptResults[old.id!!] = decryptedResult("""{"v":1,"content":"edited","client_nonce":"n","edit_of":"${old.id}","edit_seq":1,"ts":2}""")
        val frame = MessageUpdateFrame(id = old.id!!, channel = old.channel!!, data = edit.toJson())

        val (cached, uiFrame) = E2eeMessages.updated(frame, shown, shown.mergeWithPartial(edit))

        assertEquals("edited", cached.content)
        assertEquals("edited", uiFrame.data["content"]?.jsonPrimitive?.content)
        assertEquals(DecryptionStatus.Decrypted(E2eeVerification.Unverified, 1), E2eeState.status[old.id])
    }

    @Test
    fun updateWithoutNewEnvelopeKeepsThePlaintext() = runTest {
        val old = encrypted()
        core.decryptResults[old.id!!] = decryptedResult(MessageDecryptorTest.PAYLOAD)
        val shown = E2eeMessages.incoming(old)
        core.calls.clear()

        // e.g. pinned: the server sends its placeholder again
        val partial = Message(pinned = true, content = PLACEHOLDER)
        val frame = MessageUpdateFrame(id = old.id!!, channel = old.channel!!, data = partial.toJson())
        val (cached, uiFrame) = E2eeMessages.updated(frame, shown, shown.mergeWithPartial(partial))

        assertEquals("hi", cached.content)
        assertEquals(true, cached.pinned)
        assertEquals("hi", uiFrame.data["content"]?.jsonPrimitive?.content)
        assertEquals(emptyList(), core.calls)
    }

    @Test
    fun lateKeyRedecryptsTheCachedMessageAndTellsTheScreen() = runTest {
        val message = encrypted()
        // the cache holds the hidden copy, with its envelope
        StoatAPI.messageCache[message.id!!] = E2eeMessages.incoming(message).also { assertNull(it.content) }
        core.decryptResults[message.id!!] = decryptedResult(MessageDecryptorTest.PAYLOAD)

        StoatAPI.wsFrameChannel.test {
            E2eeMessages.onRoomKeys(listOf(E2eeRoomKey(message.channel!!, "s1")))
            val frame = assertIs<MessageUpdateFrame>(awaitItem())
            assertEquals(message.id, frame.id)
            assertEquals("hi", frame.data["content"]?.jsonPrimitive?.content)
        }
        assertEquals("hi", StoatAPI.messageCache[message.id!!]?.content)
        assertIs<DecryptionStatus.Decrypted>(E2eeState.status[message.id])
    }

    @Test
    fun installingADeviceDecryptsWhatCameBefore() = runTest {
        E2eeMessages.install(null)
        val message = encrypted()
        StoatAPI.messageCache[message.id!!] = E2eeMessages.incoming(message)
        core.decryptResults[message.id!!] = decryptedResult(MessageDecryptorTest.PAYLOAD)

        StoatAPI.wsFrameChannel.test {
            E2eeMessages.install(MessageDecryptor(core))
            assertIs<MessageUpdateFrame>(awaitItem())
        }
        assertEquals("hi", StoatAPI.messageCache[message.id!!]?.content)
    }

    private fun Message.toJson() = StoatJson.encodeToJsonElement(Message.serializer(), this) as JsonObject

    companion object {
        const val PLACEHOLDER = "🔒 Encrypted message"
    }
}
