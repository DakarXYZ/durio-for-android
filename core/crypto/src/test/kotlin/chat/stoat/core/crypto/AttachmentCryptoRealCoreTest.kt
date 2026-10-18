package chat.stoat.core.crypto

import chat.stoat.api.fork.e2ee.E2eeException
import chat.stoat.api.fork.e2ee.E2eeFileKey
import org.junit.Before
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Attachments through the real core (plan AN-28, CR-10, Q37): round trip, a swapped blob is
// refused, and JCA AES-CTR with the key/IV of the EncryptedFile reads the same plaintext — the
// path media playback takes
class AttachmentCryptoRealCoreTest {
    @Before
    fun hostLibrary() = HostLibrary.assume()

    private val data = Random(7).nextBytes(300_000)

    @Test
    fun roundTripAndSwappedBlob() {
        val blob = UniffiAttachmentCrypto.encrypt(data)
        assertEquals(data.size, blob.ciphertext.size)
        assertTrue("\"v\":\"v2\"" in blob.fileJson)
        assertContentEquals(data, UniffiAttachmentCrypto.decrypt(blob.ciphertext, blob.fileJson))

        val swapped = blob.ciphertext.copyOf().also { it[1000] = (it[1000] + 1).toByte() }
        assertFailsWith<E2eeException.AttachmentHashMismatch> { UniffiAttachmentCrypto.decrypt(swapped, blob.fileJson) }
        val cut = blob.ciphertext.copyOf(blob.ciphertext.size - 1)
        assertFailsWith<E2eeException.AttachmentHashMismatch> { UniffiAttachmentCrypto.decrypt(cut, blob.fileJson) }
    }

    @Test
    fun streamingVerifierChecksTheWholeBlob() {
        val blob = UniffiAttachmentCrypto.encrypt(data)
        UniffiAttachmentCrypto.verifier(blob.fileJson).use { v ->
            var offset = 0
            while (offset < blob.ciphertext.size) {
                val end = minOf(offset + 65_536, blob.ciphertext.size)
                v.update(blob.ciphertext.copyOfRange(offset, end))
                offset = end
            }
            v.finish()
        }
        assertFailsWith<E2eeException.AttachmentHashMismatch> {
            UniffiAttachmentCrypto.verifier(blob.fileJson).use { v ->
                v.update(blob.ciphertext.copyOf(1000))
                v.finish()
            }
        }
    }

    @Test
    fun jcaCtrReadsWhatTheCoreEncrypted() {
        val blob = UniffiAttachmentCrypto.encrypt(data)
        val key = E2eeFileKey.parse(blob.fileJson)
        // EncryptedFile v2: the low 64 bits of the IV are zero
        assertTrue(key.iv.copyOfRange(8, 16).all { it == 0.toByte() })
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.key, "AES"), IvParameterSpec(key.iv))
        assertContentEquals(data, cipher.doFinal(blob.ciphertext))
    }
}
