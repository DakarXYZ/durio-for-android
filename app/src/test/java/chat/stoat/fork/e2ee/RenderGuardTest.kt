package chat.stoat.fork.e2ee

import chat.stoat.api.fork.e2ee.DecryptionStatus
import chat.stoat.api.fork.e2ee.E2eeVerification
import chat.stoat.core.model.fork.EncryptedEnvelope
import chat.stoat.core.model.schemas.Message
import chat.stoat.fork.e2ee.ui.noticeFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Render guard of Message.kt (plan AN-46): an encrypted message whose plaintext is not there
// never shows its body — whatever reached the screen, the server's placeholder stays hidden
class RenderGuardTest {
    private val placeholder = Message(
        id = "01MESSAGE00000000000000000",
        content = "🔒 Encrypted message",
        encrypted = EncryptedEnvelope(sessionId = "s", deviceId = "d", ciphertext = "c"),
    )

    @Test
    fun everyStateWithoutPlaintextHidesTheBody() {
        val states = listOf(
            null,
            DecryptionStatus.Pending,
            DecryptionStatus.Suspicious,
        ) + DecryptionStatus.Failed.Reason.entries.map { DecryptionStatus.Failed(it) }
        for (status in states) {
            val notice = noticeFor(placeholder, status)
            assertTrue("$status", notice?.hidesBody == true)
        }
        assertTrue(noticeFor(placeholder, null)!!.shimmer)
        assertTrue(noticeFor(placeholder, DecryptionStatus.Suspicious)!!.warning)
    }

    @Test
    fun decryptedShowsTheBodyWithAShieldLineWhenUnproven() {
        assertNull(noticeFor(placeholder, DecryptionStatus.Decrypted(E2eeVerification.Unverified, 0)))
        assertNull(noticeFor(placeholder, DecryptionStatus.Decrypted(E2eeVerification.Verified, 0)))
        val backup = noticeFor(placeholder, DecryptionStatus.Decrypted(E2eeVerification.Unauthenticated, 0))!!
        assertFalse(backup.hidesBody)
        assertFalse(backup.warning)
        assertTrue(noticeFor(placeholder, DecryptionStatus.Decrypted(E2eeVerification.UnknownDevice, 0))!!.warning)
    }

    @Test
    fun plainMessagesAreLeftAlone() {
        assertNull(noticeFor(placeholder.copy(encrypted = null), null))
        assertEquals(null, noticeFor(Message(id = "x", content = "hi"), DecryptionStatus.Pending))
    }
}
