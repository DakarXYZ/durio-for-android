package chat.stoat.api.fork.routes

import chat.stoat.api.StoatJson
import chat.stoat.api.fork.http.ApiClient
import chat.stoat.api.fork.http.api
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * An `/admin` route refused (backend `routes/admin`, dur.io fork): 403 `NotPrivileged` for a
 * non-admin, 403 `AdminElevationRequired` without admin mode (2FA, [elevateAdmin]), 403
 * `TotpRequired` when elevating without an authenticator app, 404 `NotFound` for an unknown
 * user or session, 400 `IsBot` (bots have no sessions).
 */
class AdminRequestException(val status: Int, val type: String?) : Exception("Admin request refused: ${type ?: status}") {
    /** Admin mode is off or expired: elevate with an MFA ticket and try again. */
    val needsElevation: Boolean
        get() = type == "AdminElevationRequired"

    /** The admin has no authenticator app: admin mode needs one. */
    val needsTotp: Boolean
        get() = type == "TotpRequired"

    /**
     * The MFA ticket of an elevation was wrong, expired or used (401 `InvalidToken`): ask for a
     * new code. Not an ended session: the session token was fine.
     */
    val invalidTicket: Boolean
        get() = status == 401
}

/** `GET /admin/@me`: an admin, and until when admin mode is on for this session. */
@Serializable
data class AdminStatus(
    val admin: Boolean = false,
    @SerialName("elevated_until") val elevatedUntil: String? = null,
) {
    /** End of admin mode in epoch ms; null when off (or unreadable). */
    val elevatedUntilMs: Long?
        get() = epochMs(elevatedUntil)

    fun elevated(nowMs: Long): Boolean = elevatedUntilMs?.let { it > nowMs } == true
}

@Serializable
private class AdminElevation(@SerialName("expires_at") val expiresAt: String)

/** A sign-up invite made by an admin of the server (`/admin/invites`). */
@Serializable
data class AdminInvite(
    @SerialName("_id") val code: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("multi_use") val multiUse: Boolean = false,
    val uses: Long = 0,
    @SerialName("created_by") val createdBy: String? = null,
) {
    val expiresAtMs: Long?
        get() = epochMs(expiresAt)

    companion object {
        /** Lifetimes the server accepts, in hours; anything else is 400 `InvalidOperation`. */
        val LIFETIMES_HOURS = listOf(1, 4, 12, 24)

        /** The link people open and paste into sign-up: `https://<host>/invite/<code>`. */
        fun link(host: String, code: String) = "https://$host/invite/$code"
    }
}

@Serializable
private class CreateAdminInvite(
    @SerialName("ttl_hours") val ttlHours: Int,
    @SerialName("multi_use") val multiUse: Boolean,
)

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
suspend fun fetchAdminStatus(client: ApiClient = ApiClient.Active): AdminStatus {
    val response = client.http.get("/admin/@me".api(client))
    if (response.status.value == 404) return AdminStatus()
    return StoatJson.decodeFromString(AdminStatus.serializer(), checked(response))
}

/**
 * `POST /admin/elevate` with a validated MFA ticket: admin mode for this session (about an
 * hour). Returns its end in epoch ms. Goes through the active account's client, which takes no
 * 401 for a logout: here a 401 is a bad ticket ([AdminRequestException.invalidTicket]).
 * @throws AdminRequestException 403 `TotpRequired`: the account has no authenticator app
 */
suspend fun elevateAdmin(mfaTicket: String, client: ApiClient = ApiClient.Active): Long {
    val response = client.http.post("/admin/elevate".api(client)) {
        header("x-mfa-ticket", mfaTicket)
    }
    val expires = StoatJson.decodeFromString(AdminElevation.serializer(), checked(response)).expiresAt
    return epochMs(expires) ?: throw AdminRequestException(response.status.value, null)
}

/** `POST /admin/invites`: a new invite for [ttlHours] (one of [AdminInvite.LIFETIMES_HOURS]). */
suspend fun createAdminInvite(ttlHours: Int, multiUse: Boolean, client: ApiClient = ApiClient.Active): AdminInvite {
    val response = client.http.post("/admin/invites".api(client)) {
        setBody(TextContent(StoatJson.encodeToString(CreateAdminInvite.serializer(), CreateAdminInvite(ttlHours, multiUse)), ContentType.Application.Json))
    }
    return StoatJson.decodeFromString(AdminInvite.serializer(), checked(response))
}

/** `GET /admin/invites`: active invites made in the app, newest first. */
suspend fun fetchAdminInvites(client: ApiClient = ApiClient.Active): List<AdminInvite> {
    val response = client.http.get("/admin/invites".api(client))
    return StoatJson.decodeFromString(ListSerializer(AdminInvite.serializer()), checked(response))
}

/** `DELETE /admin/invites/{code}`: the invite stops working; 404 when gone already. */
suspend fun revokeAdminInvite(code: String, client: ApiClient = ApiClient.Active) {
    checked(client.http.delete("/admin/invites/${code.encodeURLPathPart()}".api(client)))
}

/**
 * Runs [block]; when admin mode is off or expired (`AdminElevationRequired`), [elevate] (ask
 * for 2FA, then [elevateAdmin]; false when the user cancelled) and runs [block] once more.
 * Null when cancelled.
 */
suspend fun <T> withAdminElevation(elevate: suspend () -> Boolean, block: suspend () -> T): T? = try {
    block()
} catch (e: AdminRequestException) {
    if (!e.needsElevation) throw e
    if (elevate()) block() else null
}

/** `DELETE /admin/elevate`: admin mode ends now ("End admin mode"). */
suspend fun endAdminElevation(client: ApiClient = ApiClient.Active) {
    checked(client.http.delete("/admin/elevate".api(client)))
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

@OptIn(ExperimentalTime::class)
private fun epochMs(timestamp: String?): Long? = try {
    timestamp?.let { Instant.parse(it).toEpochMilliseconds() }
} catch (e: IllegalArgumentException) {
    null
}

private suspend fun checked(response: HttpResponse): String {
    val body = response.bodyAsText()
    if (!response.status.isSuccess()) throw AdminRequestException(response.status.value, errorType(body))
    return body
}
