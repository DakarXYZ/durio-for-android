package chat.stoat.fork.archive

import chat.stoat.core.model.fork.MessageImport
import chat.stoat.core.model.schemas.Message
import chat.stoat.core.model.schemas.WebHook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchivedMessagesTest {
    private val archived = Message(
        id = "m",
        author = "w",
        webhook = WebHook(avatar = "file1", name = "Old Friend"),
        imported = MessageImport("discord"),
    )

    @Test
    fun importedIsTold() {
        assertTrue(ArchivedMessages.isImported(archived))
        assertFalse(ArchivedMessages.isImported(archived.copy(imported = null)))
    }

    @Test
    fun webhookAvatarAndPlaceholder() {
        assertEquals("file1", ArchivedMessages.webhookAvatar(archived)?.id)
        assertNull(ArchivedMessages.webhookAvatar(archived.copy(webhook = WebHook(name = "x"))))
        assertEquals("w", ArchivedMessages.webhookAuthorPlaceholder(archived)?.id)
        assertNull(ArchivedMessages.webhookAuthorPlaceholder(archived.copy(webhook = null)))
    }
}
