package chat.stoat.core.model.fork

import chat.stoat.core.model.schemas.Channel
import chat.stoat.core.model.schemas.Message
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val thread = ThreadInfo(parent = "P", message = "M", owner = "U")
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun archivedByHandOrAfterAWeek() {
        val now = 100 * day
        assertFalse(ThreadInfo.isArchived(thread, now - 6 * day, now))
        assertTrue(ThreadInfo.isArchived(thread, now - 8 * day, now))
        assertTrue(ThreadInfo.isArchived(thread.copy(archived = true), now, now))
    }

    @Test
    fun parsesThreadChannel() {
        val channel = json.decodeFromString(
            Channel.serializer(),
            """{"_id":"T","channel_type":"TextChannel","server":"S","name":"Talk",
               "thread":{"parent":"P","message":"M","owner":"U","message_count":3}}""",
        )
        assertEquals("P", channel.thread?.parent)
        assertEquals(3, channel.thread?.messageCount)
        assertFalse(channel.thread!!.archived)

        val merged = channel.mergeWithPartial(Channel(thread = channel.thread!!.copy(archived = true)))
        assertTrue(merged.thread!!.archived)
        assertEquals("Talk", merged.name)
    }

    @Test
    fun messageThreadAndSpoilerClear() {
        val message = json.decodeFromString(
            Message.serializer(),
            """{"_id":"M","channel":"P","thread":"T","spoiler":{"kind":"nsfw","by":"U"},"pinned":true}""",
        )
        assertEquals("T", message.thread)
        assertEquals(MessageSpoiler.KIND_NSFW, message.spoiler?.kind)

        val cleared = message.withCleared(listOf("Thread", "Spoiler"))
        assertNull(cleared.thread)
        assertNull(cleared.spoiler)
        assertEquals(true, cleared.pinned)
    }
}
