package chat.stoat.api.fork.routes

import chat.stoat.api.StoatJson
import chat.stoat.api.fork.http.ApiClient
import chat.stoat.api.fork.http.api
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * An `/admin` route refused (backend `routes/admin`, dur.io fork): 403 `NotPrivileged` for a
 * non-admin, 404 `NotFound` for an unknown user or session, 400 `IsBot` (bots have no sessions).
 */
class AdminRequestException(val status: Int, val type: String?) : Exception("Admin request refused: ${type ?: status}")

@Serializable
private class AdminStatus(val admin: Boolean = false)

/** A user as `GET /admin/users` finds them. */
@Serializable
data class AdminUser(
    @SerialName("_id") val id: String,
    val username: String,
    val discriminator: String,
    @SerialName("display_name") val displayName: String? = null,
    val bot: Boolean = false,
) {
    /** `username#discriminator`, the way it is searched for. */
    val tag: String
        get() = "$username#$discriminator"
}

/**
 * `GET /admin/@me`: whether the account may use the admin routes of its server. 404 is a
 * server without them (upstream or older): not an admin either.
 */
suspend fun fetchAdminStatus(client: ApiClient = ApiClient.Active): Boolean {
    val response = client.http.get("/admin/@me".api(client))
    if (response.status.value == 404) return false
    val body = checked(response)
    return StoatJson.decodeFromString(AdminStatus.serializer(), body).admin
}

/** `GET /admin/users?query=` by id or `username#discriminator`; null when nobody matches. */
suspend fun findAdminUser(query: String, client: ApiClient = ApiClient.Active): AdminUser? {
    val response = client.http.get("/admin/users?query=${query.trim().encodeURLParameter()}".api(client))
    if (response.status.value == 404) return null
    return StoatJson.decodeFromString(AdminUser.serializer(), checked(response))
}

/** `GET /admin/users/{id}/sessions`: name and last activity, never tokens. */
suspend fun fetchUserSessions(userId: String, client: ApiClient = ApiClient.Active): List<DeviceSession> {
    val response = client.http.get("/admin/users/${userId.encodeURLPathPart()}/sessions".api(client))
    return StoatJson.decodeFromString(ListSerializer(DeviceSession.serializer()), checked(response))
}

/** `DELETE /admin/users/{id}/sessions/{session}`: like a logout, the session's E2EE device goes too. */
suspend fun endUserSession(userId: String, sessionId: String, client: ApiClient = ApiClient.Active) {
    checked(client.http.delete("/admin/users/${userId.encodeURLPathPart()}/sessions/${sessionId.encodeURLPathPart()}".api(client)))
}

/** `DELETE /admin/users/{id}/sessions`: every session of the user. */
suspend fun endAllUserSessions(userId: String, client: ApiClient = ApiClient.Active) {
    checked(client.http.delete("/admin/users/${userId.encodeURLPathPart()}/sessions".api(client)))
}

private suspend fun checked(response: HttpResponse): String {
    val body = response.bodyAsText()
    if (!response.status.isSuccess()) throw AdminRequestException(response.status.value, errorType(body))
    return body
}
