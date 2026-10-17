package chat.stoat.api.routes.auth

import chat.stoat.api.StoatHttp
import chat.stoat.api.StoatJson
import chat.stoat.api.api
import chat.stoat.api.fork.http.ApiClient
import chat.stoat.api.fork.http.api
import chat.stoat.core.model.schemas.Session
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.builtins.ListSerializer

suspend fun fetchAllSessions(): List<Session> {
    val response = StoatHttp.get("/auth/session/all".api())
        .bodyAsText()

    return StoatJson.decodeFromString(
        ListSerializer(Session.serializer()),
        response
    )
}

suspend fun logoutSessionById(id: String, client: ApiClient = ApiClient.Active) {
    client.http.delete("/auth/session/$id".api(client))
}

suspend fun logoutAllSessions(includingSelf: Boolean = false) {
    StoatHttp.delete("/auth/session/all".api()) {
        parameter("revoke_self", includingSelf)
    }
}
