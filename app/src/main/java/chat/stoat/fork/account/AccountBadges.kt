package chat.stoat.fork.account

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the account switcher shows next to accounts that are not active (plan AN-10, AN-12):
 * messages that came by push since the account was last open (there is no socket for them) and
 * "log in again" after the server rejected the account's token. Kept in the account's `kv_<key>`.
 */
data class AccountBadge(val unread: Int = 0, val sessionExpired: Boolean = false)

object AccountBadges {
    private const val UNREAD = "durio/pushUnread"
    private const val EXPIRED = "durio/sessionExpired"

    private val _badges = MutableStateFlow<Map<String, AccountBadge>>(emptyMap())
    val badges: StateFlow<Map<String, AccountBadge>> = _badges.asStateFlow()

    suspend fun load(storages: List<AccountStorage>) {
        _badges.value = storages.associate { storage ->
            storage.key to AccountBadge(
                unread = storage.kv.getInt(UNREAD) ?: 0,
                sessionExpired = storage.kv.getBoolean(EXPIRED) == true,
            )
        }
    }

    suspend fun addUnread(storage: AccountStorage) {
        val unread = (storage.kv.getInt(UNREAD) ?: 0) + 1
        storage.kv.set(UNREAD, unread)
        put(storage.key) { it.copy(unread = unread) }
    }

    suspend fun clearUnread(storage: AccountStorage) {
        storage.kv.remove(UNREAD)
        put(storage.key) { it.copy(unread = 0) }
    }

    suspend fun setSessionExpired(storage: AccountStorage, expired: Boolean) {
        if (expired) storage.kv.set(EXPIRED, true) else storage.kv.remove(EXPIRED)
        put(storage.key) { it.copy(sessionExpired = expired) }
    }

    fun forget(key: String) {
        _badges.update { it - key }
    }

    private fun put(key: String, change: (AccountBadge) -> AccountBadge) {
        _badges.update { it + (key to change(it[key] ?: AccountBadge())) }
    }
}
