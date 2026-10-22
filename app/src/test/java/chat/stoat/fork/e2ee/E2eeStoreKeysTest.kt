package chat.stoat.fork.e2ee

import chat.stoat.fork.keystore.AesGcmKeyWrapper
import chat.stoat.fork.keystore.KeyWrapper
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import javax.crypto.KeyGenerator
import kotlin.test.assertFailsWith

// Data keys of the crypto stores (plan AN-22, AN-42); a software key stands in for the Keystore
class E2eeStoreKeysTest {
    private val dir = Files.createTempDirectory("durio-keys").toFile()
    private val deviceKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val wrapper: KeyWrapper = AesGcmKeyWrapper { deviceKey }

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun createdOnceAndStoredWrapped() {
        val keys = E2eeStoreKeys(dir, wrapper)
        val first = keys.dataKey("a")

        assertEquals(32, first.size)
        val file = keys.keyFile("a")
        assertTrue(file.isFile)
        // only the wrapped form is on disk
        assertFalse(file.readBytes().toList().windowed(32).any { it == first.toList() })

        // the same key in a new process
        assertArrayEquals(first, E2eeStoreKeys(dir, wrapper).dataKey("a"))
    }

    @Test
    fun callersGetCopies() {
        val keys = E2eeStoreKeys(dir, wrapper)
        val copy = keys.dataKey("a")
        val expected = copy.copyOf()
        copy.fill(0)
        assertArrayEquals(expected, keys.dataKey("a"))
    }

    @Test
    fun accountsHaveTheirOwnKeys() {
        val keys = E2eeStoreKeys(dir, wrapper)
        assertFalse(keys.dataKey("a").contentEquals(keys.dataKey("b")))
    }

    @Test
    fun lostDeviceKeyIsReportedNotReplaced() {
        E2eeStoreKeys(dir, wrapper).dataKey("a")
        val otherDevice = AesGcmKeyWrapper(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().let { k -> { k } })

        assertFailsWith<E2eeKeyLostException> { E2eeStoreKeys(dir, otherDevice).dataKey("a") }
        assertTrue(E2eeStoreKeys(dir, wrapper).keyFile("a").isFile)
    }

    @Test
    fun storeWithoutKeyIsLost() {
        val keys = E2eeStoreKeys(dir, wrapper)
        keys.storeDir("a").mkdirs()
        keys.storeDir("a").resolve("crypto.sqlite3").writeText("x")

        assertFailsWith<E2eeKeyLostException> { keys.dataKey("a") }
        assertFalse(keys.keyFile("a").exists())
    }

    @Test
    fun storeOfAnEarlierSessionIsTold() {
        val keys = E2eeStoreKeys(dir, wrapper)
        assertFalse(keys.madeForOtherSession("a", "S1"))

        keys.dataKey("a")
        keys.storeDir("a").mkdirs()
        keys.storeDir("a").resolve("crypto.sqlite3").writeText("x")
        // a store from before the note: this session's
        assertFalse(keys.madeForOtherSession("a", "S1"))

        keys.rememberSession("a", "S1")
        assertFalse(keys.madeForOtherSession("a", "S1"))
        // logged in again: a new session, the store stayed
        assertTrue(keys.madeForOtherSession("a", "S2"))
        assertFalse(keys.madeForOtherSession("b", "S2"))
    }

    @Test
    fun deleteRemovesTheSessionNote() {
        val keys = E2eeStoreKeys(dir, wrapper)
        keys.dataKey("a")
        keys.rememberSession("a", "S1")
        keys.delete("a")

        assertFalse(keys.sessionFile("a").exists())
        assertFalse(keys.madeForOtherSession("a", "S2"))
    }

    @Test
    fun deleteRemovesTheKey() {
        val keys = E2eeStoreKeys(dir, wrapper)
        val first = keys.dataKey("a")
        keys.delete("a")

        assertFalse(keys.keyFile("a").exists())
        assertFalse(first.contentEquals(keys.dataKey("a")))
    }
}
