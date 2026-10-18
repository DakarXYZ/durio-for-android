package chat.stoat.api.fork.e2ee

import chat.stoat.api.fork.routes.EncryptedAttachmentException
import chat.stoat.api.fork.routes.downloadEncryptedAttachment
import chat.stoat.api.fork.routes.uploadEncryptedAttachment
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// autumn for encrypted files (plan AN-28, BE-14)
class EncryptedAttachmentRouteTest {
    private val log = mutableListOf<String>()
    private var status = HttpStatusCode.OK
    private val http = HttpClient(
        MockEngine { request ->
            val part = if (request.body.contentType?.contentType == "multipart") "multipart" else "none"
            log += "${request.method.value} ${request.url} $part"
            respond(if (request.method.value == "GET") "CIPHER" else """{"id":"01FILE"}""", status)
        }
    )

    @Test
    fun uploadIsMarkedEncrypted() = runTest {
        val id = uploadEncryptedAttachment(byteArrayOf(1, 2, 3), http = http, filesBase = "https://f.example")
        assertEquals("01FILE", id)
        assertEquals("POST https://f.example/attachments?encrypted=true multipart", log.single())
    }

    @Test
    fun downloadUsesTheBlobPath() = runTest {
        assertContentEquals("CIPHER".encodeToByteArray(), downloadEncryptedAttachment("01FILE", http, "https://f.example"))
        assertTrue(log.single().startsWith("GET https://f.example/attachments/01FILE/file.bin"))

        status = HttpStatusCode.NotFound
        val e = assertFailsWith<EncryptedAttachmentException> { downloadEncryptedAttachment("x", http, "https://f.example") }
        assertEquals(404, e.status)
    }

    @Test
    fun fileKeyParsesAnEncryptedFile() {
        val key = E2eeFileKey.parse(
            """{"v":"v2","key":{"kty":"oct","key_ops":["encrypt","decrypt"],"alg":"A256CTR",
               "k":"-mikHFIZ5ePUAWRkT57QdmoPnuCJxFx0V63wc88R5jQ","ext":true},
               "iv":"enhdwylIfxsAAAAAAAAAAA","hashes":{"sha256":"Ax+q/5xA52toS81BJMB/2D9Ubmom9kSq4NJaOHIxZIo"}}"""
        )
        assertEquals(32, key.key.size)
        assertEquals(16, key.iv.size)
        assertEquals("E2eeFileKey", key.toString())
        assertFailsWith<IllegalArgumentException> { E2eeFileKey.parse("""{"v":"v1"}""") }
    }
}
