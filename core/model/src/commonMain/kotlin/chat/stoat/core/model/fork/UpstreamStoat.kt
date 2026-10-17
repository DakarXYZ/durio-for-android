package chat.stoat.core.model.fork

/**
 * The upstream Stoat service (stoat.chat, formerly revolt.chat). This file is the only place
 * upstream addresses may appear in app and core sources (plan AN-07; checked by
 * scripts/check-upstream-hosts.sh, allowlist in scripts/upstream-hosts.allowlist).
 *
 * Our builds have no default server. These are used only for an account the user added on the
 * upstream server (allowed without push, O12): its geo/age gate, discover and community links
 * keep working there (Q39) and are off for every other server.
 */
object UpstreamStoat {
    private val API_HOSTS = setOf("api.stoat.chat", "api.revolt.chat")

    const val WEB_APP = "https://stoat.chat"
    const val BETA_WEB_APP = "https://beta.stoat.chat"
    const val INVITES = "https://stt.gg"
    const val MARKETING = "https://stoat.chat"
    const val SUPPORT = "https://support.stoat.chat"
    const val CHANGELOG = "https://changelog.stoat.chat"
    const val GEO = "https://geo.revolt.chat/?client=android"
    const val HEALTH = "https://health.revolt.chat/api/health"
    const val DISCOVER = "https://stt.gg/discover/servers?embedded=true"
    const val DISCOVER_HOST = "rvlt.gg"
    const val TESTERS_INVITE = "https://stt.gg/Testers"

    fun isUpstream(endpoints: ServerEndpoints?): Boolean {
        val api = endpoints?.api ?: return false
        val host = api.substringAfter("://").substringBefore('/').substringBefore(':')
        return host in API_HOSTS
    }

    val isActive: Boolean
        get() = isUpstream(ActiveServer.currentOrNull)

    /** Upstream's `GET /` does not name its short invite host; restore the links it always had. */
    fun withUpstreamLinks(endpoints: ServerEndpoints): ServerEndpoints =
        if (isUpstream(endpoints)) endpoints.copy(webApp = WEB_APP, invites = INVITES) else endpoints
}
