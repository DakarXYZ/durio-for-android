package chat.stoat.fork.messages

/**
 * How often the user picks each emoji, for the "Frequently used" section of the emoji picker.
 * Pure functions; [RecentEmoji] keeps the counts on the device.
 */
object EmojiFrequency {
    /** How many emoji keep a count */
    const val KEEP = 64

    /** The same emoji again within this long is one use (the picker and the reaction both report it) */
    const val REPEAT_MS = 1_500L

    /** [counts] with one more use of [emoji], the least used dropped past [KEEP] */
    fun counted(counts: Map<String, Int>, emoji: String): Map<String, Int> =
        (counts + (emoji to (counts[emoji] ?: 0) + 1)).entries
            .sortedByDescending { it.value }
            .take(KEEP)
            .associate { it.key to it.value }

    /** The most used emoji first; ties go to the more recent one (by [recent], newest first) */
    fun frequent(counts: Map<String, Int>, recent: List<String>, count: Int = 18): List<String> =
        counts.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenBy { recent.indexOf(it.key).let { index -> if (index < 0) Int.MAX_VALUE else index } }
            )
            .take(count)
            .map { it.key }

    /** One `emoji<TAB>count` per line */
    fun encode(counts: Map<String, Int>): String =
        counts.entries.joinToString("\n") { "${it.key}\t${it.value}" }

    fun decode(text: String?): Map<String, Int> =
        text.orEmpty().lineSequence().mapNotNull { line ->
            val tab = line.lastIndexOf('\t')
            if (tab <= 0) return@mapNotNull null
            val count = line.substring(tab + 1).toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            line.substring(0, tab) to count
        }.toMap()
}
