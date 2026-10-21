package chat.stoat.fork.polls

import chat.stoat.core.model.fork.Poll

/** What a poll looks like to one user; pure, for the card and its tests. */
object PollLogic {
    /** Answers [selfId] picked. */
    fun picks(poll: Poll, selfId: String?): List<String> =
        if (selfId == null) emptyList() else poll.answers.filter { selfId in it.votes }.map { it.id }

    /**
     * The picks (not sent yet) after tapping [answerId]: single choice picks it (or none when it
     * was the pick), multiple choice toggles it.
     */
    fun toggle(poll: Poll, picks: List<String>, answerId: String): List<String> =
        if (poll.multiple) {
            if (answerId in picks) picks - answerId else picks + answerId
        } else {
            if (answerId in picks) emptyList() else listOf(answerId)
        }

    /** Whether two lists of picks are the same answers. */
    fun samePicks(a: List<String>, b: List<String>): Boolean = a.size == b.size && a.toSet() == b.toSet()

    /** The poll as it will be once [picks] are [selfId]'s votes (shown until the server answers). */
    fun withPicks(poll: Poll, selfId: String, picks: List<String>): Poll = poll.copy(
        answers = poll.answers.map { answer ->
            val others = answer.votes - selfId
            answer.copy(votes = if (answer.id in picks) others + selfId else others)
        }
    )

    /** People who voted at all (one person picking two answers counts once). */
    fun voters(poll: Poll): Int = poll.answers.flatMap { it.votes }.toSet().size

    /** Votes on all answers: the base of the shares, so they add up to 100 with several picks. */
    fun votes(poll: Poll): Int = poll.answers.sumOf { it.votes.size }

    /** Share of all votes an answer got, 0..100, rounded. */
    fun percent(poll: Poll, answerId: String): Int {
        val votes = votes(poll)
        if (votes == 0) return 0
        val got = poll.answers.firstOrNull { it.id == answerId }?.votes?.size ?: 0
        return Math.round(got * 100f / votes)
    }
}
