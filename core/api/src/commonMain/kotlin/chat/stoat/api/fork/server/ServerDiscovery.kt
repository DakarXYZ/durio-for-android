package chat.stoat.api.fork.server

import chat.stoat.api.StoatHttp
import chat.stoat.api.buildUserAgent
import chat.stoat.core.model.fork.ServerEndpoints
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json

/**
 * Finds a server's addresses from what the user typed (plan AN-06, 00-architecture §6):
 * `https://<host>/.well-known/stoat` → `{"api": ...}` → `GET <api>/`. Without `.well-known`
 * it tries the typed path, `<host>/api` and the host itself as the API.
 *
 * Has its own client and never sends a session token: the server is not trusted yet (AN-15).
 * [allowInsecure] (debug builds only) permits plain http/ws for local development servers.
 */
class ServerDiscovery(
    engine: HttpClientEngine,
    private val allowInsecure: Boolean = false,
    userAgent: String? = null,
) : AutoCloseable {
    private val http = HttpClient(engine) {
        // Followed by hand below, at most MAX_REDIRECTS times and never to plain http
        followRedirects = false
        expectSuccess = false

        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
            connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
        }

        if (userAgent != null) {
            defaultRequest {
                header("User-Agent", userAgent)
            }
        }
    }

    suspend fun resolve(input: String): ServerEndpoints = discover(input).endpoints

    /** @throws DiscoveryError */
    suspend fun discover(input: String): DiscoveredServer {
        val address = parseAddress(input)
        try {
            return discover(address)
        } catch (e: DiscoveryError) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw e.toDiscoveryError() ?: e
        }
    }

    override fun close() {
        http.close()
    }

    private class Address(val origin: String, val path: String)

    private fun parseAddress(input: String): Address {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) {
            throw DiscoveryError.InvalidAddress(input)
        }

        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        // Ktor reads an empty host as localhost
        if (withScheme.substringAfter("://").substringBefore('/').isEmpty()) {
            throw DiscoveryError.InvalidAddress(input)
        }
        val url = runCatching { Url(withScheme) }.getOrNull()
            ?.takeIf { it.protocol == URLProtocol.HTTPS || it.protocol == URLProtocol.HTTP }
            ?.takeIf { it.host.isNotEmpty() && it.host.all { c -> c.isLetterOrDigit() || c in HOST_CHARS } }
            ?: throw DiscoveryError.InvalidAddress(input)

        val origin = canonicalApiUrl("${url.protocol.name}://${url.host}:${url.port}")
        requireSecure(origin)
        return Address(origin, url.encodedPath.trimEnd('/'))
    }

    private suspend fun discover(address: Address): DiscoveredServer {
        val wellKnown = fetchText("${address.origin}/.well-known/stoat")
            ?.let { decodeOrNull<WellKnownStoat>(it) }

        // A server that publishes .well-known means it: no guessing past it
        wellKnown?.api?.let { api ->
            val canonical = canonicalApiUrl(api)
            requireSecure(canonical)
            val root = fetchRoot(canonical) ?: throw DiscoveryError.NotStoatServer(canonical)
            return discovered(canonical, root, wellKnown.name)
        }

        val candidates = listOfNotNull(
            address.path.takeIf { it.isNotEmpty() }?.let { address.origin + it },
            "${address.origin}/api",
            address.origin,
        ).distinct()

        for (api in candidates) {
            fetchRoot(api)?.let { return discovered(api, it, name = null) }
        }

        throw DiscoveryError.NotStoatServer(address.origin)
    }

    private suspend fun fetchRoot(api: String): ServerRootInfo? =
        fetchText("$api/")
            ?.let { decodeOrNull<ServerRootInfo>(it) }
            ?.takeIf { it.revolt != null && it.ws != null }

    private fun discovered(api: String, root: ServerRootInfo, name: String?): DiscoveredServer {
        val features = root.features
        val endpoints = ServerEndpoints(
            api = api,
            ws = root.ws!!.trimEnd('/'),
            files = features?.autumn.serviceUrl(),
            proxy = features?.january.serviceUrl(),
            webApp = root.app?.trimEnd('/') ?: originOf(api),
            livekit = features?.livekit?.enabled == true,
        )
        requireSecure(endpoints.ws)
        if (endpoints.files.isNotEmpty()) requireSecure(endpoints.files)

        return DiscoveredServer(endpoints, root, name?.takeIf { it.isNotBlank() })
    }

    /** GET with a bounded redirect chain and body size; null for anything but a small 2xx. */
    private suspend fun fetchText(url: String): String? {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            val response = http.get(current)
            if (response.status.value in 300..399) {
                val location = response.headers[HttpHeaders.Location] ?: return null
                current = URLBuilder(current).takeFrom(location).buildString()
                requireSecure(current)
                return@repeat
            }

            return if (response.status.isSuccess()) response.readLimited() else null
        }
        return null
    }

    private suspend fun HttpResponse.readLimited(): String? {
        if ((contentLength() ?: 0) > MAX_BODY_BYTES) return null
        val bytes = bodyAsChannel().readRemaining(MAX_BODY_BYTES + 1L).readByteArray()
        return if (bytes.size > MAX_BODY_BYTES) null else bytes.decodeToString()
    }

    private inline fun <reified T> decodeOrNull(text: String): T? =
        runCatching { DiscoveryJson.decodeFromString<T>(text) }.getOrNull()

    private fun requireSecure(url: String) {
        if (allowInsecure) return
        if (!url.startsWith("https://") && !url.startsWith("wss://")) {
            throw DiscoveryError.Insecure(url)
        }
    }

    private fun ServerRootInfo.Service?.serviceUrl(): String =
        this?.takeIf { it.enabled != false }?.url?.trimEnd('/').orEmpty()

    private fun originOf(url: String): String {
        val authorityStart = url.indexOf("://") + 3
        val pathStart = url.indexOf('/', authorityStart)
        return if (pathStart < 0) url else url.substring(0, pathStart)
    }

    companion object {
        private const val MAX_REDIRECTS = 3
        private const val MAX_BODY_BYTES = 64 * 1024
        private const val REQUEST_TIMEOUT_MILLIS = 15_000L
        private const val CONNECT_TIMEOUT_MILLIS = 10_000L
        private const val HOST_CHARS = ".-_[]:"

        private val DiscoveryJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        /** Discovery for the app: shares the engine of [StoatHttp], but not its session token. */
        fun create(allowInsecure: Boolean): ServerDiscovery =
            ServerDiscovery(StoatHttp.engine, allowInsecure, buildUserAgent("Ktor discovery"))
    }
}
