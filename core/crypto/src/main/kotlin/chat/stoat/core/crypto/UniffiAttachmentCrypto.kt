package chat.stoat.core.crypto

import chat.stoat.api.fork.e2ee.E2eeAttachmentCrypto
import chat.stoat.api.fork.e2ee.E2eeAttachmentVerifier
import chat.stoat.api.fork.e2ee.E2eeEncryptedBlob
import uniffi.stoat_crypto.AttachmentEncryptor
import uniffi.stoat_crypto.AttachmentVerifier
import uniffi.stoat_crypto.attachmentChunkSize
import uniffi.stoat_crypto.decryptAttachment

/**
 * [E2eeAttachmentCrypto] over the core's attachment streams (plan AN-28, CR-10). The whole file
 * is in memory anyway (upload is `ByteArray` multipart), so encryption fills one array chunk by
 * chunk instead of handing hundreds of megabytes over JNA at once.
 */
object UniffiAttachmentCrypto : E2eeAttachmentCrypto {
    private val chunk: Int by lazy { attachmentChunkSize().toInt() }

    override fun encrypt(data: ByteArray): E2eeEncryptedBlob = core {
        AttachmentEncryptor().use { encryptor ->
            val out = ByteArray(data.size)
            var offset = 0
            while (offset < data.size) {
                val end = minOf(offset + chunk, data.size)
                encryptor.update(data.copyOfRange(offset, end)).copyInto(out, offset)
                offset = end
            }
            E2eeEncryptedBlob(out, encryptor.finish())
        }
    }

    override fun decrypt(ciphertext: ByteArray, fileJson: String): ByteArray = core {
        decryptAttachment(ciphertext, fileJson)
    }

    override fun verifier(fileJson: String): E2eeAttachmentVerifier {
        val verifier = core { AttachmentVerifier(fileJson) }
        return object : E2eeAttachmentVerifier {
            override fun update(chunk: ByteArray) = core { verifier.update(chunk) }

            override fun finish() = core { verifier.finish().close() }

            override fun close() = verifier.close()
        }
    }
}
