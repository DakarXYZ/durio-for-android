package chat.stoat.api.fork.routes

import chat.stoat.api.StoatJson
import chat.stoat.api.fork.http.ApiClient
import chat.stoat.api.fork.http.api
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * A session of the account as the devices screen shows it (plan AN-30): session = E2EE device
 * (C22). Upstream's `Session` has no `last_seen`; the fork's backend sends it (BE-31).
 */
@Serializable
data class DeviceSession(
    @SerialName("_id") val id: String,
    val name: String,
    @SerialName("last_seen") val lastSeen: JsonElement? = null,
) {
    /** Last activity in epoch ms, if the server knows it (an ISO date or ms). */
    @OptIn(ExperimentalTime::class)
    val lastSeenMs: Long?
        get() {
            val value = lastSeen as? JsonPrimitive ?: return null
            value.longOrNull?.let { return it }
            return try {
                Instant.parse(value.content).toEpochMilliseconds()
            } catch (e: IllegalArgumentException) {
                null
            }
        }
}

/** `GET /auth/session/all` of the account behind [client]. */
suspend fun fetchDeviceSessions(client: ApiClient): List<DeviceSession> {
    val response = client.http.get("/auth/session/all".api(client))
    if (!response.status.isSuccess()) throw AccountRequestException(response.status.value)
    return StoatJson.decodeFromString(ListSerializer(DeviceSession.serializer()), response.bodyAsText())
}
