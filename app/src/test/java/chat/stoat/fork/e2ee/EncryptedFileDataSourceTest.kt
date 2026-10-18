package chat.stoat.fork.e2ee

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import chat.stoat.api.fork.e2ee.E2eeFileKey
import chat.stoat.fork.e2ee.media.EncryptedFileDataSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

// Seeking in encrypted media (plan AN-28): any position decrypts to the same plaintext
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class EncryptedFileDataSourceTest {
    private val plain = Random(3).nextBytes(10_000)
    private val key = Random(4).nextBytes(32)

    // like EncryptedFile v2: the low 64 bits of the IV are zero
    private val iv = Random(5).nextBytes(8) + ByteArray(8)

    private fun encryptedFile(): File {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return File.createTempFile("ctr", ".bin").apply {
            writeBytes(cipher.doFinal(plain))
            deleteOnExit()
        }
    }

    private fun read(source: EncryptedFileDataSource, position: Long, length: Long = C.LENGTH_UNSET.toLong()): ByteArray {
        source.open(DataSpec.Builder().setUri(Uri.parse("file:///x")).setPosition(position).setLength(length).build())
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(777)
        while (true) {
            val n = source.read(buffer, 0, buffer.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, n)
        }
        source.close()
        return out.toByteArray()
    }

    @Test
    fun everyPositionDecryptsTheSameBytes() {
        val source = EncryptedFileDataSource(encryptedFile(), E2eeFileKey(key, iv))
        assertArrayEquals(plain, read(source, 0))
        for (position in listOf(1L, 15, 16, 17, 4095, 9999)) {
            assertArrayEquals("from $position", plain.copyOfRange(position.toInt(), plain.size), read(source, position))
        }
        assertArrayEquals(plain.copyOfRange(100, 150), read(source, 100, 50))
    }

    @Test
    fun counterAddsAcrossBytes() {
        val start = ByteArray(15) + byteArrayOf(0xFF.toByte())
        val next = EncryptedFileDataSource.counterAt(start, 1)
        assertEquals(1.toByte(), next[14])
        assertEquals(0.toByte(), next[15])
        val wrapped = EncryptedFileDataSource.counterAt(ByteArray(16) { 0xFF.toByte() }, 1)
        assertArrayEquals(ByteArray(16), wrapped)
    }
}
