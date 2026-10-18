package chat.stoat.api.fork.routes

import chat.stoat.api.StoatJson
import chat.stoat.api.fork.http.ApiClient
import chat.stoat.api.fork.http.api
import chat.stoat.api.fork.server.ServerRootInfo
import chat.stoat.core.model.schemas.User
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

/** Thrown when the server refuses the request (bad token, gone session); never for network errors. */
class AccountRequestException(val status: Int) : Exception("Server answered $status")

/**
 * `GET /users/@me` for any account (plan AN-08 migration, AN-12). Unlike `fetchSelf()` it leaves
 * the global caches alone.
 */
suspend fun fetchSelfUser(client: ApiClient): User {
    val response = client.http.get("/users/@me".api(client))
    if (!response.status.isSuccess()) throw AccountRequestException(response.status.value)
    return StoatJson.decodeFromString(User.serializer(), response.bodyAsText())
}

/**
 * `GET /` of the account's server (plan AN-23): features, `features.e2ee` and its limits. The
 * tolerant [ServerRootInfo], not upstream's `Root`.
 */
suspend fun fetchServerRoot(client: ApiClient): ServerRootInfo {
    val response = client.http.get("/".api(client))
    if (!response.status.isSuccess()) throw AccountRequestException(response.status.value)
    return StoatJson.decodeFromString(ServerRootInfo.serializer(), response.bodyAsText())
}

/**
 * `POST /auth/session/logout`: revokes the session whose token made the request (plan AN-11).
 * For accounts whose session ID is unknown; otherwise `logoutSessionById` does the same.
 */
suspend fun logoutCurrentSession(client: ApiClient) {
    val response = client.http.post("/auth/session/logout".api(client))
    if (!response.status.isSuccess()) throw AccountRequestException(response.status.value)
}
