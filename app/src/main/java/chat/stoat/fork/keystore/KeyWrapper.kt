package chat.stoat.fork.keystore

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets (session tokens now, the crypto store's data keys in stage 4) with a
 * key that never leaves the device (plan AN-08, AN-22). An interface so unit tests can use a
 * software key: Robolectric has no AndroidKeyStore.
 */
interface KeyWrapper {
    fun wrap(plain: ByteArray): ByteArray

    /** @throws UnwrapException if the blob is damaged or the key is gone (AN-42). */
    fun unwrap(blob: ByteArray): ByteArray
}

class UnwrapException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * AES-256-GCM with the IV chosen by the provider (Keystore keys require randomised IVs).
 * Blob format, versioned from day one: `version (1 byte) | IV (12 bytes) | ciphertext + tag`.
 */
class AesGcmKeyWrapper(private val key: () -> SecretKey) : KeyWrapper {
    override fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "Unexpected IV size ${iv.size}" }
        return byteArrayOf(VERSION) + iv + cipher.doFinal(plain)
    }

    override fun unwrap(blob: ByteArray): ByteArray {
        if (blob.size < 1 + IV_BYTES + TAG_BYTES || blob[0] != VERSION) {
            throw UnwrapException("Unknown or damaged blob")
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(TAG_BYTES * 8, blob, 1, IV_BYTES)
            )
            cipher.doFinal(blob, 1 + IV_BYTES, blob.size - 1 - IV_BYTES)
        } catch (e: GeneralSecurityException) {
            throw UnwrapException("Could not unwrap", e)
        }
    }

    companion object {
        const val VERSION: Byte = 1
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
    }
}
