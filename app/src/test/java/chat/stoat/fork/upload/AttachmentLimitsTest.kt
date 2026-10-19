package chat.stoat.fork.upload

import org.junit.Assert.assertEquals
import org.junit.Test

// Attachment caps: the server's limit, for encrypted files at most a quarter of the heap
class AttachmentLimitsTest {
    private val mib = 1024L * 1024

    @Test
    fun plainFilesFollowTheServer() {
        assertEquals(100_000_000L, AttachmentLimits.limit(100_000_000L, encrypted = false, heapBytes = 128 * mib))
    }

    @Test
    fun encryptedFilesStayWithinAQuarterOfTheHeap() {
        // a 512 MiB heap sends the server's full 100 MB
        assertEquals(100_000_000L, AttachmentLimits.limit(100_000_000L, encrypted = true, heapBytes = 512 * mib))
        // 256 MiB: 64 MiB, plaintext and ciphertext together half the heap
        assertEquals(64 * mib, AttachmentLimits.limit(100_000_000L, encrypted = true, heapBytes = 256 * mib))
        // a smaller server limit wins
        assertEquals(20_000_000L, AttachmentLimits.limit(20_000_000L, encrypted = true, heapBytes = 512 * mib))
    }
}
