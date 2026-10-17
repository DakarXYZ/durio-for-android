package chat.stoat.persistence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.firstOrNull

// durio: the store is picked per key, so account keys can live in the account's own file
// while device keys stay in revolt_kv (plan AN-08, AccountKv)
class KVStorage(
    private val dataStoreFor: (key: String) -> DataStore<Preferences>
) {
    constructor(dataStore: DataStore<Preferences>) : this({ dataStore })

    suspend fun set(key: String, value: String) {
        dataStoreFor(key).edit { preferences ->
            preferences[stringPreferencesKey(key)] = value
        }
    }

    suspend fun get(key: String): String? {
        return dataStoreFor(key).data.firstOrNull()?.get(stringPreferencesKey(key))
    }

    suspend fun set(key: String, value: Boolean) {
        dataStoreFor(key).edit { preferences ->
            preferences[stringPreferencesKey(key)] = value.toString()
        }
    }

    suspend fun getBoolean(key: String): Boolean? {
        return dataStoreFor(key).data.firstOrNull()?.get(stringPreferencesKey(key))?.toBoolean()
    }

    suspend fun set(key: String, value: Int) {
        dataStoreFor(key).edit { preferences ->
            preferences[stringPreferencesKey(key)] = value.toString()
        }
    }

    suspend fun getInt(key: String): Int? {
        return dataStoreFor(key).data.firstOrNull()?.get(stringPreferencesKey(key))?.toInt()
    }

    suspend fun remove(key: String) {
        dataStoreFor(key).edit { preferences ->
            preferences.remove(stringPreferencesKey(key))
        }
    }
}
