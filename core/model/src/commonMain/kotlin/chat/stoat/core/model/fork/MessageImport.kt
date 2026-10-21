package chat.stoat.core.model.fork

import kotlinx.serialization.Serializable

/**
 * Marks a message imported into a dur.io server from an archive (e.g. Discord). Such a message
 * is authored by a webhook standing in for the original author (no user exists for it); its
 * name and avatar are in the message's `webhook`.
 */
@Serializable
data class MessageImport(
    val source: String,
) {
    companion object {
        const val SOURCE_DISCORD = "discord"
    }
}
