package chat.stoat.persistence

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import chat.stoat.api.fork.account.AccountKv

val Context.stoatKVStorage: DataStore<Preferences> by preferencesDataStore(name = "revolt_kv")

// durio: account keys go to the active account's file (plan AN-08)
fun KVStorage(context: Context): KVStorage {
    val device = context.stoatKVStorage
    return KVStorage { key -> AccountKv.storeFor(key, device) }
}
