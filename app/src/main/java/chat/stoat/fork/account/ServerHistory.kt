package chat.stoat.fork.account

import chat.stoat.api.StoatJson
import chat.stoat.persistence.KVStorage
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** Servers used on this device, newest first, offered on the login screen (AN-10). Device KV. */
object ServerHistory {
    private const val KEY = "durio/serverHistory"
    private const val LIMIT = 5
    private val LIST = ListSerializer(String.serializer())

    suspend fun load(kv: KVStorage): List<String> =
        kv.get(KEY)?.let { json -> runCatching { StoatJson.decodeFromString(LIST, json) }.getOrNull() }
            ?: emptyList()

    suspend fun add(kv: KVStorage, api: String) {
        val address = api.removePrefix("https://")
        val updated = (listOf(address) + load(kv).filterNot { it == address }).take(LIMIT)
        kv.set(KEY, StoatJson.encodeToString(LIST, updated))
    }
}
