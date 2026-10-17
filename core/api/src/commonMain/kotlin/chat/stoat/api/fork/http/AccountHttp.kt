package chat.stoat.api.fork.http

import chat.stoat.api.StoatHttp
import chat.stoat.api.StoatJson
import chat.stoat.api.buildUserAgent
import chat.stoat.core.model.fork.ServerEndpoints
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json

/**
 * HTTP for one account regardless of which account is active (plan AN-15): its own server and
 * token, never the global ones. For push handling, notification actions and other background
 * work; the active account keeps using [StoatHttp].
 *
 * Shares the engine (and its connection pool) with [StoatHttp]: closing this client leaves the
 * engine open.
 */
class AccountHttp(
    val endpoints: ServerEndpoints,
    private val token: String,
    engine: HttpClientEngine = StoatHttp.engine,
    userAgent: String = buildUserAgent(),
) : ApiClient, AutoCloseable {
    override val http = HttpClient(engine) {
        install(SessionTokenPlugin("AccountSessionToken", { endpoints }, { token }))
        install(ContentNegotiation) {
            json(StoatJson)
        }
        install(Logging) { level = LogLevel.INFO }

        defaultRequest {
            header("User-Agent", userAgent)
        }
    }

    override fun api(path: String): String = "${endpoints.api}$path"

    override fun close() {
        http.close()
    }
}
