package chat.stoat.api.fork.routes.e2ee

import chat.stoat.api.StoatJson
import chat.stoat.api.fork.e2ee.E2eeRequest
import chat.stoat.api.fork.http.ApiClient
import chat.stoat.api.fork.http.api
import io.ktor.client.plugins.retry
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The `/e2ee` routes (plan AN-21; shapes: 10-backend "Заметки реализации этапа 2", contract v1 in
 * stoat-crypto/fixtures). Bodies go to the core and come back from it as raw JSON: no Kotlin
 * models, one schema less to keep in sync.
 *
 * Every call disables the client's own retries (`StoatHttp` retries 5 times): `E2eeSync`
 * retries with backoff and marks each attempt in the core exactly once.
 */
object E2eeRoutes {
    /** Messages per mailbox page; the server's maximum (`MAX_TO_DEVICE_FETCH`). */
    const val TO_DEVICE_LIMIT = 100

    /** Ids per ack call (`MAX_TO_DEVICE_ACK`). */
    const val ACK_LIMIT = 100

    const val TO_DEVICE_PATH = "/e2ee/to_device?limit=$TO_DEVICE_LIMIT"
    const val TO_DEVICE_ACK_PATH = "/e2ee/to_device/ack"
    const val KEYS_COUNT_PATH = "/e2ee/keys/count"
}

/** Status and raw body; [retryAfterMs] from a 429. */
class E2eeResponse(val status: Int, val body: String, val retryAfterMs: Long? = null) {
    val isSuccess: Boolean
        get() = status in 200..299

    override fun toString() = "E2eeResponse($status)"
}

/** Runs one request of the core as it is: method, path (with its query) and JSON body. */
suspend fun executeE2eeRequest(client: ApiClient, request: E2eeRequest): E2eeResponse =
    e2eeCall(client, HttpMethod.parse(request.method.uppercase()), request.path, request.body)

/** `GET /e2ee/to_device?limit=100`: the oldest messages of this device's mailbox. */
suspend fun fetchToDevice(client: ApiClient): E2eeResponse =
    e2eeCall(client, HttpMethod.Get, E2eeRoutes.TO_DEVICE_PATH)

/** `POST /e2ee/to_device/ack {ids}`: at most [E2eeRoutes.ACK_LIMIT] ids. */
suspend fun ackToDevice(client: ApiClient, ids: List<String>): E2eeResponse {
    require(ids.size in 1..E2eeRoutes.ACK_LIMIT) { "1..${E2eeRoutes.ACK_LIMIT} ids per ack" }
    val body = buildJsonObject { put("ids", JsonArray(ids.map(::JsonPrimitive))) }
    return e2eeCall(client, HttpMethod.Post, E2eeRoutes.TO_DEVICE_ACK_PATH, body.toString())
}

/** `GET /e2ee/keys/count`: one-time and fallback keys left on the server. */
suspend fun fetchKeysCount(client: ApiClient): E2eeResponse =
    e2eeCall(client, HttpMethod.Get, E2eeRoutes.KEYS_COUNT_PATH)

private suspend fun e2eeCall(
    client: ApiClient,
    method: HttpMethod,
    path: String,
    body: String? = null,
): E2eeResponse {
    val response = client.http.request(path.api(client)) {
        this.method = method
        retry { noRetry() }
        if (!body.isNullOrEmpty() && method != HttpMethod.Get) {
            setBody(TextContent(body, ContentType.Application.Json))
        }
    }
    val text = response.bodyAsText()
    val retryAfter = if (response.status.value == 429) retryAfterMs(text, response.headers[HttpHeaders.RetryAfter]) else null
    return E2eeResponse(response.status.value, text, retryAfter)
}

// Stoat's limiter answers `{"retry_after": ms}`; a proxy may send `Retry-After: seconds`
internal fun retryAfterMs(body: String, header: String?): Long? {
    val fromBody = try {
        StoatJson.parseToJsonElement(body).jsonObject["retry_after"]?.jsonPrimitive?.longOrNull
    } catch (_: Exception) {
        null
    }
    return fromBody ?: header?.trim()?.toLongOrNull()?.times(1000)
}
