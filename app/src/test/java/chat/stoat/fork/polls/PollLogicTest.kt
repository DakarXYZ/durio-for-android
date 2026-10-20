package chat.stoat.fork.polls

import chat.stoat.core.model.fork.Poll
import chat.stoat.core.model.fork.PollAnswer
import org.junit.Assert.assertEquals
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
    fun singleChoicePicksOrTakesBack() {
        assertEquals(listOf("0"), PollLogic.picks(poll(), "me"))
        assertEquals(listOf("1"), PollLogic.nextPicks(poll(), "me", "1"))
        assertEquals(emptyList<String>(), PollLogic.nextPicks(poll(), "me", "0"))
    }

    @Test
    fun multipleChoiceToggles() {
        assertEquals(listOf("0", "2"), PollLogic.nextPicks(poll(multiple = true), "me", "2"))
        assertEquals(emptyList<String>(), PollLogic.nextPicks(poll(multiple = true), "me", "0"))
    }

    @Test
    fun picksApplyLocally() {
        val after = PollLogic.withPicks(poll(), "me", listOf("2"))
        assertEquals(listOf("a"), after.answers[0].votes)
        assertEquals(listOf("me"), after.answers[2].votes)
    }

    @Test
    fun percentOfVoters() {
        assertEquals(3, PollLogic.voters(poll()))
        assertEquals(67, PollLogic.percent(poll(), "0"))
        assertEquals(33, PollLogic.percent(poll(), "1"))
        assertEquals(0, PollLogic.percent(poll(), "2"))
        // one voter with two picks counts once
        val both = PollLogic.withPicks(poll(multiple = true), "a", listOf("0", "1"))
        assertEquals(3, PollLogic.voters(both))
        assertEquals(0, PollLogic.percent(Poll("q", listOf(PollAnswer("0", "x"))), "0"))
    }
}
