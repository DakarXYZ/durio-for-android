package chat.stoat.fork.media

import chat.stoat.core.model.schemas.AutumnResource
import chat.stoat.core.model.schemas.Embed
import chat.stoat.core.model.schemas.Message
import chat.stoat.core.model.schemas.Metadata
import chat.stoat.core.model.schemas.WebHook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureGridTest {
    private fun image(id: String, filename: String = "$id.png") =
        AutumnResource(id = id, filename = filename, metadata = Metadata(type = "Image", width = 10, height = 10))

    private fun video(id: String) =
        AutumnResource(id = id, filename = "$id.mp4", metadata = Metadata(type = "Video", width = 10, height = 10))

    private fun message(
        id: String,
        vararg attachments: AutumnResource,
        author: String = "u",
        tail: Boolean = true,
        content: String? = null,
    ) = Message(id = id, author = author, attachments = attachments.toList(), tail = tail, content = content)

    private fun panelSizes(plan: Map<String, MessagePictures>, id: String): List<Int>? =
        (plan[id] as? MessagePictures.Panels)?.panels?.map { it.size }

    @Test
    fun columnsAndRowsByCount() {
        assertEquals(listOf(1, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3), (1..12).map { PictureGrid.columns(it) })
        assertEquals(listOf(1, 1, 2, 2, 2, 2, 3, 3, 3, 4, 4, 4), (1..12).map { PictureGrid.rows(it) })
    }

    @Test
    fun picturesAreImagesNotVideosOrSpoilers() {
        val m = message("m", image("a"), video("v"), image("s", "SPOILER_s.png"), image("g", "g.gif"))
        assertEquals(listOf("a", "g"), PictureGrid.pictures(m).map { it.attachment?.id })
    }

    @Test
    fun imageEmbedsOfALinkOnlyMessageArePictures() {
        val embed = Embed(type = "Image", url = "https://cdn.test/a.png", width = 1, height = 1)
        val linkOnly = Message(id = "m", content = "https://cdn.test/a.png", embeds = listOf(embed))
        assertEquals(listOf(embed), PictureGrid.pictures(linkOnly).map { it.embed })
        assertTrue(PictureGrid.pictures(linkOnly.copy(content = "look https://cdn.test/a.png")).isEmpty())
    }

    @Test
    fun oneMessageWithOnePictureIsDrawnAsBefore() {
        assertTrue(PictureGrid.plan(listOf(message("m", image("a"), tail = false))).isEmpty())
    }

    @Test
    fun severalPicturesOfOneMessageMakeAPanel() {
        val plan = PictureGrid.plan(listOf(message("m", image("a"), image("b"), tail = false)))
        assertEquals(listOf(2), panelSizes(plan, "m"))
    }

    @Test
    fun followingPictureOnlyMessagesJoin() {
        val plan = PictureGrid.plan(
            listOf(
                message("m1", image("a"), tail = false, content = "holiday"),
                message("m2", image("b")),
                message("m3", image("c"), image("d")),
            )
        )
        assertEquals(listOf(4), panelSizes(plan, "m1"))
        assertEquals(MessagePictures.Merged, plan["m2"])
        assertEquals(MessagePictures.Merged, plan["m3"])
        val owners = (plan["m1"] as MessagePictures.Panels).panels.flatten().map { it.messageId }
        assertEquals(listOf("m1", "m2", "m3", "m3"), owners)
    }

    @Test
    fun aRunBreaks() {
        val head = message("m1", image("a"), tail = false)
        val breakers = listOf(
            message("x", image("b"), author = "other"),
            message("x", image("b"), tail = false),
            message("x", image("b"), content = "text"),
            message("x", image("b"), video("v")),
            message("x", image("b")).copy(replies = listOf("r")),
            message("x", image("b")).copy(reactions = mapOf("e" to listOf("u"))),
            message("x", image("b")).copy(edited = "2026-01-01"),
            message("x", image("b")).copy(pinned = true),
            message("x", image("b")).copy(webhook = WebHook(name = "Other")),
            message("x", image("b")).copy(embeds = listOf(Embed(type = "Website", title = "t"))),
        )
        for (breaker in breakers) {
            assertFalse(breaker.toString(), PictureGrid.canJoin(head, breaker))
            val plan = PictureGrid.plan(listOf(head, breaker))
            assertNull(plan["m1"])
            assertFalse(plan["x"] is MessagePictures.Merged)
        }
        // a date divider (null) breaks too
        assertTrue(PictureGrid.plan(listOf(head, null, message("m2", image("b")))).isEmpty())
    }

    @Test
    fun aBreakStartsANewRun() {
        val plan = PictureGrid.plan(
            listOf(
                message("m1", image("a"), tail = false),
                message("m2", image("b")),
                message("m3", image("c"), tail = false, author = "other"),
                message("m4", image("d"), author = "other"),
            )
        )
        assertEquals(listOf(2), panelSizes(plan, "m1"))
        assertEquals(listOf(2), panelSizes(plan, "m3"))
        assertEquals(MessagePictures.Merged, plan["m4"])
    }

    @Test
    fun webhookMessagesJoinUnderTheSameName() {
        val hook = WebHook(name = "Old Friend")
        val plan = PictureGrid.plan(
            listOf(
                message("m1", image("a"), tail = false).copy(webhook = hook),
                message("m2", image("b")).copy(webhook = hook),
            )
        )
        assertEquals(listOf(2), panelSizes(plan, "m1"))
    }

    @Test
    fun aPanelHoldsTwelve() {
        val head = message("m0", *Array(10) { image("a$it") }, tail = false)
        val next = message("m1", *Array(5) { image("b$it") })
        assertEquals(listOf(12, 3), panelSizes(PictureGrid.plan(listOf(head, next)), "m0"))
        val many = message("m", *Array(25) { image("c$it") }, tail = false)
        assertEquals(listOf(12, 12, 1), panelSizes(PictureGrid.plan(listOf(many)), "m"))
    }
}
