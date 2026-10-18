package chat.stoat.core.crypto

import chat.stoat.api.fork.e2ee.E2eeCore
import chat.stoat.api.fork.e2ee.E2eeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.stoat_crypto.CryptoException
import uniffi.stoat_crypto.CryptoMachine
import uniffi.stoat_crypto.StoreConfig
import uniffi.stoat_crypto.Timing
import uniffi.stoat_crypto.destroyStore
import java.io.File

/**
 * Opening and deleting the core's store (plan AN-22, AN-23). The store is a directory
 * (SQLite plus `-wal`/`-shm` and the core's lock file) opened with a raw 32-byte key; the key
 * comes Keystore-wrapped from the app and is never kept here.
 */
object CryptoStores {
    const val KEY_BYTES = 32

    /**
     * Opens the machine of [userId] (ULID) on [deviceId] (= session id). Wipes [key] before
     * returning, whatever happens; the core wipes its own copy.
     *
     * @throws E2eeException.StoreLocked if this process already has it open
     * @throws E2eeException.StoreUnusable wrong key, damaged store, or another account's store
     */
    suspend fun open(userId: String, deviceId: String, directory: File, key: ByteArray): E2eeCore {
        try {
            require(key.size == KEY_BYTES) { "Store key must be 32 bytes" }
            withContext(Dispatchers.IO) { directory.mkdirs() }
            val machine = core { CryptoMachine.open(userId, deviceId, StoreConfig.Sqlite(directory.path, key)) }
            return UniffiE2eeCore(machine)
        } finally {
            key.fill(0)
        }
    }

    /**
     * A machine without persistence, for tests. [ownRetryMs] shortens the core's pause after a
     * failed request of its own (review O-2); the app keeps the core's timing (1 s, doubled per
     * failure, at most 15 min).
     */
    suspend fun openInMemory(userId: String, deviceId: String, ownRetryMs: Long? = null): E2eeCore {
        val machine = core { CryptoMachine.open(userId, deviceId, StoreConfig.Memory) }
        if (ownRetryMs != null) {
            core { machine.setTiming(TEST_TIMING.copy(ownRetryMs = ownRetryMs.toULong())) }
        }
        return UniffiE2eeCore(machine)
    }

    // the core's defaults (stoat-crypto outbox.rs, Timing::default)
    private val TEST_TIMING = Timing(
        keyRequestDelayMs = 3_000u,
        toDeviceRetryMs = 60_000u,
        toDeviceRetryAttempts = 8u,
        ownRetryMs = 1_000u,
    )

    /**
     * Deletes the store of a signed-out account (logout, AN-11). Close its machine first: the
     * core refuses while the store is locked.
     */
    suspend fun destroy(directory: File) = withContext(Dispatchers.IO) {
        try {
            // deleting does not use the key, but the type wants 32 bytes
            destroyStore(StoreConfig.Sqlite(directory.path, ByteArray(KEY_BYTES)))
        } catch (e: CryptoException) {
            throw e.toE2ee()
        }
    }
}
