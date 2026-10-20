package chat.stoat.fork.polls

import chat.stoat.core.model.fork.Poll

/** What a poll looks like to one user; pure, for the card and its tests. */
object PollLogic {
    /** Answers [selfId] picked. */
    fun picks(poll: Poll, selfId: String?): List<String> =
        if (selfId == null) emptyList() else poll.answers.filter { selfId in it.votes }.map { it.id }

    /**
     * The picks after tapping [answerId]: single choice picks it (or takes the vote back when it
     * was the pick), multiple choice toggles it.
     */
    fun nextPicks(poll: Poll, selfId: String?, answerId: String): List<String> {
        val current = picks(poll, selfId)
        return if (poll.multiple) {
            if (answerId in current) current - answerId else current + answerId
        } else {
            if (current == listOf(answerId)) emptyList() else listOf(answerId)
        }
    }

    /** The poll as it will be once [picks] are [selfId]'s votes (shown until the server answers). */
    fun withPicks(poll: Poll, selfId: String, picks: List<String>): Poll = poll.copy(
        answers = poll.answers.map { answer ->
            val others = answer.votes - selfId
            answer.copy(votes = if (answer.id in picks) others + selfId else others)
        }
    )

    /** People who voted at all (one person picking two answers counts once). */
    fun voters(poll: Poll): Int = poll.answers.flatMap { it.votes }.toSet().size

    /** Share of voters who picked an answer, 0..100, rounded. */
    fun percent(poll: Poll, answerId: String): Int {
        val voters = voters(poll)
        if (voters == 0) return 0
        val votes = poll.answers.firstOrNull { it.id == answerId }?.votes?.size ?: 0
        return Math.round(votes * 100f / voters)
    }
}
