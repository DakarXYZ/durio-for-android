package chat.stoat.api.fork.http

import chat.stoat.api.StoatHttp
import chat.stoat.api.api
import io.ktor.client.HttpClient

/**
 * Where a route sends its request and with which token (plan AN-15).
 *
 * Routes that run in the background for a possibly inactive account (push, notification
 * actions, push re-subscription) take a `client: ApiClient = ApiClient.Active` parameter;
 * everything else keeps using the active account through [StoatHttp].
 */
interface ApiClient {
    val http: HttpClient

    fun api(path: String): String

    /** The active account: the global [StoatHttp] and server, as upstream routes use them. */
    data object Active : ApiClient {
        override val http: HttpClient
            get() = StoatHttp

        override fun api(path: String): String = path.api()
    }
}

fun String.api(client: ApiClient): String = client.api(this)
