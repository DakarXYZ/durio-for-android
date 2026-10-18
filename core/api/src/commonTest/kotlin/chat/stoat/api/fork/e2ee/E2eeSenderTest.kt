package chat.stoat.api.fork.e2ee

import chat.stoat.api.fork.http.ApiClient
import chat.stoat.api.fork.routes.EncryptedSendException
import chat.stoat.api.fork.routes.editEncryptedMessage
import chat.stoat.api.fork.routes.sendEncryptedMessage
import chat.stoat.api.routes.channel.SendMessageReply
import chat.stoat.core.model.fork.EncryptedEnvelope
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Send path of encrypted DMs/groups (plan AN-26; host contract step 7) and its routes
class E2eeSenderTest {
    private val log = mutableListOf<String>()
    private var status = HttpStatusCode.OK
    private var reply = "{}"

    private val client = object : ApiClient {
        override val http = HttpClient(
            MockEngine { request ->
                val body = (request.body as? TextContent)?.text.orEmpty()
                log += "${request.method.value} ${request.url.encodedPath.removePrefix("/api")} $body".trim()
                respond(reply, status)
            }
        )

        override fun api(path: String) = "https://a.example/api$path"
    }

    private val core = FakeE2eeCore()

    private fun sender(scope: kotlinx.coroutines.CoroutineScope) = E2eeSender(core, E2eeSync(core, client, scope))

    private fun payload() = E2eeSender.message(content = "hi", nonce = "01NONCE", replies = listOf("01R"), ts = 5)

    @Test
    fun sharesTheKeysThenEncryptsOnce() = runTest {
        core.toPrepare["c1"] = mutableListOf(request("claim", E2eeRequestKind.KeysClaim, path = "/e2ee/keys/claim"))

        val envelope = sender(backgroundScope).encrypt("c1", listOf("01A", "01B"), payload())

        assertEquals(
            listOf("members c1 01A,01B", "prepare c1", "sent claim {}", "prepare c1", "encrypt c1"),
            core.calls
        )
        assertEquals(listOf("POST /e2ee/keys/claim {\"r\":\"claim\"}"), log)
        // the library's extra sender_key is dropped (C7)
        assertEquals(EncryptedEnvelope(sessionId = "s1", deviceId = core.deviceId, ciphertext = "C1"), envelope)
        val sent = Json.parseToJsonElement(core.encrypted.single().second).jsonObject
        assertEquals("01NONCE", sent["client_nonce"]?.jsonPrimitive?.content)
        assertEquals(listOf("01R"), sent["in_reply_to"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals(0, sent["edit_seq"]?.jsonPrimitive?.long)
        assertEquals(1, sent["v"]?.jsonPrimitive?.long)
    }

    @Test
    fun roomKeyNotSharedGoesBackToPrepare() = runTest {
        core.notShared = 1

        sender(backgroundScope).encrypt("c1", listOf("01A"), payload())

        assertEquals(2, core.calls.count { it == "prepare c1" })
        assertEquals(1, core.encrypted.size)
    }

    @Test
    fun dmPartnerWithoutDevicesIsNotSentTo() = runTest {
        assertFailsWith<E2eeNoRecipientDevicesException> {
            sender(backgroundScope).encrypt("c1", listOf("01A", "01B"), payload(), requireDevicesOf = "01B")
        }
        assertTrue(core.encrypted.isEmpty())

        core.userDevices["01B"] = listOf(E2eeDevice("01B", "D", "ed", verified = false, blocked = false, deleted = false))
        sender(backgroundScope).encrypt("c1", listOf("01A", "01B"), payload(), requireDevicesOf = "01B")
        assertEquals(1, core.encrypted.size)
    }

    @Test
    fun failedPreparationSendsNothing() = runTest {
        core.toPrepare["c1"] = mutableListOf(request("share", E2eeRequestKind.ToDevice, method = "PUT", path = "/e2ee/to_device/t1"))
        status = HttpStatusCode.BadGateway

        assertFailsWith<E2eeSendPreparationException> {
            sender(backgroundScope).encrypt("c1", listOf("01A"), payload())
        }
        assertTrue(core.encrypted.isEmpty())
    }

    @Test
    fun editPayloadBindsToTheMessage() {
        val json = E2eeSender.edit("01MSG", "new", "01N", previousEditSeq = 2, replies = listOf("01R"), attachments = emptyList(), ts = 9).toJson()
        val obj = Json.parseToJsonElement(json).jsonObject
        assertEquals("01MSG", obj["edit_of"]?.jsonPrimitive?.content)
        assertEquals(3, obj["edit_seq"]?.jsonPrimitive?.long)
        assertEquals("new", obj["content"]?.jsonPrimitive?.content)
        // an attachment-only message has no content field at all
        assertNull(Json.parseToJsonElement(E2eeSender.message(null, "n", ts = 1).toJson()).jsonObject["content"])
    }

    @Test
    fun sendRouteCarriesTheEnvelopeAndNoContent() = runTest {
        val envelope = EncryptedEnvelope(sessionId = "s", deviceId = "d", ciphertext = "x")
        sendEncryptedMessage(
            "c1", envelope, nonce = "01N", replies = listOf(SendMessageReply("01R", true)),
            attachments = listOf("f1", "t1"), idempotencyKey = "k", client = client,
        )
        editEncryptedMessage("c1", "m1", envelope, client)

        val send = Json.parseToJsonElement(log[0].substringAfter("messages ")).jsonObject
        assertEquals(setOf("encrypted", "nonce", "replies", "attachments"), send.keys)
        assertEquals("s", send["encrypted"]!!.jsonObject["session_id"]?.jsonPrimitive?.content)
        // the server requires every envelope field, defaults included
        assertEquals(1, send["encrypted"]!!.jsonObject["v"]?.jsonPrimitive?.long)
        assertEquals(EncryptedEnvelope.MEGOLM_ALGORITHM, send["encrypted"]!!.jsonObject["algorithm"]?.jsonPrimitive?.content)
        assertEquals(listOf("f1", "t1"), send["attachments"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertTrue(log[1].startsWith("PATCH /channels/c1/messages/m1 {\"encrypted\":"))
        assertFalse("content" in log[1])
    }

    @Test
    fun refusedSendThrowsWithTheServerType() = runTest {
        status = HttpStatusCode.BadRequest
        reply = """{"type":"EncryptedChannelViolation"}"""
        val e = assertFailsWith<EncryptedSendException> {
            sendEncryptedMessage("c1", EncryptedEnvelope(sessionId = "s", deviceId = "d", ciphertext = "x"), "n", idempotencyKey = "k", client = client)
        }
        assertEquals(400, e.status)
        assertEquals("EncryptedChannelViolation", e.type)
    }
}
