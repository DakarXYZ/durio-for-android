package chat.stoat.fork.polls

import chat.stoat.core.model.fork.Poll
import chat.stoat.core.model.fork.PollAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PollLogicTest {
    private fun poll(multiple: Boolean = false) = Poll(
        question = "Where?",
        answers = listOf(
            PollAnswer("0", "Park", listOf("me", "a")),
            PollAnswer("1", "Cafe", listOf("b")),
            PollAnswer("2", "Online"),
        ),
        multiple = multiple,
    )

    @Test
    fun singleChoicePicksOrClears() {
        assertEquals(listOf("0"), PollLogic.picks(poll(), "me"))
        assertEquals(listOf("1"), PollLogic.toggle(poll(), listOf("0"), "1"))
        assertEquals(emptyList<String>(), PollLogic.toggle(poll(), listOf("0"), "0"))
    }

    @Test
    fun multipleChoiceToggles() {
        assertEquals(listOf("0", "2"), PollLogic.toggle(poll(multiple = true), listOf("0"), "2"))
        assertEquals(emptyList<String>(), PollLogic.toggle(poll(multiple = true), listOf("0"), "0"))
    }

    @Test
    fun samePicksIgnoresOrder() {
        assertTrue(PollLogic.samePicks(listOf("0", "2"), listOf("2", "0")))
        assertFalse(PollLogic.samePicks(listOf("0"), listOf("0", "2")))
    }

    @Test
    fun picksApplyLocally() {
        val after = PollLogic.withPicks(poll(), "me", listOf("2"))
        assertEquals(listOf("a"), after.answers[0].votes)
        assertEquals(listOf("me"), after.answers[2].votes)
    }

    @Test
    fun percentOfAllVotes() {
        assertEquals(3, PollLogic.voters(poll()))
        assertEquals(67, PollLogic.percent(poll(), "0"))
        assertEquals(33, PollLogic.percent(poll(), "1"))
        assertEquals(0, PollLogic.percent(poll(), "2"))
        // one voter picking all three answers: a third each, not 100% each
        val all = Poll("q", listOf(PollAnswer("0", "x", listOf("a")), PollAnswer("1", "y", listOf("a")), PollAnswer("2", "z", listOf("a"))), multiple = true)
        assertEquals(1, PollLogic.voters(all))
        assertEquals(3, PollLogic.votes(all))
        assertEquals(33, PollLogic.percent(all, "0"))
        assertEquals(0, PollLogic.percent(Poll("q", listOf(PollAnswer("0", "x"))), "0"))
    }
}
