package chat.stoat.api.fork.server

import chat.stoat.core.model.fork.ServerEndpoints
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET <api>/` as seen by server discovery (plan AN-06). Unlike `routes.misc.Root` every field
 * is optional, so an old or trimmed-down server gives a clear error instead of a
 * SerializationException.
 */
@Serializable
data class ServerRootInfo(
    val revolt: String? = null,
    val ws: String? = null,
    val app: String? = null,
    val features: Features? = null,
) {
    @Serializable
    data class Features(
        val captcha: Captcha? = null,
        val email: Boolean? = null,
        @SerialName("invite_only") val inviteOnly: Boolean? = null,
        val autumn: Service? = null,
        val january: Service? = null,
        val livekit: Toggle? = null,
        val e2ee: E2ee? = null,
    )

    @Serializable
    data class Captcha(
        val enabled: Boolean? = null,
        val key: String? = null,
    )

    @Serializable
    data class Service(
        val enabled: Boolean? = null,
        val url: String? = null,
    )

    @Serializable
    data class Toggle(
        val enabled: Boolean? = null,
    )

    // BE-21; absent on upstream servers
    @Serializable
    data class E2ee(
        val enabled: Boolean? = null,
        @SerialName("envelope_versions") val envelopeVersions: List<Int>? = null,
    )

    val e2eeEnabled: Boolean
        get() = features?.e2ee?.enabled == true
}

/** `/.well-known/stoat` served by Caddy in self-hosted (SH-05). */
@Serializable
data class WellKnownStoat(
    val api: String? = null,
    val name: String? = null,
)

/** Result of server discovery: the addresses plus what the login screen shows (AN-10). */
data class DiscoveredServer(
    val endpoints: ServerEndpoints,
    val info: ServerRootInfo,
    val name: String? = null,
)
