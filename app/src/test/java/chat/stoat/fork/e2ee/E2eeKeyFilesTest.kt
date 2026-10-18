package chat.stoat.fork.e2ee

import chat.stoat.api.fork.e2ee.E2eeException
import chat.stoat.api.fork.e2ee.E2eeKeyExport
import chat.stoat.api.fork.e2ee.E2eeKeyImport
import chat.stoat.api.fork.e2ee.E2eeRoomKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.LocalDate

// Key files (CR-21): passphrase rules, the 64 MiB cap before reading it all, error mapping,
// passphrases wiped, waiting messages decrypted again after an import
class E2eeKeyFilesTest {
    private class Core : TestE2eeCore() {
        var exportError: E2eeException? = null
        var seenPassphrase: String? = null
        var seenChannels: List<String>? = null

        override suspend fun exportRoomKeys(passphrase: ByteArray, channelIds: List<String>): E2eeKeyExport {
            seenPassphrase = passphrase.decodeToString()
            seenChannels = channelIds
            exportError?.let { throw it }
            return E2eeKeyExport("-----BEGIN MEGOLM SESSION DATA-----", 4)
        }

        override suspend fun importRoomKeys(file: String, passphrase: ByteArray): E2eeKeyImport {
            seenPassphrase = passphrase.decodeToString()
            if (!file.startsWith("-----BEGIN")) throw E2eeException.InvalidKeyFile()
            if (passphrase.decodeToString() != "right one") throw E2eeException.WrongPassphrase()
            return E2eeKeyImport(3, 2, 1, listOf(E2eeRoomKey("c1", "s1")))
        }
    }

    @Test
    fun passphraseStrength() {
        assertEquals(PassphraseStrength.TooShort, E2eeKeyFiles.strength(""))
        assertEquals(PassphraseStrength.TooShort, E2eeKeyFiles.strength("abcdefghi"))
        assertEquals(PassphraseStrength.Weak, E2eeKeyFiles.strength("aaaaaaaaaaaa"))
        assertEquals(PassphraseStrength.Weak, E2eeKeyFiles.strength("abcdefghij"))
        assertEquals(PassphraseStrength.Fair, E2eeKeyFiles.strength("abcdefgh12"))
        assertEquals(PassphraseStrength.Strong, E2eeKeyFiles.strength("correct horse battery"))
        assertEquals(PassphraseStrength.Strong, E2eeKeyFiles.strength("Tr0ub4dor&3x"))
        // characters, not UTF-16 units
        assertEquals(PassphraseStrength.TooShort, E2eeKeyFiles.strength("😀😀😀😀😀"))
    }

    @Test
    fun exportNeedsTheSameFairPassphraseTwice() {
        assertFalse(E2eeKeyFiles.canExport("correct horse battery", "correct horse batter"))
        assertFalse(E2eeKeyFiles.canExport("abcdefghij", "abcdefghij"))
        assertTrue(E2eeKeyFiles.canExport("correct horse battery", "correct horse battery"))
    }

    @Test
    fun fileNameIsATextFile() {
        assertEquals("dur.io-keys-2026-10-18.txt", E2eeKeyFiles.fileName(LocalDate.of(2026, 10, 18)))
    }

    @Test
    fun exportWipesThePassphraseAndExplainsTooManyKeys() = runTest {
        val core = Core()
        val passphrase = "correct horse battery".encodeToByteArray()
        val ready = E2eeKeyFiles.export(core, passphrase, listOf("c1"))
        assertTrue(ready is E2eeKeyFiles.Exported.Ready)
        assertEquals("correct horse battery", core.seenPassphrase)
        assertEquals(listOf("c1"), core.seenChannels)
        assertArrayEquals(ByteArray(passphrase.size), passphrase)

        // the core's InvalidKeyFile on export: the file would pass 64 MiB
        core.exportError = E2eeException.InvalidKeyFile()
        val again = "correct horse battery".encodeToByteArray()
        assertEquals(E2eeKeyFiles.Exported.Failed(KeyFileFailure.TooManySessions), E2eeKeyFiles.export(core, again, emptyList()))
        assertArrayEquals(ByteArray(again.size), again)
    }

    @Test
    fun importTellsWrongPassphraseFromABrokenFile() = runTest {
        val core = Core()
        val redecrypted = mutableListOf<E2eeRoomKey>()
        val file = "-----BEGIN MEGOLM SESSION DATA-----".encodeToByteArray()

        val wrong = "wrong one".encodeToByteArray()
        assertEquals(
            E2eeKeyFiles.Imported.Failed(KeyFileFailure.WrongPassphrase),
            E2eeKeyFiles.import(core, file, wrong) { redecrypted += it },
        )
        assertArrayEquals(ByteArray(wrong.size), wrong)

        assertEquals(
            E2eeKeyFiles.Imported.Failed(KeyFileFailure.InvalidFile),
            E2eeKeyFiles.import(core, "hello".encodeToByteArray(), "right one".encodeToByteArray()) { redecrypted += it },
        )
        assertTrue(redecrypted.isEmpty())

        val right = "right one".encodeToByteArray()
        val done = E2eeKeyFiles.import(core, file, right) { redecrypted += it }
        assertTrue(done is E2eeKeyFiles.Imported.Done)
        assertEquals(2, (done as E2eeKeyFiles.Imported.Done).result.imported)
        // messages waiting for these keys are decrypted again
        assertEquals(listOf(E2eeRoomKey("c1", "s1")), redecrypted)
        assertArrayEquals(ByteArray(right.size), right)
    }

    @Test
    fun readingStopsPastTheCap() {
        assertArrayEquals(ByteArray(10) { 1 }, E2eeKeyFiles.readCapped(ByteArrayInputStream(ByteArray(10) { 1 }), max = 10))
        assertNull(E2eeKeyFiles.readCapped(ByteArrayInputStream(ByteArray(11)), max = 10))

        // a stream that never ends is not read past the cap
        var served = 0L
        val endless = object : java.io.InputStream() {
            override fun read(): Int = 0.also { served++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                served += len
                return len
            }
        }
        assertNull(E2eeKeyFiles.readCapped(endless, max = 1_000_000))
        assertTrue(served <= 1_000_000 + 64 * 1024)
        assertEquals(64 * 1024 * 1024, E2eeKeyFiles.MAX_FILE_BYTES)
    }
}
