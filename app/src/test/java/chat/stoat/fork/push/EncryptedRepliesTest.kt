package chat.stoat.fork.push

import chat.stoat.api.fork.http.ApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

// Notification replies (plan AN-43): never plaintext into an encrypted chat, not even by guess;
// the reply text is handed to the job in memory only
class EncryptedRepliesTest {
    private fun client(answer: () -> String?) = object : ApiClient {
        override val http = HttpClient(
            MockEngine {
                val body = answer() ?: throw IOException("offline")
                if (body.isEmpty()) respondError(HttpStatusCode.NotFound) else respond(body, HttpStatusCode.OK)
            }
        )

        override fun api(path: String) = "https://a.example$path"
    }

    @Test
    fun theServersChannelDecides() = runTest {
        val encrypted = client {
            """{"_id":"c1","channel_type":"Group","name":"g","recipients":["a","b"],
                "encryption":{"algorithm":"m.megolm.v1.aes-sha2","enabled_by":"a"}}"""
        }
        val plain = client { """{"_id":"c1","channel_type":"Group","name":"g","recipients":["a","b"]}""" }
        val guild = client { """{"_id":"c1","channel_type":"TextChannel","name":"t","server":"s"}""" }

        assertEquals(true, EncryptedReplies.isEncrypted("acc", "c1", encrypted))
        assertEquals(false, EncryptedReplies.isEncrypted("acc", "c1", plain))
        assertEquals(false, EncryptedReplies.isEncrypted("acc", "c1", guild))
    }

    @Test
    fun unknownWhenTheServerCannotBeAsked() = runTest {
        // the caller then sends through the job, which checks again: no plaintext by guess
        assertNull(EncryptedReplies.isEncrypted("acc", "c1", client { null }))
    }

    @Test
    fun replyTextIsTakenOnce() {
        EncryptedReplies.putBack("r1", "secret")
        assertEquals("secret", EncryptedReplies.take("r1"))
        assertNull(EncryptedReplies.take("r1"))
    }
}
