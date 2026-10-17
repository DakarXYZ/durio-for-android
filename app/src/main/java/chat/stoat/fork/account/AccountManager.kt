package chat.stoat.fork.account

import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import chat.stoat.BuildConfig
import chat.stoat.activities.MainActivity
import chat.stoat.api.StoatAPI
import chat.stoat.api.fork.account.AccountKv
import chat.stoat.api.fork.http.AccountHttp
import chat.stoat.api.fork.routes.fetchSelfUser
import chat.stoat.api.fork.server.DiscoveredServer
import chat.stoat.api.fork.server.ServerDiscovery
import chat.stoat.api.routes.account.UserHints
import chat.stoat.core.model.data.STOAT_FILES
import chat.stoat.core.model.fork.ActiveServer
import chat.stoat.core.model.schemas.User
import chat.stoat.fork.keystore.AndroidKeystoreWrapKey
import chat.stoat.fork.keystore.UnwrapException
import chat.stoat.persistence.Database
import chat.stoat.persistence.stoatKVStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.asLog
import logcat.logcat

/**
 * Accounts on this device (plan AN-08, AN-09). Stage 1 keeps one active account at a time: the
 * global `StoatAPI`, socket and settings belong to it. Switching tears those down without
 * touching the account's data or its session, points the app at the next account's server and
 * files, and restarts the activity task (Q34).
 *
 * Background work for any account (push, notification actions) goes through [registry] and
 * [http] instead of the global state (AN-12, Q35).
 */
object AccountManager {
    data class Session(val token: String, val id: String)

    private lateinit var app: Context
    private lateinit var store: AccountStore
    private lateinit var migration: LegacyAccountMigration

    lateinit var registry: AccountStorageRegistry
        private set

    private val mutex = Mutex()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Switching touches the voice call and Compose state, so it runs on the main thread and
    // outlives any activity
    private val mainScope: CoroutineScope
        get() = ProcessLifecycleOwner.get().lifecycleScope

    private val _state = MutableStateFlow(AccountsState())
    val state: StateFlow<AccountsState> = _state.asStateFlow()

    /** True while switching, adding or logging out; the UI shows progress. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    val active: Account?
        get() = _state.value.active

    @Volatile
    private var activeStorage: AccountStorage? = null

    /** An old install whose migration is postponed: its session still lives in `revolt_kv`. */
    @Volatile
    var legacyMode = false
        private set

    /** Account to go back to when "Add account" is cancelled. */
    private var returnKey: String? = null

    /** Server picked on the login screen, before the account exists (AN-10). */
    var pendingServer: DiscoveredServer? = null
        private set

    // Before login there is no account cache; the socket writes into this throwaway one
    private val noAccountDriver: SqlDriver by lazy {
        AndroidSqliteDriver(Database.Schema, app, null)
    }

    /** SQLDelight driver of the active account (`StoatAPIHost.storage.sqlDriver`). */
    val sqlDriver: SqlDriver
        get() = activeStorage?.sqlDriver ?: noAccountDriver

    /**
     * Call first thing in Application.onCreate: reads the account list (one small file), runs the
     * migration of an old install and points [ActiveServer] and the KV at the active account
     * before any request is made.
     */
    fun init(context: Context) {
        app = context.applicationContext
        store = AccountStore(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                produceFile = { app.preferencesDataStoreFile(AccountStore.FILE) }
            )
        )
        registry = AccountStorageRegistry(app, AndroidKeystoreWrapKey.wrapper())
        migration = LegacyAccountMigration(
            device = app.stoatKVStorage,
            legacyServer = { LegacyServer.load(app) },
            accounts = store,
            storageFor = registry::get,
        )

        runBlocking(Dispatchers.IO) {
            val result = try {
                migration.migrate()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "Account migration failed: ${e.asLog()}" }
                LegacyAccountMigration.Result.Postponed
            }
            if (result is LegacyAccountMigration.Result.Migrated ||
                result is LegacyAccountMigration.Result.Discarded
            ) {
                LegacyServer.clear(app)
            }

            val state = store.load()
            _state.value = state
            activate(state.active)

