package chat.stoat.core.model.fork

import kotlinx.serialization.Serializable

/**
 * Addresses of one server, as found by server discovery (plan AN-06, 00-architecture §6).
 *
 * [api] is canonical (`canonicalApiUrl`): it is part of the account key and of push routing.
 * [files] and [proxy] are empty when the server has autumn or january disabled.
 * [invites] is a dedicated short-link host; null means `<webApp>/invite`.
 */
@Serializable
data class ServerEndpoints(
    val api: String,
    val ws: String,
    val files: String,
    val proxy: String,
    val webApp: String,
    val invites: String? = null,
    val livekit: Boolean = false,
)
