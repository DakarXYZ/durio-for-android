package chat.stoat.fork.e2ee

import android.content.Context
import chat.stoat.api.fork.e2ee.E2eeCore
import chat.stoat.api.fork.e2ee.E2eeException
import chat.stoat.api.fork.e2ee.E2eeFrames
import chat.stoat.api.fork.e2ee.E2eeChannels
import chat.stoat.api.fork.e2ee.E2eeLimits
import chat.stoat.api.fork.e2ee.E2eeMessages
import chat.stoat.api.fork.e2ee.MessageDecryptor
import chat.stoat.api.fork.e2ee.E2eeSocketListener
import chat.stoat.api.fork.e2ee.E2eeSync
import chat.stoat.api.fork.e2ee.E2eeSyncEvent
import chat.stoat.api.fork.http.AccountHttp
import chat.stoat.api.fork.routes.AccountRequestException
import chat.stoat.api.fork.routes.fetchServerRoot
import chat.stoat.api.realtime.DisconnectionState
import chat.stoat.api.realtime.RealtimeSocket
import chat.stoat.core.crypto.CryptoStores
import chat.stoat.core.crypto.UniffiAttachmentCrypto
import chat.stoat.api.fork.e2ee.E2eeAttachments
import chat.stoat.fork.e2ee.media.EncryptedMedia
import chat.stoat.fork.account.Account
import chat.stoat.fork.account.AccountManager
import chat.stoat.fork.keystore.KeyWrapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.logcat

/** E2EE of the active account, for the UI (AN-30, AN-42) and the send path (AN-26). */
sealed interface E2eeStatus {
    /** No account, a server without E2EE (or not ours), or not started yet. */
    data object Off : E2eeStatus

    data object Starting : E2eeStatus

    data object Ready : E2eeStatus

    /** The server could not be asked; retried on the next socket `Ready`. */
    data object Offline : E2eeStatus

    /** The session id of this login is unknown (an install from before stage 1): log in again. */
    data object NoDevice : E2eeStatus

    /** The store key or the store is gone (AN-42): the device must be reset. */
    data object KeyLost : E2eeStatus

    /** The server rejected the token. */
    data object SessionExpired : E2eeStatus

    /** Anything else; [code] names it, never with content. */
    data class Failed(val code: String) : E2eeStatus
}

/**
 * The active account's E2EE device for the screens of AN-30..32: its core, sync and HTTP. Valid
 * while [E2eeManager.status] is [E2eeStatus.Ready] for [account]; do not keep it longer.
 */
class E2eeActive(val account: Account, val core: E2eeCore, val sync: E2eeSync, val http: AccountHttp)

/**
 * The E2EE device of each account (plan AN-23): opens the crypto core after login of the active
 * account (if its server has `features.e2ee.enabled`), runs its [E2eeSync] and hears the socket
 * for it (AN-24); stops it on account switch, destroys the store on logout (AN-11).
 *
 * Device = session (C22): `deviceId` is the account's session id, `userId` its user ULID. The
 * cores are owned by [registry] (one per account and process); push handling (AN-29) borrows
 * them from there for accounts that are not active.
 */
