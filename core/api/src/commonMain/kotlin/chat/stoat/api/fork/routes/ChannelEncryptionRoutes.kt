package chat.stoat.api.fork.routes

import chat.stoat.api.StoatJson
import chat.stoat.api.fork.http.ApiClient
import chat.stoat.api.fork.http.api
import chat.stoat.core.model.fork.EncryptedEnvelope
import chat.stoat.core.model.schemas.Channel
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess

/**
 * Why encryption could not be enabled (BE-11): 404 the server has E2EE switched off, 403
 * `MissingPermission` (ManageChannel), 400 `NotEncryptable` (a bot among the recipients or
 * webhooks), 409 `AlreadyEncrypted`.
 */
class EnableEncryptionException(val status: Int, val type: String?) :
    Exception("Encryption not enabled: ${type ?: status}")

/**
 * `PUT /channels/{id}/encryption` (plan AN-27; shapes in 10-backend "Заметки реализации этапа
 * 2"): irreversible; the answer is the channel with `encryption`. The socket then sends
 * `ChannelUpdate` and the system message `encryption_enabled`.
 */
suspend fun enableChannelEncryption(channelId: String, client: ApiClient = ApiClient.Active): Channel {
    val response = client.http.put("/channels/$channelId/encryption".api(client)) {
        setBody(TextContent("""{"algorithm":"${EncryptedEnvelope.MEGOLM_ALGORITHM}"}""", ContentType.Application.Json))
    }
    val body = response.bodyAsText()
    if (!response.status.isSuccess()) throw EnableEncryptionException(response.status.value, errorType(body))
    return StoatJson.decodeFromString(Channel.serializer(), body)
}
