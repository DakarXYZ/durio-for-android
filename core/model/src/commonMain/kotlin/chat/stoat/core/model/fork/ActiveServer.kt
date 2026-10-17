package chat.stoat.core.model.fork

import kotlin.concurrent.Volatile

/**
 * Server of the active account (plan AN-07). Read on every request and in composition, so a
 * volatile field rather than Compose state: switching accounts restarts the activity (AN-09).
 *
 * There is no default server: the app fills it from the stored account before the first request.
 */
object ActiveServer {
    @Volatile
    private var endpoints: ServerEndpoints? = null

    val currentOrNull: ServerEndpoints?
        get() = endpoints

    val current: ServerEndpoints
        get() = endpoints ?: error("No server selected")

    fun set(endpoints: ServerEndpoints?) {
        this.endpoints = endpoints
    }
}
