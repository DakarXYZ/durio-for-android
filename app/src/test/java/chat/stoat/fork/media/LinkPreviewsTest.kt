package chat.stoat.fork.media

import chat.stoat.core.model.schemas.Embed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPreviewsTest {
    private val site = Embed(
        type = "Website",
        url = "https://example.com/page",
        originalURL = "https://Example.com/page/",
        title = "A page",
    )
    private val image = Embed(type = "Image", url = "https://cdn.test/a.png", width = 10, height = 10)

    @Test
    fun whichEmbedsReplaceTheLink() {
        assertTrue(LinkPreviews.replacesLink(site))
        assertTrue(LinkPreviews.replacesLink(image))
        assertTrue(LinkPreviews.replacesLink(Embed(type = "Video", url = "https://v.test/a.mp4")))
        assertFalse(LinkPreviews.replacesLink(site.copy(title = " ")))
        // a post with no title, only its author (site name) and text
        assertTrue(LinkPreviews.replacesLink(site.copy(title = null, siteName = "someone", description = "a post")))
        assertFalse(LinkPreviews.replacesLink(site.copy(title = null, siteName = "someone", description = " ")))
        assertFalse(LinkPreviews.replacesLink(Embed(type = "Text", title = "x")))
        assertFalse(LinkPreviews.replacesLink(Embed(type = "None")))
    }

    @Test
    fun normalisesFragmentSlashAndHost() {
        assertEquals("https://example.com/Path", LinkPreviews.normalise("HTTPS://Example.COM/Path/#top"))
        assertEquals("https://example.com", LinkPreviews.normalise("https://example.com/"))
        assertEquals("https://example.com?Q=1", LinkPreviews.normalise("https://EXAMPLE.com?Q=1"))
    }

    @Test
    fun aMessageThatIsOnlyThePreviewedLinkHasNoText() {
        assertEquals("", LinkPreviews.visibleContent("  https://example.com/page#x  ", listOf(site)))
        assertEquals("", LinkPreviews.visibleContent("<https://cdn.test/a.png>", listOf(image)))
    }

    @Test
    fun aLinkAloneOnItsLineIsHidden() {
        val content = "look at this\n\nhttps://example.com/page\nnice, right?"
        assertEquals(
            "look at this\n\nnice, right?",
            LinkPreviews.visibleContent(content, listOf(site)),
        )
        assertEquals(
            "look at this",
            LinkPreviews.visibleContent("look at this\n  <https://cdn.test/a.png>  ", listOf(image, site)),
        )
    }

    @Test
    fun aLinkInASentenceOrCodeStays() {
        val sentence = "see https://example.com/page for more"
        assertEquals(sentence, LinkPreviews.visibleContent(sentence, listOf(site)))
        val code = "code:\n```\nhttps://example.com/page\n```"
        assertEquals(code, LinkPreviews.visibleContent(code, listOf(site)))
    }

    @Test
    fun nothingIsHiddenWithoutAMatchingShownEmbed() {
        val content = "first\nhttps://example.com/page"
        assertEquals(content, LinkPreviews.visibleContent(content, null))
        assertEquals(content, LinkPreviews.visibleContent(content, listOf(site.copy(title = null))))
        assertEquals(
            "first",
            LinkPreviews.visibleContent(content, listOf(site.copy(title = null, description = "a post"))),
        )
        assertEquals(content, LinkPreviews.visibleContent(content, listOf(image)))
        assertEquals("", LinkPreviews.visibleContent(null, listOf(site)))
    }

    @Test
    fun aSingleLinkWithASingleShownEmbedIsHiddenEvenIfTheyDiffer() {
        val video = Embed(type = "Video", url = "https://host.test/autumn/attachments/abc")
        assertEquals("", LinkPreviews.visibleContent("https://tenor.com/view/cat-123", listOf(video)))
        assertEquals("", LinkPreviews.visibleContent(" <https://youtu.be/xyz> ", listOf(site)))
        // two shown embeds, or text besides the link: only matching links are hidden
        val single = "https://youtu.be/xyz"
        assertEquals(single, LinkPreviews.visibleContent(single, listOf(site, image)))
        val withText = "watch\nhttps://youtu.be/xyz"
        assertEquals(withText, LinkPreviews.visibleContent(withText, listOf(site)))
        // an embed that is not shown does not count
        assertEquals("", LinkPreviews.visibleContent(single, listOf(site, Embed(type = "None"))))
        // no shown embed at all: the link stays
        assertEquals(single, LinkPreviews.visibleContent(single, listOf(Embed(type = "Website"))))
    }
}
