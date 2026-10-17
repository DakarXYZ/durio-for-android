package chat.stoat.api.fork.http

import chat.stoat.api.StoatAPI
import chat.stoat.core.model.fork.ServerEndpoints
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.header
import io.ktor.http.Url

/**
 * Adds `x-session-token` only to requests for the account's own server (plan AN-15).
 * Upstream added it to every request, leaking the token to geo/health/changelog services,
 * embeds and, with several accounts, to the servers of other accounts.
 *
 * A token the caller set explicitly is left alone.
 */
fun SessionTokenPlugin(
    name: String,
    endpoints: () -> ServerEndpoints?,
    token: () -> String,
): ClientPlugin<Unit> = createClientPlugin(name) {
    onRequest { request, _ ->
        if (request.headers.contains(StoatAPI.TOKEN_HEADER_NAME)) return@onRequest

        val server = endpoints() ?: return@onRequest
        val value = token()
        if (value.isNotEmpty() && server.acceptsSessionToken(request.url.build())) {
            request.header(StoatAPI.TOKEN_HEADER_NAME, value)
        }
    }
}

/**
 * The token belongs to the API and the file server (autumn needs it for uploads). Both are
 * compared by scheme, host and port, so another service on the same host gets nothing.
 */
fun ServerEndpoints.acceptsSessionToken(url: Url): Boolean =
    listOf(api, files)
        .filter { it.isNotEmpty() }
        .any { Url(it).sameOrigin(url) }

private fun Url.sameOrigin(other: Url): Boolean =
    protocol == other.protocol &&
        host.equals(other.host, ignoreCase = true) &&
        port == other.port
