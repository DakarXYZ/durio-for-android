package chat.stoat.core.model.fork

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What makes a server text channel a thread of another channel (dur.io servers). A thread is not
 * in the server's channel list and takes the permissions of its parent channel.
 */
@Serializable
data class ThreadInfo(
    /** Id of the channel the thread belongs to */
    val parent: String,
    /** Id of the message in the parent channel the thread hangs off */
    val message: String,
    /** Id of the user who started the thread */
    val owner: String,
    /** Archived by hand; a new message brings it back */
    val archived: Boolean = false,
    @SerialName("message_count")
    val messageCount: Long = 0,
) {
    companion object {
        /** A thread nothing was said in for this long counts as archived */
        const val ARCHIVE_AFTER_MS: Long = 7L * 24 * 60 * 60 * 1000

        /**
         * Whether a thread counts as archived: by hand, or its last activity is older than a week.
         * @param lastActivityMs time of its last message (or of its creation), epoch ms
         */
        fun isArchived(thread: ThreadInfo, lastActivityMs: Long, nowMs: Long): Boolean =
            thread.archived || nowMs - lastActivityMs > ARCHIVE_AFTER_MS
    }
}

/** What a moderator hid a message behind (dur.io servers) */
@Serializable
data class MessageSpoiler(
    /** [KIND_SPOILER] or [KIND_NSFW] */
    val kind: String,
    /** Id of the user who hid it */
    val by: String? = null,
) {
    companion object {
        const val KIND_SPOILER = "spoiler"
        const val KIND_NSFW = "nsfw"
    }
}
