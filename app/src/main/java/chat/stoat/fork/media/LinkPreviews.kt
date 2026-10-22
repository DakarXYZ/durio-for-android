package chat.stoat.fork.media

import chat.stoat.core.model.schemas.Embed

/**
 * Links that have a preview (like Discord): a URL standing alone in the message text is not
 * shown when an embed of it is drawn as a card or media; the embed stands for it.
 */
object LinkPreviews {
    private val standaloneUrl = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)

    /** An embed drawn as media or a card: an image, a video, or a website with a title or description. */
    fun replacesLink(embed: Embed): Boolean = when (embed.type) {
        "Image", "Video" -> true
        "Website" -> !embed.title.isNullOrBlank() || !embed.description.isNullOrBlank()
        else -> false
    }

    /** Drops the `#fragment` and one trailing `/`; the scheme and host are case-insensitive. */
    fun normalise(url: String): String {
        val noFragment = url.substringBefore('#')
        val trimmed = if (noFragment.endsWith("/")) noFragment.dropLast(1) else noFragment
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd < 0) return trimmed
        val hostStart = schemeEnd + 3
        val hostEnd = trimmed.indexOfAny(charArrayOf('/', '?'), hostStart).let { if (it < 0) trimmed.length else it }
        return trimmed.substring(0, hostEnd).lowercase() + trimmed.substring(hostEnd)
    }

    /**
     * The text to render: [content] without the lines that are only a URL (optionally in `<>`)
     * which an embed in [embeds] replaces. URLs inside a sentence or a code block stay.
     * A message that is a single URL with a single such embed loses the URL even when the two
     * differ (canonical links, youtu.be, archived previews whose media is on the file server).
     * Empty when nothing remains; [content] itself when nothing is hidden.
     */
    fun visibleContent(content: String?, embeds: List<Embed>?): String {
        if (content.isNullOrEmpty()) return ""
        val shown = embeds.orEmpty().filter(::replacesLink)
        if (shown.size == 1 && standaloneUrl.matches(content.trim().removeSurrounding("<", ">"))) return ""
        val previewed = shown
            .flatMap { listOfNotNull(it.originalURL, it.url) }
            .map(::normalise)
            .toSet()
        if (previewed.isEmpty()) return content

        val lines = content.split("\n")
        val kept = ArrayList<String>(lines.size)
        var inFence = false
        var hidden = false
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("```")) inFence = !inFence
            val url = trimmed.removeSurrounding("<", ">")
            if (!inFence && standaloneUrl.matches(url) && normalise(url) in previewed) {
                hidden = true
                continue
            }
            kept.add(line)
        }
        if (!hidden) return content
        return kept.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }.joinToString("\n")
    }
}
