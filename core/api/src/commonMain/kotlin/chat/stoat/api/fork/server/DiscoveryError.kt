package chat.stoat.api.fork.server

import kotlinx.io.IOException

/** Why server discovery failed (plan AN-06); each case maps to its own message on the login screen. */
sealed class DiscoveryError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The input is not a host name or URL. */
    class InvalidAddress(val input: String) : DiscoveryError("Not a server address: $input")

    /** Plain http (or ws) outside debug builds. */
    class Insecure(val url: String) : DiscoveryError("Server address must use https: $url")

    /** DNS, connection refused, timeouts. */
    class NoConnection(cause: Throwable?) : DiscoveryError("Could not reach the server", cause)

    /** TLS handshake or certificate validation failed. */
    class Certificate(cause: Throwable?) : DiscoveryError("Server certificate is not trusted", cause)

    /** Reachable, but nothing there looks like a Stoat API. */
    class NotStoatServer(val url: String) : DiscoveryError("Not a Stoat server: $url")
}

/**
 * Platform exceptions (certificates, unresolved hosts) are not visible from common code;
 * null means "not a network error", which is rethrown as is.
 */
internal expect fun platformDiscoveryError(e: Throwable): DiscoveryError?

internal fun Throwable.toDiscoveryError(): DiscoveryError? =
    platformDiscoveryError(this) ?: if (this is IOException) DiscoveryError.NoConnection(this) else null
