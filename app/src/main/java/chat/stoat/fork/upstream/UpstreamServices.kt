package chat.stoat.fork.upstream

import chat.stoat.BuildConfig
import chat.stoat.core.model.fork.UpstreamStoat

/**
 * Which services of the upstream server the app may contact (plan AN-07, AN-17, Q39).
 *
 * On our servers none of them: geo would hand the user's IP to upstream, the others are about
 * upstream's own service. An account on the upstream server keeps the geo/age gate, discover, the
 * testers' server and the support links; health alerts and the changelog are opt-in at build time.
 * Geo is queried without the session token (AN-15) and its gate applies only to that account.
 */
object UpstreamServices {
    val geo: Boolean
        get() = UpstreamStoat.isActive

    val health: Boolean
        get() = BuildConfig.FORK_UPSTREAM_HEALTH && UpstreamStoat.isActive

    val gazette: Boolean
        get() = BuildConfig.FORK_UPSTREAM_GAZETTE && UpstreamStoat.isActive

    val discover: Boolean
        get() = UpstreamStoat.isActive

    val testers: Boolean
        get() = UpstreamStoat.isActive

    /** Upstream's knowledge base (webhook help and the like); our servers have none. */
    val support: Boolean
        get() = UpstreamStoat.isActive
}
