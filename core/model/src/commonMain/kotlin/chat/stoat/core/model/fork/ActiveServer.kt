package chat.stoat.core.model.fork

import chat.stoat.core.model.data.STOAT_BASE
import chat.stoat.core.model.data.STOAT_FILES
import chat.stoat.core.model.data.STOAT_PROXY
import chat.stoat.core.model.data.STOAT_WEBSOCKET
import chat.stoat.core.model.data.STOAT_WEB_APP
import kotlin.concurrent.Volatile

/**
 * Server of the active account (plan AN-07). Read on every request and in composition, so a
 * volatile field rather than Compose state: switching accounts restarts the activity (AN-09).
 */
object ActiveServer {
    // TODO(AN-07): no default server, filled from the stored account before the first request
    @Volatile
    private var endpoints: ServerEndpoints? = ServerEndpoints(
        api = STOAT_BASE,
        ws = STOAT_WEBSOCKET,
        files = STOAT_FILES,
        proxy = STOAT_PROXY,
        webApp = STOAT_WEB_APP,
    )

    val currentOrNull: ServerEndpoints?
        get() = endpoints

    val current: ServerEndpoints
        get() = endpoints ?: error("No server selected")

    fun set(endpoints: ServerEndpoints?) {
        this.endpoints = endpoints
    }
}