            if (state.active == null && migration.hasLegacySession()) {
                legacyMode = true
                ActiveServer.set(LegacyServer.load(app))
            }
        }

        // The single-account cache from before AN-08; caches are per account now
        app.deleteDatabase("revolt.db")

        val defaultServer = BuildConfig.DEFAULT_SERVER
        if (!legacyMode && ActiveServer.currentOrNull == null && defaultServer.isNotBlank()) {
            // TODO(AN-10): the login screen discovers the server the user types
            ioScope.launch {
                runCatching {
                    ServerDiscovery.create(allowInsecure = BuildConfig.DEBUG).use { it.discover(defaultServer) }
                }.onSuccess { selectServer(it) }
                    .onFailure { logcat(LogPriority.WARN) { "Could not resolve default server: ${it.asLog()}" } }
            }
        }

        if (legacyMode) {
            ioScope.launch {
                migration.rememberUserId { endpoints, token ->
                    AccountHttp(endpoints, token).use { fetchSelfUser(it).id.orEmpty() }
                }
            }
        }
    }

    /** Token and session ID of the active account, or of a not yet migrated old install. */
    suspend fun session(): Session? = withContext(Dispatchers.IO) { readSession() }

    private suspend fun readSession(): Session? {
        val account = active
        if (account != null) {
            val token = try {
                registry.get(account.key).readToken()
            } catch (e: UnwrapException) {
                // Keystore key lost (data cleared, vendor bugs): the account needs a new login
                logcat(LogPriority.ERROR) { "Token of ${account.key} is unreadable: ${e.asLog()}" }
                null
            } ?: return null
            return Session(token, account.sessionId)
        }
        if (legacyMode) {
            return migration.legacySession()?.let { (token, id) -> Session(token, id) }
        }
        return null
    }

    /** HTTP client for any account, active or not; null if it has no usable token (AN-12, AN-15). */
    suspend fun http(key: String): AccountHttp? {
        val account = _state.value.find(key) ?: return null
        val token = withContext(Dispatchers.IO) {
            try {
                registry.get(key).readToken()
            } catch (e: UnwrapException) {
                null
            }
        } ?: return null
        return AccountHttp(account.endpoints, token)
    }

    /** Everything background code needs for one account (Q35). */
    suspend fun context(key: String): AccountContext? {
        val account = _state.value.find(key) ?: return null
        val http = http(key) ?: return null
        return AccountContext(account, registry.get(key), http)
    }

    /** Login screen: use this server for the next login or sign-up (only without an active account). */
    fun selectServer(server: DiscoveredServer) {
        if (active != null) return
        pendingServer = server
        ActiveServer.set(server.endpoints)
    }

    /**
     * Called by the login and MFA screens once the server issued a session: stores the account,
     * its wrapped token, and makes it active. The caller then logs in as usual.
     */
    suspend fun completeLogin(hints: UserHints): Account = mutex.withLock {
        val server = pendingServer
        val endpoints = server?.endpoints ?: ActiveServer.current
        val key = accountKey(endpoints.api, hints.userId)
        val previous = _state.value.find(key)

        val storage = registry.get(key)
        storage.writeToken(hints.token)

        val account = Account(
            key = key,
            endpoints = endpoints,
            userId = hints.userId,
            sessionId = hints.id,
            displayName = previous?.displayName,
            avatarUrl = previous?.avatarUrl,
            addedAt = previous?.addedAt ?: System.currentTimeMillis(),
            ownServer = server?.info?.features?.e2ee != null || previous?.ownServer == true,
        )
        _state.value = store.upsert(account, makeActive = true)
        activate(account)
        pendingServer = null
        returnKey = null

        if (legacyMode) {
            // logged in again instead of migrating; the old session is not ours to keep
            legacyMode = false
            migration.removeAccountKeys()
            LegacyServer.clear(app)
        }
        account
    }

    /**
     * After `StoatAPI.loginAs` succeeded for the active account: refresh the name and avatar kept
     * with the account.
     */
    fun onLoggedIn() {
        val account = active ?: return
        val self = StoatAPI.selfId?.let { StoatAPI.userCache[it] }
        ioScope.launch {
            if (self != null) updateProfile(account.key, self)
        }
    }

    private suspend fun updateProfile(key: String, user: User) {
        val name = User.resolveDefaultName(user)
        val avatar = user.avatar?.id?.let { "$STOAT_FILES/avatars/$it" }
        mutex.withLock {
            val account = _state.value.find(key) ?: return
            if (account.displayName == name && account.avatarUrl == avatar) return
            _state.value = store.upsert(account.copy(displayName = name, avatarUrl = avatar))
        }
    }

    /** Makes [key] the active account and restarts the UI on it (AN-09). */
    fun switchTo(key: String) {
        mainScope.launch {
            mutex.withLock {
                val target = _state.value.find(key) ?: return@withLock
                if (target.key == active?.key) return@withLock
                _busy.value = true
                teardownActive()
                _state.value = store.setActive(target.key)
                activate(target)
                returnKey = null
                restartUi()
            }
        }
    }

    /** "Add account": leave the active account (it stays signed in) and open the login screen. */
    fun beginAddAccount() {
        mainScope.launch {
            mutex.withLock {
                _busy.value = true
                returnKey = active?.key ?: returnKey
                teardownActive()
                _state.value = store.setActive(null)
                activate(null)
                restartUi()
            }
        }
    }

    /** Accounts to go back to from the login screen ("Add account" cancelled). */
    val canCancelAddAccount: Boolean
        get() = active == null && !legacyMode && _state.value.accounts.isNotEmpty()

    fun cancelAddAccount() {
        val accounts = _state.value.accounts
        val key = returnKey?.takeIf { k -> accounts.any { it.key == k } }
            ?: accounts.maxByOrNull { it.addedAt }?.key
            ?: return
        switchTo(key)
    }

    /**
     * Log out of [key]: delete the account's files and continue with another account or the login
     * screen.
     */
    fun logout(key: String?) {
        mainScope.launch {
            mutex.withLock {
                _busy.value = true
                val account = key?.let { _state.value.find(it) }
                val restart = account == null || account.key == active?.key
                try {
                    when {
                        account != null -> logoutAccount(account)
                        legacyMode -> logoutLegacy()
                        else -> teardownActive()
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    logcat(LogPriority.ERROR) { "Logout failed: ${e.asLog()}" }
                }
                // logging out of a background account leaves the UI as it is
                if (restart) restartUi() else _busy.value = false
            }
        }
    }

    private suspend fun logoutAccount(account: Account) {
        val wasActive = account.key == active?.key
        if (wasActive) {
            teardownActive()
            // nothing may write into the files that are about to go
            activate(null)
        }
        // TODO(AN-11): revoke the session on the server first

        registry.remove(account.key, deleteFiles = true)
        _state.value = store.remove(account.key)

        if (wasActive) {
            val next = _state.value.accounts.maxByOrNull { it.addedAt }
            _state.value = store.setActive(next?.key)
            activate(next)
        }
    }

    private suspend fun logoutLegacy() {
        teardownActive()
        migration.removeAccountKeys()
        LegacyServer.clear(app)
        legacyMode = false
        activate(_state.value.active)
    }

    /**
     * Stops everything that belongs to the active account (AN-09, the table in the plan), without
     * deleting its data or ending its session.
     */
    private suspend fun teardownActive() {
        AccountReset.resetGlobalState()
        pendingServer = null
    }

    private fun activate(account: Account?) {
        val storage = account?.let { registry.get(it.key) }
        activeStorage = storage
        AccountKv.active = storage?.dataStore
        ActiveServer.set(account?.endpoints)
    }

    private fun restartUi() {
        _busy.value = false
        app.startActivity(
            Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }

    /** Push routing and re-subscription see the device's accounts through this. */
    suspend fun accounts(): List<Account> = store.load().accounts
}

/** One account for background work: its record, files and HTTP client (Q35). */
class AccountContext(
    val account: Account,
    val storage: AccountStorage,
    val http: AccountHttp,
) : AutoCloseable {
    override fun close() = http.close()
}

/**
 * HTTP for the account with this key, whether active or not (AN-12, AN-15); null if the account
 * is unknown or its token cannot be read. Close it when done.
 */
suspend fun AccountHttp(key: String): AccountHttp? = AccountManager.http(key)
