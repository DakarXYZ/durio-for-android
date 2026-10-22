package chat.stoat.fork.account

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import chat.stoat.fork.keystore.KeyWrapper
import chat.stoat.fork.keystore.UnwrapException
import chat.stoat.persistence.Database
import chat.stoat.persistence.KVStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import java.io.File

/**
 * Files of one account (plan AN-08): `kv_<key>` (DataStore) and `cache_<key>.db` (SQLDelight).
 * Neither is encrypted nor backed up (Q40); the session token inside the KV file is wrapped
 * with the Keystore key.
 *
 * Get instances only from [AccountStorageRegistry]: DataStore allows one instance per file per
 * process.
 */
class AccountStorage internal constructor(
    private val context: Context,
    val key: String,
    private val wrapper: KeyWrapper,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val driverFactory: (Context, String) -> SqlDriver = ::androidDriver,
) {
    val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { context.preferencesDataStoreFile(kvName(key)) }
    )

    val kv = KVStorage(dataStore)

    private val driverLazy = lazy { driverFactory(context, databaseName(key)) }

    /** Opened on first use, so push handling for a background account reads only what it needs. */
    val sqlDriver: SqlDriver by driverLazy

    suspend fun readToken(): String? {
        val stored = dataStore.data.first()[TOKEN] ?: return null
        return try {
            String(wrapper.unwrap(Base64.decode(stored, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: UnwrapException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw UnwrapException("Stored token is not Base64", e)
        }
    }

    suspend fun writeToken(token: String) {
        val wrapped = Base64.encodeToString(wrapper.wrap(token.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        dataStore.edit { it[TOKEN] = wrapped }
    }

    /** Stops the DataStore and closes the database; files stay. */
    internal suspend fun close() {
        scope.coroutineContext.job.cancelAndJoin()
        if (driverLazy.isInitialized()) sqlDriver.close()
    }

    companion object {
        private val TOKEN = stringPreferencesKey("durio/sessionToken")

        fun kvName(key: String) = "kv_$key"

        fun databaseName(key: String) = "cache_$key.db"

        private fun androidDriver(context: Context, name: String): SqlDriver =
            AndroidSqliteDriver(Database.Schema, context, name)

        /** Deletes the account's files. Call only after [close]. */
        fun deleteFiles(context: Context, key: String) {
            context.preferencesDataStoreFile(kvName(key)).delete()
            context.deleteDatabase(databaseName(key))
            // the E2EE store and its wrapped key (AN-22, E2eeStoreKeys); normally destroyed
            // by E2eeManager.destroy already, this catches what a failed destroy left
            File(context.noBackupFilesDir, "crypto/$key").deleteRecursively()
            File(context.noBackupFilesDir, "keys/$key.bin").delete()
            File(context.noBackupFilesDir, "keys/$key.session").delete()
        }
    }
}
