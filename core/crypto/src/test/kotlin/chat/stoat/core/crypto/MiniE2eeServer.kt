package chat.stoat.core.crypto

import chat.stoat.api.fork.http.ApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The `/e2ee` routes of contract v1 in memory, enough for two devices to exchange keys (after
 * stoat-crypto's bindings/android MiniServer; the live backend is the real reference). Each
 * device talks to it through its own [client], as the app does through `AccountHttp`.
 */
class MiniE2eeServer {
    private class Mail(val id: String, val recipient: String, val json: JsonObject)

    private val deviceKeys = mutableMapOf<String, MutableMap<String, JsonElement>>()
    private val otks = mutableMapOf<String, MutableMap<String, JsonElement>>()
    private val fallback = mutableMapOf<String, Pair<String, JsonElement>>()
    private val fallbackUsed = mutableMapOf<String, Boolean>()
    private val mailbox = mutableListOf<Mail>()
    private var seq = 0
    val log = mutableListOf<String>()
    private val queried = mutableSetOf<Pair<String, String>>()

    fun client(user: String, device: String): ApiClient = object : ApiClient {
        override val http = HttpClient(
            MockEngine { request ->
                val body = (request.body as? TextContent)?.text
                val path = request.url.encodedPathAndQuery.removePrefix("/api")
                respond(handle(user, device, request.method.value, path, body), HttpStatusCode.OK)
            }
        )

        override fun api(path: String) = "https://mini.invalid/api$path"
    }

    fun hasKeysOf(device: String): Boolean = synchronized(this) {
        deviceKeys.values.any { device in it }
    }

    fun mailboxSize(device: String): Int = synchronized(this) { mailbox.count { it.recipient == device } }

    /** [device] asked for the keys of [user]. */
    fun queried(device: String, user: String): Boolean = synchronized(this) { (device to matrixId(user)) in queried }

    fun logged(line: String): Boolean = synchronized(this) { line in log }

    fun logSize(): Int = synchronized(this) { log.size }

    @Synchronized
    fun handle(user: String, device: String, method: String, path: String, body: String?): String {
        log += "$device $method ${path.substringBefore('?')}"
        val json = body?.takeIf { it.isNotEmpty() }?.let { Json.parseToJsonElement(it).jsonObject }
        val me = matrixId(user)
        return when {
            path == "/e2ee/keys/upload" -> {
                json?.get("device_keys")?.let { deviceKeys.getOrPut(me) { mutableMapOf() }[device] = it }
                json?.get("one_time_keys")?.jsonObject?.forEach { (id, key) ->
                    otks.getOrPut(device) { mutableMapOf() }[id] = key
                }
                json?.get("fallback_keys")?.jsonObject?.entries?.firstOrNull()?.let { (id, key) ->
                    fallback[device] = id to key
                    fallbackUsed[device] = false
                }
                counts(device).toString()
            }
            path == "/e2ee/keys/query" -> buildJsonObject {
                json!!["device_keys"]!!.jsonObject.keys.forEach { queried += device to it }
                putJsonObject("device_keys") {
                    json!!["device_keys"]!!.jsonObject.keys.forEach { u ->
                        put(u, JsonObject(deviceKeys[u] ?: emptyMap()))
                    }
                }
                putJsonObject("failures") {}
            }.toString()
            path == "/e2ee/keys/claim" -> buildJsonObject {
                putJsonObject("one_time_keys") {
                    json!!["one_time_keys"]!!.jsonObject.forEach { (u, devices) ->
                        putJsonObject(u) {
                            devices.jsonObject.keys.forEach { d ->
                                claim(d)?.let { (id, key) -> putJsonObject(d) { put(id, key) } }
                            }
                        }
                    }
                }
                putJsonObject("failures") {}
            }.toString()
            path == "/e2ee/keys/count" -> counts(device).toString()
            path == "/e2ee/to_device/ack" -> {
                val ids = json!!["ids"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
                mailbox.removeAll { it.recipient == device && it.id in ids }
                ""
            }
            path.startsWith("/e2ee/to_device?") -> buildJsonObject {
                put("messages", JsonArray(mailbox.filter { it.recipient == device }.take(100).map { it.json }))
            }.toString()
            path.startsWith("/e2ee/to_device/") && method == "PUT" -> {
                val type = json!!["type"]!!.jsonPrimitive.content
                json["messages"]!!.jsonObject.forEach { (u, devices) ->
                    devices.jsonObject.forEach { (d, content) ->
                        val targets = if (d == "*") deviceKeys[u]?.keys.orEmpty().filter { it != device } else listOf(d)
                        targets.forEach { t ->
                            seq += 1
                            val id = "01KM" + seq.toString().padStart(22, '0')
                            mailbox += Mail(
                                id,
                                t,
                                buildJsonObject {
                                    put("id", id)
                                    put("sender", me)
                                    put("sender_device", device)
                                    put("type", type)
                                    put("content", content)
                                }
                            )
                        }
                    }
                }
                "{}"
            }
            else -> error("MiniE2eeServer: no route $method $path")
        }
    }

    private fun claim(device: String): Pair<String, JsonElement>? {
        otks[device]?.entries?.firstOrNull()?.let { (id, key) ->
            otks[device]!!.remove(id)
            return id to key
        }
        return fallback[device]?.also { fallbackUsed[device] = true }
    }

    private fun counts(device: String) = buildJsonObject {
        putJsonObject("one_time_key_counts") { put("signed_curve25519", otks[device]?.size ?: 0) }
        put(
            "device_unused_fallback_key_types",
            buildJsonArray { if (fallbackUsed[device] == false) add(JsonPrimitive("signed_curve25519")) }
        )
    }

    companion object {
        fun matrixId(user: String) = "@${user.lowercase()}:stoat.invalid"
    }
}
