package chat.stoat.api.fork.routes.e2ee

import chat.stoat.api.StoatJson
import chat.stoat.api.fork.e2ee.E2eeRequest
import chat.stoat.api.fork.http.ApiClient
import chat.stoat.api.fork.http.api
import io.ktor.client.plugins.retry
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
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
    const val BACKUP_VERSION_PATH = "/e2ee/backup/version"
    const val BACKUP_KEYS_PATH = "/e2ee/backup/keys"
    const val SECRETS_PATH = "/e2ee/secrets"

    /** Secret storage entries a restore reads (AN-32). */
    const val SECRET_DEFAULT_KEY = "m.secret_storage.default_key"
    const val SECRET_KEY_PREFIX = "m.secret_storage.key."
    const val SECRET_MEGOLM_BACKUP = "m.megolm_backup.v1"

    /** Header of an MFA ticket: replacing a backup needs one (Q13). */
    const val MFA_TICKET_HEADER = "x-mfa-ticket"
}

/** Status and raw body; [retryAfterMs] from a 429. */
class E2eeResponse(val status: Int, val body: String, val retryAfterMs: Long? = null) {
    val isSuccess: Boolean
        get() = status in 200..299

    override fun toString() = "E2eeResponse($status)"
}

/**
 * Runs one request of the core as it is: method, path (with its query) and JSON body; with
 * [mfaTicket] for a backup version that replaces another (AN-32).
 */
suspend fun executeE2eeRequest(client: ApiClient, request: E2eeRequest, mfaTicket: String? = null): E2eeResponse =
    e2eeCall(client, HttpMethod.parse(request.method.uppercase()), request.path, request.body, mfaTicket)

/** `GET /e2ee/secrets/<type>`: an entry of the secret storage (ciphertext), 404 if none. */
suspend fun fetchE2eeSecret(client: ApiClient, type: String): E2eeResponse =
    e2eeCall(client, HttpMethod.Get, "${E2eeRoutes.SECRETS_PATH}/${type.encodeURLPathPart()}")

/** `GET /e2ee/backup/version`: the active backup, 404 if none. */
suspend fun fetchBackupVersion(client: ApiClient): E2eeResponse =
    e2eeCall(client, HttpMethod.Get, E2eeRoutes.BACKUP_VERSION_PATH)

/** `GET /e2ee/backup/keys?version=<v>`: every key of the backup (encrypted to its public key). */
suspend fun fetchBackupKeys(client: ApiClient, version: String): E2eeResponse =
    e2eeCall(client, HttpMethod.Get, "${E2eeRoutes.BACKUP_KEYS_PATH}?version=${version.encodeURLParameter()}")

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
    mfaTicket: String? = null,
): E2eeResponse {
    val response = client.http.request(path.api(client)) {
        this.method = method
        retry { noRetry() }
        if (mfaTicket != null) header(E2eeRoutes.MFA_TICKET_HEADER, mfaTicket)
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