object E2eeManager : E2eeSocketListener {
    private class Session(
        val account: Account,
        val core: E2eeCore,
        val http: AccountHttp,
        val sync: E2eeSync,
        val job: Job,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private lateinit var keys: E2eeStoreKeys

    @Volatile
    private var appContext: Context? = null

    lateinit var registry: CryptoRegistry
        private set

    // the account E2EE should run for: set on login, cleared on switch/logout
    @Volatile
    private var wanted: Account? = null

    @Volatile
    private var session: Session? = null

    @Volatile
    private var startJob: Job? = null

    private val _status = MutableStateFlow<E2eeStatus>(E2eeStatus.Off)
    val status: StateFlow<E2eeStatus> = _status.asStateFlow()

    private val _events = MutableSharedFlow<E2eeSyncEvent>(extraBufferCapacity = 64)

    /** Room keys (re-decrypt, AN-25), verifications (AN-31), actions for the UI (AN-32, AN-42). */
    val events: SharedFlow<E2eeSyncEvent> = _events.asSharedFlow()

    /**
     * Whether a DM/group is encrypted: only those get their member lists into the core (the core
     * then pins the channel as encrypted, AN-39).
     */
    @Volatile
    var isEncryptedChannel: (channelId: String) -> Boolean = { E2eeChannels.isEncrypted(it) }

    /** The active account's sync, for the send path (AN-26); null without E2EE. */
    val activeSync: E2eeSync?
        get() = session?.sync

    /** The active account's device for the settings, verification and backup (AN-30..32). */
    val active: E2eeActive?
        get() = session?.let { E2eeActive(it.account, it.core, it.sync, it.http) }

    /**
     * The core and sync of [accountKey] for one piece of work (send, edit, push, notification
     * reply; AN-26, AN-29, AN-43): the running session of the active account, else the account's
     * core borrowed from [registry] with a sync of its own over the account's HTTP (no loop: the
     * work drives it). The borrowed core closes after the registry's idle time.
     *
     * @throws E2eeUnavailableException the account has no E2EE device (not a dur.io server,
     *   unknown session, no token)
     */
    suspend fun <T> withSession(accountKey: String, block: suspend (E2eeCore, E2eeSync) -> T): T {
        session?.takeIf { it.account.key == accountKey }?.let { return block(it.core, it.sync) }

        val account = AccountManager.state.value.find(accountKey)
        if (account == null || !account.ownServer || account.sessionId.isBlank()) throw E2eeUnavailableException()
        val http = AccountManager.http(accountKey) ?: throw E2eeUnavailableException()
        val job = SupervisorJob()
        try {
            return registry.withCore(accountKey) { core ->
                core.setServerLimits(E2eeLimits(DEFAULT_TO_DEVICE_BATCH, DEFAULT_REQUEST_BYTES))
                block(core, E2eeSync(core, http, CoroutineScope(Dispatchers.IO + job)))
            }
        } catch (e: E2eeKeyLostException) {
            throw E2eeUnavailableException(e)
        } catch (e: E2eeException.StoreUnusable) {
            throw E2eeUnavailableException(e)
        } finally {
            job.cancel()
            http.close()
        }
    }

    /** Called from `AccountManager.init`, before any account starts. */
    fun init(context: Context, wrapper: KeyWrapper) {
        keys = E2eeStoreKeys(context.noBackupFilesDir, wrapper)
        registry = CryptoRegistry(
            scope = scope,
            open = ::openCore,
            destroyStore = { key ->
                CryptoStores.destroy(keys.storeDir(key))
                keys.delete(key)
            },
        )
        E2eeFrames.listener = this
        // attachments need no account: the stateless half of the core (AN-28)
        E2eeAttachments.crypto = UniffiAttachmentCrypto
        appContext = context.applicationContext
        E2eePrivacy.init(context)
        E2eeVerifications.init(context)
    }

    private suspend fun openCore(accountKey: String): E2eeCore {
        val account = AccountManager.state.value.find(accountKey)
            ?: throw IllegalStateException("No account for this key")
        return CryptoStores.open(account.userId, account.sessionId, keys.storeDir(accountKey), keys.dataKey(accountKey))
    }

    /** After `StoatAPI.loginAs` of the active account ([AccountManager.onLoggedIn]). */
    fun start(account: Account) {
        wanted = account
        launchStart()
    }

    private fun launchStart() {
        startJob?.cancel()
        startJob = scope.launch { mutex.withLock { startLocked() } }
    }

    /** Account switch or "add account": the machine is closed, its files stay (AN-09). */
    suspend fun stop() {
        wanted = null
        // a start still waiting for the server must not hold up the switch
        startJob?.cancel()
        mutex.withLock { stopLocked() }
        _status.value = E2eeStatus.Off
        // a restore of the old account's backup stops with it
        E2eeRecovery.reset()
        // nothing of the old account stays: decrypted images, downloaded ciphertext
        appContext?.let { EncryptedMedia.clearCache(it) }
    }

    /** Logout (AN-11): stop, close the machine and delete its store and key. */
    suspend fun destroy(accountKey: String) {
        if (wanted?.key == accountKey) {
            wanted = null
            startJob?.cancel()
        }
        mutex.withLock {
            if (session?.account?.key == accountKey) {
                stopLocked()
                _status.value = E2eeStatus.Off
                E2eeRecovery.reset()
            }
            try {
                registry.destroy(accountKey)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // the account's files are deleted anyway (AccountStorage.deleteFiles)
                logcat(LogPriority.WARN) { "E2EE store not destroyed: ${e::class.simpleName}" }
            }
            keys.delete(accountKey)
        }
        appContext?.let { EncryptedMedia.clearCache(it) }
    }

    private suspend fun startLocked() {
        val account = wanted ?: return
        if (session?.account?.key == account.key) return
        stopLocked()

        // only dur.io servers announce features.e2ee (O12)
        if (!account.ownServer) {
            _status.value = E2eeStatus.Off
            return
        }
        if (account.sessionId.isBlank()) {
            _status.value = E2eeStatus.NoDevice
            return
        }

        _status.value = E2eeStatus.Starting
        val http = AccountManager.http(account.key)
        if (http == null) {
            _status.value = E2eeStatus.SessionExpired
            return
        }

        val feature = try {
            fetchServerRoot(http).features?.e2ee
        } catch (e: Exception) {
            http.close()
            if (e is CancellationException) throw e
            _status.value = if (e is AccountRequestException && e.status == 401) {
                E2eeStatus.SessionExpired
            } else {
                E2eeStatus.Offline
            }
            return
        }
        if (feature?.enabled != true) {
            http.close()
            _status.value = E2eeStatus.Off
            return
        }

        // Opening and the limits are not cancelled halfway (stop() cancels a slow start): an
        // open store is released again rather than left locked until the process dies
        var acquired: E2eeCore? = null
        val core = try {
            withContext(NonCancellable) {
                val opened = registry.acquire(account.key).also { acquired = it }
                opened.setServerLimits(
                    E2eeLimits(
                        maxToDeviceBatch = feature.maxToDeviceBatch ?: DEFAULT_TO_DEVICE_BATCH,
                        maxRequestBytes = feature.maxRequestBytes ?: DEFAULT_REQUEST_BYTES,
                    )
                )
                opened
            }.also { currentCoroutineContext().ensureActive() }
        } catch (e: Exception) {
            if (acquired != null) withContext(NonCancellable) { registry.release(account.key, closeNow = true) }
            http.close()
            if (e is CancellationException) throw e
            _status.value = when (e) {
                is E2eeKeyLostException, is E2eeException.StoreUnusable -> E2eeStatus.KeyLost
                else -> E2eeStatus.Failed(e::class.simpleName ?: "Unknown")
            }
            logcat(LogPriority.ERROR) { "E2EE device not opened: ${e::class.simpleName}" }
            return
        }

        val job = SupervisorJob(scope.coroutineContext[Job])
        val sessionScope = CoroutineScope(scope.coroutineContext + job)
        val sync = E2eeSync(core, http, sessionScope)
        sessionScope.launch(start = CoroutineStart.UNDISPATCHED) {
            sync.events.collect { event ->
                _events.emit(event)
                when (event) {
                    // messages waiting for these keys are decrypted and re-shown (AN-25)
                    is E2eeSyncEvent.RoomKeys -> E2eeMessages.onRoomKeys(event.keys)
                    E2eeSyncEvent.Unauthorized -> _status.value = E2eeStatus.SessionExpired
                    else -> Unit
                }
            }
        }
        session = Session(account, core, http, sync, job)
        sync.start()
        _status.value = E2eeStatus.Ready
        // messages that came in before the device was ready are decrypted now
        sessionScope.launch { E2eeMessages.install(MessageDecryptor(core)) }

        // Ready came before we were here: the contract wants it after every connect
        if (RealtimeSocket.disconnectionState == DisconnectionState.Connected) onReady()
    }

    /** Members of every encrypted DM/group known so far (contract step 5). */
    private fun feedMembers(sync: E2eeSync) {
        for ((channelId, members) in E2eeChannels.encryptedWithMembers()) sync.onChannelMembers(channelId, members)
    }

    private suspend fun stopLocked() {
        val current = session ?: return
        session = null
        // an open verification holds the core (and its store lock) until closed
        E2eeVerifications.controller.clear()
        E2eeMessages.install(null)
        current.sync.stop()
        current.job.cancel()
        current.http.close()
        registry.release(current.account.key, closeNow = true)
    }

    // ---- socket (AN-24), called on realtimeContext: hand over, never wait for the network

    override fun onReady() {
        val current = session
        if (current != null) {
            current.sync.onReady()
            feedMembers(current.sync)
            return
        }
        // offline at login: try again now that the server answers
        val status = _status.value
        if (wanted != null && (status == E2eeStatus.Offline || status is E2eeStatus.Failed)) launchStart()
    }

    override fun onToDevice(recipientDevice: String) {
        val current = session ?: return
        if (recipientDevice == current.core.deviceId) current.sync.onToDevice()
    }

    override fun onDeviceListUpdate(user: String) {
        session?.sync?.onDeviceListUpdate(user)
    }

    override fun onKeysLow() {
        session?.sync?.onKeysLow()
    }

    override fun onChannelMembers(channelId: String, members: List<String>) {
        val current = session ?: return
        if (isEncryptedChannel(channelId)) current.sync.onChannelMembers(channelId, members)
    }

    // backend defaults (Revolt.toml [features.e2ee]) for a server that does not send them
    private const val DEFAULT_TO_DEVICE_BATCH = 1000
    private const val DEFAULT_REQUEST_BYTES = 4 * 1024 * 1024
}
