package chat.stoat.fork.messages

import org.junit.Assert.assertEquals
import org.junit.Test

class EmojiFrequencyTest {
    @Test
    fun mostUsedFirstTiesByRecency() {
        var counts = emptyMap<String, Int>()
        listOf("👍", "😂", "👍", "❤️", "😂", "👍").forEach { counts = EmojiFrequency.counted(counts, it) }
        assertEquals(mapOf("👍" to 3, "😂" to 2, "❤️" to 1), counts)

        // 😂 and ❤️ tie at 2: the more recent one first
        counts = EmojiFrequency.counted(counts, "❤️")
        assertEquals(listOf("👍", "❤️", "😂"), EmojiFrequency.frequent(counts, recent = listOf("❤️", "😂", "👍")))
        assertEquals(listOf("👍"), EmojiFrequency.frequent(counts, emptyList(), count = 1))
    }

    @Test
    fun keepsTheMostUsed() {
        var counts = (1..EmojiFrequency.KEEP).associate { "e$it" to 2 }
        counts = EmojiFrequency.counted(counts, "new")
        assertEquals(EmojiFrequency.KEEP, counts.size)
        assertEquals(false, counts.containsKey("new"))
    }

    @Test
    fun storedAndRead() {
        val counts = mapOf("👍" to 3, "01ABCDEFGHJKMNPQRSTVWXYZ00" to 1)
        assertEquals(counts, EmojiFrequency.decode(EmojiFrequency.encode(counts)))
        assertEquals(emptyMap<String, Int>(), EmojiFrequency.decode("broken\nx\t-1"))
    }
}
