package chat.stoat.fork.keystore

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.assertFailsWith

// AndroidKeyStore does not exist under Robolectric/JVM (AN-16), so the same AES-GCM code runs
// with a software key here; the Keystore key itself needs an instrumented test on a device.
class KeyWrapperTest {
    private fun softwareKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun roundTrip() {
        val wrapper = AesGcmKeyWrapper(softwareKey().let { key -> { key } })
        val token = "session-token-ÄÖ-🦦".toByteArray()

        val blob = wrapper.wrap(token)

        assertEquals(AesGcmKeyWrapper.VERSION, blob[0])
        assertEquals(1 + 12 + token.size + 16, blob.size)
        assertArrayEquals(token, wrapper.unwrap(blob))
    }

    @Test
    fun freshIvEveryTime() {
        val wrapper = AesGcmKeyWrapper(softwareKey().let { key -> { key } })
        val first = wrapper.wrap(byteArrayOf(1, 2, 3))
        val second = wrapper.wrap(byteArrayOf(1, 2, 3))
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun tamperedBlobIsRejected() {
        val wrapper = AesGcmKeyWrapper(softwareKey().let { key -> { key } })
        val blob = wrapper.wrap("token".toByteArray())
        blob[blob.size - 1] = (blob[blob.size - 1] + 1).toByte()

        assertFailsWith<UnwrapException> { wrapper.unwrap(blob) }
    }

    @Test
    fun otherKeyIsRejected() {
        // like a Keystore key lost after "clear data" (AN-42): no crash, a typed error
        val blob = AesGcmKeyWrapper(softwareKey().let { key -> { key } }).wrap("token".toByteArray())
        val other = AesGcmKeyWrapper(softwareKey().let { key -> { key } })

        assertFailsWith<UnwrapException> { other.unwrap(blob) }
    }

    @Test
    fun unknownVersionAndShortBlobsAreRejected() {
        val wrapper = AesGcmKeyWrapper(softwareKey().let { key -> { key } })
        val blob = wrapper.wrap("token".toByteArray())
        blob[0] = 2

        assertFailsWith<UnwrapException> { wrapper.unwrap(blob) }
        assertFailsWith<UnwrapException> { wrapper.unwrap(byteArrayOf(1, 0, 0)) }
    }

    @Test
    fun keystoreKeyGoneIsATypedError() {
        // Android Keystore throws ProviderException or UnrecoverableKeyException when the key
        // went away under a live handle or cannot be loaded (AN-42)
        val blob = AesGcmKeyWrapper(softwareKey().let { key -> { key } }).wrap("token".toByteArray())

        assertFailsWith<UnwrapException> { AesGcmKeyWrapper { throw java.security.ProviderException("Keystore operation failed") }.unwrap(blob) }
        assertFailsWith<UnwrapException> { AesGcmKeyWrapper { throw java.security.UnrecoverableKeyException("gone") }.unwrap(blob) }
    }
}
