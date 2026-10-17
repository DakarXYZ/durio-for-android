package chat.stoat.api.routes.push

import chat.stoat.api.routes.account.WebPushData
import chat.stoat.api.fork.http.ApiClient
import chat.stoat.api.fork.http.api
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

suspend fun subscribePush(
    endpoint: String = "fcm",
    auth: String,
    p256diffieHellman: String? = null,
    client: ApiClient = ApiClient.Active,
) {
    val data = WebPushData(
        endpoint = endpoint,
        p256diffieHellman = p256diffieHellman ?: "",
        auth = auth
    )

    client.http.post("/push/subscribe".api(client)) {
        setBody(data)
        contentType(ContentType.Application.Json)
    }
}

suspend fun unsubscribePush(client: ApiClient = ApiClient.Active) {
    client.http.post("/push/unsubscribe".api(client))
}