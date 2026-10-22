package chat.stoat.fork.messages

import chat.stoat.StoatApplication
import chat.stoat.persistence.KVStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

/**
 * The emoji the user reacted with, newest first (unicode or a custom emoji ULID), for the quick
 * reactions of a message. Kept on the device, as the web client keeps its own.
 */
object RecentEmoji {
    private const val KEY = "durio/recentEmoji"
    private const val COUNTS_KEY = "durio/emojiCounts"
    private const val KEEP = 20

    /** Shown while the user has reacted with fewer emoji than the quick bar holds */
    private val DEFAULTS = listOf("👍", "❤️", "😂", "😮", "😢")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val kv by lazy { KVStorage(StoatApplication.instance) }
    private val recent = MutableStateFlow<List<String>>(emptyList())
    private val uses = MutableStateFlow<Map<String, Int>>(emptyMap())
    private var loaded = false
    private var lastCounted: Pair<String, Long>? = null

    val emoji: StateFlow<List<String>> = recent.asStateFlow()

    /** How often each emoji was picked (reactions and the picker), for the picker's favourites */
    val counts: StateFlow<Map<String, Int>> = uses.asStateFlow()

    /** Reads the stored list once; the quick bar calls it when it opens. */
    fun load() {
        if (loaded) return
        loaded = true
        scope.launch {
            val stored = kv.get(KEY)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
            recent.update { current -> (current + stored).distinct().take(KEEP) }
            val storedCounts = EmojiFrequency.decode(kv.get(COUNTS_KEY))
            uses.update { current ->
                (storedCounts.keys + current.keys).associateWith { (storedCounts[it] ?: 0) + (current[it] ?: 0) }
            }
        }
    }

    /** Count a use of an emoji (an insertion in the composer, or a reaction) */
    fun count(emoji: String) {
        load()
        val now = System.currentTimeMillis()
        val last = lastCounted
        if (last != null && last.first == emoji && now - last.second < EmojiFrequency.REPEAT_MS) return
        lastCounted = emoji to now
        val updated = uses.updateAndGet { EmojiFrequency.counted(it, emoji) }
        scope.launch { kv.set(COUNTS_KEY, EmojiFrequency.encode(updated)) }
    }

    /** Remember an emoji the user reacted with. */
    fun use(emoji: String) {
        load()
        count(emoji)
        val list = recent.updateAndGet { listOf(emoji) + it.filter { e -> e != emoji } }
        scope.launch { kv.set(KEY, list.take(KEEP).joinToString("\n")) }
    }

    /** The quick reactions: the most recent emoji, topped up with defaults. */
    fun quick(recent: List<String>, count: Int = 5): List<String> =
        (recent + DEFAULTS).distinct().take(count)
}
