package chat.stoat.api.fork.e2ee

import chat.stoat.api.fork.http.ApiClient
import chat.stoat.api.internals.ULID
import chat.stoat.core.model.fork.EncryptedEnvelope
import chat.stoat.core.model.schemas.Message
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

// Text of an encrypted push (plan AN-29): envelope, one mailbox pass for a missing key,
// replies unknown in a push, never text from a tampered message
class E2eePushDecryptorTest {
    private val log = mutableListOf<String>()
    // the mock answers on the test scheduler, so the mailbox timeout runs in virtual time
    private fun TestScope.client() = object : ApiClient {
        override val http = HttpClient(
            MockEngine.create {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    log += "${request.method.value} ${request.url.encodedPathAndQuery.removePrefix("/api")}"
                    respond("""{"messages":[]}""", HttpStatusCode.OK)
                }
            }
        )

        override fun api(path: String) = "https://a.example/api$path"
    }

    private val core = FakeE2eeCore()

    private fun TestScope.decryptor(scope: kotlinx.coroutines.CoroutineScope) = E2eePushDecryptor(core, E2eeSync(core, client(), scope))

    private val pushed = Message(
        id = ULID.makeNext(),
        channel = "01CHANNEL0000000000000000A",
        author = "01AUTHOR000000000000000000",
        encrypted = EncryptedEnvelope(sessionId = "s1", deviceId = "d", ciphertext = "c"),
    )

    @Test
    fun envelopeDecryptsWithoutTheNetwork() = runTest {
        core.decryptResults[pushed.id!!] = decryptedResult(MessageDecryptorTest.PAYLOAD)

        val outcome = assertIs<E2eePushDecryptor.Outcome.Text>(decryptor(backgroundScope).decrypt(pushed, repliesKnown = false))

        assertEquals("hi", outcome.content)
        assertEquals(emptyList(), log)
    }

    @Test
    fun missingKeyFetchesTheMailboxOnceAndTriesAgain() = runTest {
        core.onToDevice = { core.decryptResults[pushed.id!!] = decryptedResult(MessageDecryptorTest.PAYLOAD) }

        val outcome = decryptor(backgroundScope).decrypt(pushed, repliesKnown = false)

        assertEquals("hi", assertIs<E2eePushDecryptor.Outcome.Text>(outcome).content)
        assertEquals(listOf("GET /e2ee/to_device?limit=100"), log)
        assertEquals(2, core.calls.count { it.startsWith("decrypt") })
    }

    @Test
    fun stillWaitingAfterTheMailbox() = runTest {
        assertEquals(E2eePushDecryptor.Outcome.Waiting, decryptor(backgroundScope).decrypt(pushed, repliesKnown = false))
    }

    @Test
    fun aReplyIsReadableOnlyWhileItsRepliesAreUnknown() = runTest {
        core.decryptResults[pushed.id!!] = decryptedResult(MessageDecryptorTest.PAYLOAD, replyMismatch = true)

        assertIs<E2eePushDecryptor.Outcome.Text>(decryptor(backgroundScope).decrypt(pushed, repliesKnown = false))
        assertEquals(E2eePushDecryptor.Outcome.NotReadable, decryptor(backgroundScope).decrypt(pushed, repliesKnown = true))
    }

    @Test
    fun tamperedMessagesAreNeverText() = runTest {
        for (result in listOf(
            decryptedResult(MessageDecryptorTest.PAYLOAD, isReplay = true),
            decryptedResult(MessageDecryptorTest.PAYLOAD, verification = E2eeVerification.Mismatch, replyMismatch = true),
            E2eeDecryptResult.Failed(E2eeFailureKind.Suspicious, "SenderMismatch"),
            E2eeDecryptResult.Failed(E2eeFailureKind.UpdateApp, "UnsupportedVersion"),
        )) {
            core.decryptResults[pushed.id!!] = result
            assertEquals(E2eePushDecryptor.Outcome.NotReadable, decryptor(backgroundScope).decrypt(pushed, repliesKnown = false))
        }
    }

    @Test
    fun attachmentOnlyMessageCountsItsFiles() = runTest {
        core.decryptResults[pushed.id!!] = decryptedResult(
            """{"v":1,"attachments":[{"id":"f","name":"a.pdf","content_type":"application/pdf","size":1,"metadata":{"type":"File"},
               "file":${MessageDecryptorTest.FILE}}],"client_nonce":"n","ts":1}"""
        )
        val outcome = assertIs<E2eePushDecryptor.Outcome.Text>(decryptor(backgroundScope).decrypt(pushed, repliesKnown = false))
        assertEquals(null, outcome.content)
        assertEquals(1, outcome.attachments)
    }
}
