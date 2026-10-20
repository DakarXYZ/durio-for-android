package chat.stoat.core.model.fork

import kotlinx.serialization.Serializable

/**
 * A poll on a message (dur.io servers). Updates come as a `MessageUpdate` carrying the whole
 * poll; answer ids are "0".."n-1" in the order shown.
 */
@Serializable
data class Poll(
    val question: String,
    val answers: List<PollAnswer> = emptyList(),
    val multiple: Boolean = false,
    val closed: Boolean = false,
)

@Serializable
data class PollAnswer(
    val id: String,
    val text: String,
    val votes: List<String> = emptyList(),
)
