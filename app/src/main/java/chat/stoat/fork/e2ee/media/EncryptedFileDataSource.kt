package chat.stoat.fork.e2ee.media

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import chat.stoat.api.fork.e2ee.E2eeFileKey
import java.io.File
import java.io.RandomAccessFile
import java.math.BigInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Plays an encrypted video or audio from its **verified** ciphertext on disk (plan AN-28): AES-
 * 256-CTR through JCA (Q37), decrypting from any position — on `open(position = P)` the counter
 * is `IV + P / 16` (128-bit big-endian) and the first `P % 16` bytes are dropped. No plaintext
 * file is ever written; the ciphertext in `cacheDir/e2ee/` is not secret.
 *
 * Only for a file whose SHA-256 the core has checked: CTR is malleable, a swapped blob must
 * never reach the media codecs.
 */
@OptIn(UnstableApi::class)
class EncryptedFileDataSource(private val file: File, private val key: E2eeFileKey) : BaseDataSource(false) {
    private var input: RandomAccessFile? = null
    private var cipher: Cipher? = null
    private var uri: Uri? = null
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val length = file.length()
        val position = dataSpec.position
        if (position > length) throw java.io.EOFException()

        val raf = RandomAccessFile(file, "r")
        val blockStart = position - position % BLOCK
        raf.seek(blockStart)
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.key, "AES"), IvParameterSpec(counterAt(key.iv, blockStart / BLOCK)))
        // the bytes of the first block before the position
        val skip = (position - blockStart).toInt()
        if (skip > 0) {
            val head = ByteArray(skip)
            raf.readFully(head)
            c.update(head)
        }
        input = raf
        cipher = c
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else length - position
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val raf = input ?: return C.RESULT_END_OF_INPUT
        val want = minOf(length.toLong(), remaining).toInt()
        val chunk = ByteArray(want)
        val read = raf.read(chunk, 0, want)
        if (read <= 0) return C.RESULT_END_OF_INPUT
        // CTR is a stream mode: as many plaintext bytes as ciphertext bytes
        val written = cipher!!.update(chunk, 0, read, buffer, offset)
        remaining -= read
        bytesTransferred(written)
        return written
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        cipher = null
        try {
            input?.close()
        } finally {
            input = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(private val file: File, private val key: E2eeFileKey) : DataSource.Factory {
        override fun createDataSource(): DataSource = EncryptedFileDataSource(file, key)
    }

    companion object {
        private const val BLOCK = 16L

        /** [iv] + [blocks] as a 128-bit big-endian counter (what AES-CTR increments). */
        internal fun counterAt(iv: ByteArray, blocks: Long): ByteArray {
            val sum = BigInteger(1, iv).add(BigInteger.valueOf(blocks))
                .mod(BigInteger.ONE.shiftLeft(128))
            val bytes = sum.toByteArray()
            val out = ByteArray(16)
            val n = minOf(bytes.size, 16)
            bytes.copyInto(out, 16 - n, bytes.size - n, bytes.size)
            return out
        }
    }
}
