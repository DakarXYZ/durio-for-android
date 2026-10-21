package chat.stoat.api.fork.archive

import chat.stoat.api.StoatJson
import chat.stoat.core.model.fork.MessageImport
import chat.stoat.core.model.schemas.Message
import chat.stoat.core.model.schemas.WebHook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Messages imported from an archive (e.g. Discord): parsed, and not lost by partial updates
class ImportedMessageTest {
    @Test
    fun importedMessageParsesAndKeepsTheMarkOnUpdate() {
        val message = StoatJson.decodeFromString(
            Message.serializer(),
            """{"_id":"01ARZ3NDEKTSV4RRFFQ69G5FAV","channel":"c","author":"01HWEBHOOK0000000000000000",
               "content":"old","webhook":{"name":"Old Friend","avatar":"file1"},"pinned":true,
               "imported":{"source":"discord"}}"""
        )
        assertEquals(MessageImport(source = MessageImport.SOURCE_DISCORD), message.imported)
        assertEquals(WebHook(avatar = "file1", name = "Old Friend"), message.webhook)

        val merged = message.mergeWithPartial(Message(content = "edited"))
        assertEquals("edited", merged.content)
        assertEquals(message.imported, merged.imported)
        assertEquals(message.webhook, merged.webhook)
    }

    @Test
    fun regularMessageIsNotImported() {
        val message = StoatJson.decodeFromString(
            Message.serializer(),
            """{"_id":"m","channel":"c","author":"a","content":"hi"}"""
        )
        assertNull(message.imported)
    }
}
