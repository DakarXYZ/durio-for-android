package chat.stoat.fork.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import chat.stoat.api.StoatJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import logcat.LogPriority
import logcat.asLog
import logcat.logcat

/** What [AccountStore] holds: every account on this device and which one is active. */
@Serializable
data class AccountsState(
    val accounts: List<Account> = emptyList(),
    val activeKey: String? = null,
) {
    val active: Account?
        get() = accounts.firstOrNull { it.key == activeKey }

    fun find(key: String): Account? = accounts.firstOrNull { it.key == key }
}

/**
 * The global `accounts` DataStore (plan AN-08): the list of accounts and the active key.
 * Tokens are never stored here.
 */
class AccountStore(private val dataStore: DataStore<Preferences>) {
    val state: Flow<AccountsState> = dataStore.data.map { it.toState() }

    suspend fun load(): AccountsState = state.first()

    /** Adds the account or replaces the one with the same key. */
    suspend fun upsert(account: Account, makeActive: Boolean = false): AccountsState =
        update { state ->
            val others = state.accounts.filterNot { it.key == account.key }
            state.copy(
                accounts = others + account,
                activeKey = if (makeActive) account.key else state.activeKey,
            )
        }

    suspend fun remove(key: String): AccountsState = update { state ->
        state.copy(
            accounts = state.accounts.filterNot { it.key == key },
            activeKey = state.activeKey.takeUnless { it == key },
        )
    }

    suspend fun setActive(key: String?): AccountsState = update { state ->
        require(key == null || state.find(key) != null) { "Unknown account $key" }
        state.copy(activeKey = key)
    }

    private suspend fun update(transform: (AccountsState) -> AccountsState): AccountsState {
        var result = AccountsState()
        dataStore.edit { preferences ->
            result = transform(preferences.toState())
            preferences[ACCOUNTS] = StoatJson.encodeToString(ACCOUNT_LIST, result.accounts)
            val active = result.activeKey
            if (active != null) preferences[ACTIVE] = active else preferences.remove(ACTIVE)
        }
        return result
    }

    private fun Preferences.toState(): AccountsState {
        val accounts = this[ACCOUNTS]?.let { json ->
            runCatching { StoatJson.decodeFromString(ACCOUNT_LIST, json) }
                .onFailure { logcat(LogPriority.ERROR) { "Account list is unreadable: ${it.asLog()}" } }
                .getOrNull()
        } ?: emptyList()
        return AccountsState(accounts, this[ACTIVE]?.takeIf { key -> accounts.any { it.key == key } })
    }

    companion object {
        const val FILE = "accounts"
        private val ACCOUNTS = stringPreferencesKey("accounts")
        private val ACTIVE = stringPreferencesKey("activeKey")
        private val ACCOUNT_LIST = ListSerializer(Account.serializer())
    }
}
