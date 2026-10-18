package chat.stoat.fork.e2ee

import chat.stoat.fork.e2ee.media.EncryptedShare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

// Shared decrypted copies of encrypted attachments (AN-44): safe names, gone after ten minutes
class EncryptedShareTest {
    @Test
    fun sharedFileNamesFromTheSenderAreSafe() {
        assertEquals("photo.jpg", EncryptedShare.safeName("photo.jpg"))
        assertEquals("passwd", EncryptedShare.safeName("../../etc/passwd"))
        assertEquals("evil.txt", EncryptedShare.safeName("C:\\x\\evil.txt"))
        assertEquals("hidden", EncryptedShare.safeName(".hidden"))
        assertEquals("ab.txt", EncryptedShare.safeName("a\u0000b\n.txt"))
        assertEquals("file", EncryptedShare.safeName("../"))
        assertEquals(100, EncryptedShare.safeName("x".repeat(500) + ".pdf").length)
        assertTrue(EncryptedShare.safeName("x".repeat(500) + ".pdf").endsWith(".pdf"))
    }

    @Test
    fun sharedCopiesGoAfterTenMinutes() {
        val root = Files.createTempDirectory("share").toFile()
        try {
            val now = System.currentTimeMillis()
            val fresh = root.resolve("fresh").apply { mkdirs(); resolve("a.jpg").writeText("x") }
            val stale = root.resolve("stale").apply { mkdirs(); resolve("b.jpg").writeText("y") }
            stale.setLastModified(now - EncryptedShare.TTL_MS - 1)
            fresh.setLastModified(now)
            EncryptedShare.sweep(root, now)
            assertTrue(fresh.exists())
            assertFalse(stale.exists())
            // logout: all of them
            EncryptedShare.sweep(root, Long.MAX_VALUE, 0)
            assertFalse(fresh.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
