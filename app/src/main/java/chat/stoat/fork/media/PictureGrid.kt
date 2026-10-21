package chat.stoat.fork.media

import chat.stoat.core.model.schemas.AutumnResource
import chat.stoat.core.model.schemas.Embed
import chat.stoat.core.model.schemas.Message

/** A picture of a message: an image attachment, or an image embed of a link-only message. */
data class GridPicture(
    val messageId: String,
    val attachment: AutumnResource? = null,
    val embed: Embed? = null,
)

/** How a message shows its pictures when they are drawn as a grid. */
sealed interface MessagePictures {
    /** The message draws these panels (at most [PictureGrid.MAX_PER_PANEL] pictures each). */
    data class Panels(val panels: List<List<GridPicture>>) : MessagePictures

    /** The message's pictures are in an earlier message's panel; it shows nothing itself. */
    data object Merged : MessagePictures
}

/**
 * Several pictures in a row from one author are drawn as one panel of thumbnails: the image
 * attachments of a message, joined by the following picture-only messages of the same group.
 */
object PictureGrid {
    const val MAX_PER_PANEL = 12

    /** 2 side by side; 3–4 in a 2x2 grid; 5 and more in 3 columns. */
    fun columns(count: Int): Int = when {
        count <= 1 -> 1
        count <= 4 -> 2
        else -> 3
    }

    fun rows(count: Int): Int = if (count <= 0) 0 else (count + columns(count) - 1) / columns(count)

    /** Images (GIFs too, not videos); a spoiler stays out of the grid so it is not revealed. */
    fun isPictureAttachment(attachment: AutumnResource): Boolean =
        attachment.metadata?.type == "Image" &&
            attachment.id != null &&
            attachment.deleted != true &&
            attachment.filename?.startsWith("SPOILER_") != true

    /** The pictures of [message] that a grid may hold, in their order. */
    fun pictures(message: Message): List<GridPicture> {
        val id = message.id ?: return emptyList()
        if (message.encrypted != null || message.system != null) return emptyList()
        val attachments = message.attachments.orEmpty()
            .filter(::isPictureAttachment)
            .map { GridPicture(id, attachment = it) }
        val embeds = if (LinkPreviews.visibleContent(message.content, message.embeds).isBlank()) {
            message.embeds.orEmpty()
                .filter { it.type == "Image" && !it.url.isNullOrBlank() }
                .map { GridPicture(id, embed = it) }
        } else {
            emptyList()
        }
        return attachments + embeds
    }

    /**
     * Whether [message] joins the panel of [previous], the message right before it: the same
     * author in the same group, and nothing but pictures (no text, files, reply or reactions).
     */
    fun canJoin(previous: Message, message: Message, pictures: List<GridPicture> = pictures(message)): Boolean =
        pictures.isNotEmpty() &&
            message.tail == true &&
            message.author != null && message.author == previous.author &&
            message.webhook?.name == previous.webhook?.name &&
            message.masquerade == previous.masquerade &&
            message.system == null &&
            message.encrypted == null &&
            message.poll == null &&
            message.replies.isNullOrEmpty() &&
            message.reactions.isNullOrEmpty() &&
            message.interactions?.reactions.isNullOrEmpty() &&
            message.edited == null &&
            message.pinned != true &&
            LinkPreviews.visibleContent(message.content, message.embeds).isBlank() &&
            message.attachments.orEmpty().all(::isPictureAttachment) &&
            message.embeds.orEmpty().all { it.type == "Image" || it.type == "None" }

    /**
     * The grids of [messages], oldest first; a null breaks a run (e.g. a date divider). Only
     * messages that draw a grid or are merged into one are in the map: a message with a single
     * picture of its own and no followers is drawn as before.
     */
    fun plan(messages: List<Message?>): Map<String, MessagePictures> {
        val result = HashMap<String, MessagePictures>()
        var headId: String? = null
        var run = ArrayList<GridPicture>()
        var joined = false
        var previous: Message? = null

        fun flush() {
            val id = headId
            if (id != null && (joined || run.size > 1)) {
                result[id] = MessagePictures.Panels(run.chunked(MAX_PER_PANEL))
            }
            headId = null
            run = ArrayList()
            joined = false
        }

        for (message in messages) {
            val id = message?.id
            if (message == null || id == null) {
                flush()
                previous = null
                continue
            }
            val pictures = pictures(message)
            val last = previous
            if (headId != null && last != null && canJoin(last, message, pictures)) {
                run.addAll(pictures)
                joined = true
                result[id] = MessagePictures.Merged
            } else {
                flush()
                if (pictures.isNotEmpty()) {
                    headId = id
                    run.addAll(pictures)
                }
            }
            previous = message
        }
        flush()
        return result
    }
}
