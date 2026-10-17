package chat.stoat.fork.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import chat.stoat.api.fork.account.AccountKv
import chat.stoat.core.model.fork.ServerEndpoints
import kotlinx.coroutines.flow.first

/**
 * Moves the single account of an install from before multi-account (plan AN-08) into
 * [AccountStore] and its own files.
 *
 * The device file `revolt_kv` stays; its account keys ([AccountKv]) are copied to
 * `kv_<key>`, the token is wrapped, then the keys are removed from `revolt_kv`. The old
 * `revolt.db` is only a cache and is deleted by the caller. Each step can be repeated, so a crash
 * half-way just migrates again on the next start.
 *
 * The account key needs the user ID. Old installs may not have `selfId` yet; then the migration
 * is postponed and the app keeps working the old way (account keys in `revolt_kv`) until
 * [rememberUserId] or the chat screen stores it.
 */
class LegacyAccountMigration(
    private val device: DataStore<Preferences>,
    private val legacyServer: () -> ServerEndpoints?,
    private val accounts: AccountStore,
    private val storageFor: (String) -> AccountStorage,
    private val now: () -> Long = System::currentTimeMillis,
) {
    sealed interface Result {
        /** Nothing signed in before (fresh install, or already migrated). */
        data object NothingToMigrate : Result

        data class Migrated(val account: Account) : Result

        /** No user ID yet: try again on the next start. */
        data object Postponed : Result

        /** No server is known for the old session, so it cannot be used; it was removed. */
        data object Discarded : Result
    }

    suspend fun hasLegacySession(): Boolean = !device.data.first()[TOKEN].isNullOrEmpty()

    suspend fun legacySession(): Pair<String, String>? {
        val prefs = device.data.first()
        val token = prefs[TOKEN]?.takeIf { it.isNotEmpty() } ?: return null
        return token to (prefs[SESSION_ID] ?: "")
    }

    suspend fun migrate(): Result {
        val prefs = device.data.first()
        val token = prefs[TOKEN]?.takeIf { it.isNotEmpty() } ?: return Result.NothingToMigrate

        val endpoints = legacyServer()
        if (endpoints == null) {
            removeAccountKeys()
            return Result.Discarded
        }

        val userId = prefs[SELF_ID]?.takeIf { it.isNotEmpty() } ?: return Result.Postponed
        val key = accountKey(endpoints.api, userId)
        val storage = storageFor(key)

        storage.dataStore.edit { target ->
            prefs.asMap().forEach { (prefKey, value) ->
                if (prefKey.name != TOKEN.name && AccountKv.isAccountScoped(prefKey.name) && value is String) {
                    target[stringPreferencesKey(prefKey.name)] = value
                }
            }
        }
        storage.writeToken(token)

        val account = Account(
            key = key,
            endpoints = endpoints,
            userId = userId,
            sessionId = prefs[SESSION_ID] ?: "",
            displayName = prefs[SELF_NAME]?.takeIf { it.isNotEmpty() },
            avatarUrl = prefs[SELF_AVATAR]?.takeIf { it.isNotEmpty() },
            addedAt = now(),
            // a session from before multi-account can only be on our own server
            ownServer = true,
        )
        accounts.upsert(account, makeActive = true)
        removeAccountKeys()
        return Result.Migrated(account)
    }

    /**
     * For a postponed migration: ask the server who the token belongs to and keep the answer in
     * `revolt_kv`, so the next start can migrate. False without network or with a bad token.
     */
    suspend fun rememberUserId(fetchUserId: suspend (ServerEndpoints, String) -> String): Boolean {
        val endpoints = legacyServer() ?: return false
        val (token, _) = legacySession() ?: return false
        val userId = try {
            fetchUserId(endpoints, token)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return false
        }
        if (userId.isEmpty()) return false
        device.edit { it[SELF_ID] = userId }
        return true
    }

    /** Drops the old session from `revolt_kv` (after migration, or when the user logs out of it). */
    suspend fun removeAccountKeys() {
        device.edit { prefs ->
            prefs.asMap().keys
                .filter { AccountKv.isAccountScoped(it.name) }
                .forEach { prefs.remove(it) }
        }
    }

    private companion object {
        val TOKEN = stringPreferencesKey("sessionToken")
        val SESSION_ID = stringPreferencesKey("sessionId")
        val SELF_ID = stringPreferencesKey("selfId")
        val SELF_NAME = stringPreferencesKey("selfName")
        val SELF_AVATAR = stringPreferencesKey("selfAvatarUrl")
    }
}
