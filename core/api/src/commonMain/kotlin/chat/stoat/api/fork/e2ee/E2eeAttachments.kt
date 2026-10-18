package chat.stoat.api.fork.e2ee

import chat.stoat.api.StoatJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Encryption of attachment blobs (plan AN-28; `EncryptedFile` v2, C8): AES-256-CTR with a fresh
 * key and IV per file, SHA-256 of the ciphertext. Key, IV and hash come from the core; Kotlin
 * only applies AES-CTR for seeking in media (Q37). Stateless, no account needed.
 */
interface E2eeAttachmentCrypto {
    /** A whole file or thumbnail: the ciphertext (same size) and its `EncryptedFile` JSON. */
    fun encrypt(data: ByteArray): E2eeEncryptedBlob

    /**
     * Checks the hash of the **whole** ciphertext first, then decrypts.
     * @throws E2eeException.AttachmentHashMismatch the server swapped or cut the blob: show nothing
     */
    fun decrypt(ciphertext: ByteArray, fileJson: String): ByteArray

    /** Streaming check of a downloaded blob (large media): `update` per chunk, then `finish`. */
    fun verifier(fileJson: String): E2eeAttachmentVerifier
}

interface E2eeAttachmentVerifier : AutoCloseable {
    fun update(chunk: ByteArray)

    /** @throws E2eeException.AttachmentHashMismatch */
    fun finish()
}

/** [fileJson] is secret (the key): kept in memory only, never logged. */
class E2eeEncryptedBlob(val ciphertext: ByteArray, val fileJson: String) {
    override fun toString() = "E2eeEncryptedBlob(${ciphertext.size} bytes)"
}

/** Key and IV of an `EncryptedFile`, for AES-CTR with seeking (media playback, AN-28). */
class E2eeFileKey(val key: ByteArray, val iv: ByteArray) {
    override fun toString() = "E2eeFileKey"

    companion object {
        /** @throws IllegalArgumentException not an `EncryptedFile` v2 with a 32-byte key and 16-byte IV */
        @OptIn(ExperimentalEncodingApi::class)
        fun parse(fileJson: String): E2eeFileKey {
            val file = StoatJson.parseToJsonElement(fileJson).jsonObject
            require(file["v"]?.jsonPrimitive?.content == "v2") { "Not an EncryptedFile v2" }
            val k = file["key"]?.jsonObject?.get("k")?.jsonPrimitive?.content ?: throw IllegalArgumentException("No key")
            val iv = file["iv"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("No IV")
            val key = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(k)
            val ivBytes = Base64.Default.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(iv)
            require(key.size == 32 && ivBytes.size == 16) { "Bad key or IV size" }
            return E2eeFileKey(key, ivBytes)
        }
    }
}

/** The process-wide [E2eeAttachmentCrypto], installed by the app over the core (AN-28). */
object E2eeAttachments {
    @Volatile
    var crypto: E2eeAttachmentCrypto? = null

    /** Autumn path of an encrypted blob: always `file.bin`, the real name is in the payload. */
    fun downloadPath(filesBase: String, id: String) = "$filesBase/attachments/$id/$ENCRYPTED_NAME"

    /** `AutumnResource.tag` of an attachment taken from a decrypted payload (AN-28). */
    const val DECRYPTED_TAG = "attachments.e2ee"

    const val ENCRYPTED_NAME = "file.bin"
    const val ENCRYPTED_TYPE = "application/octet-stream"
}
