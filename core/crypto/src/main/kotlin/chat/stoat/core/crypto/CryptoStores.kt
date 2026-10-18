package chat.stoat.core.crypto

import chat.stoat.api.fork.e2ee.E2eeCore
import chat.stoat.api.fork.e2ee.E2eeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.stoat_crypto.CryptoException
import uniffi.stoat_crypto.CryptoMachine
import uniffi.stoat_crypto.StoreConfig
import uniffi.stoat_crypto.Timing
import uniffi.stoat_crypto.coreVersion as libraryVersion
import uniffi.stoat_crypto.destroyStore
import java.io.File

/**
 * Opening and deleting the core's store (plan AN-22, AN-23). The store is a directory
 * (SQLite plus `-wal`/`-shm` and the core's lock file) opened with a raw 32-byte key; the key
 * comes Keystore-wrapped from the app and is never kept here.
 */
object CryptoStores {
    const val KEY_BYTES = 32

    /** Version of the core library in the APK (semver of the stoat-crypto crates, CR-23). */
    fun coreVersion(): String = libraryVersion()

    /**
     * Opens the machine of [userId] (ULID) on [deviceId] (= session id). Wipes [key] before
     * returning, whatever happens; the core wipes its own copy.
     *
     * CR-23: the store's directory remembers the newest core that opened it ([VERSION_FILE]); a
     * store of a newer core is not opened by an older one (the app was downgraded) — the
     * library's migrations only go forward.
     *
     * @throws E2eeException.StoreLocked if this process already has it open
     * @throws E2eeException.StoreUnusable wrong key, damaged store, or another account's store
     * @throws E2eeException.StoreTooNew written by a newer core: update the app
     */
    suspend fun open(userId: String, deviceId: String, directory: File, key: ByteArray): E2eeCore {
        try {
            require(key.size == KEY_BYTES) { "Store key must be 32 bytes" }
            val current = coreVersion()
            val marker = File(directory, VERSION_FILE)
            withContext(Dispatchers.IO) {
                directory.mkdirs()
                val stored = if (marker.isFile) marker.readText().trim() else null
                if (stored != null && isNewer(stored, current)) throw E2eeException.StoreTooNew()
            }
            val machine = core { CryptoMachine.open(userId, deviceId, StoreConfig.Sqlite(directory.path, key)) }
            withContext(Dispatchers.IO) { rememberVersion(marker, current) }
            return UniffiE2eeCore(machine)
        } finally {
            key.fill(0)
        }
    }

    /** Name of the file in a store's directory with the newest core version that opened it. */
    const val VERSION_FILE = "core-version"

    /** Whether semver [a] is newer than [b]; an unreadable version never blocks. */
    fun isNewer(a: String, b: String): Boolean {
        val x = semver(a) ?: return false
        val y = semver(b) ?: return false
        for (i in 0 until 3) if (x[i] != y[i]) return x[i] > y[i]
        return false
    }

    // "1.2.3" (pre-release or build suffix ignored) → [1, 2, 3]
    private fun semver(version: String): List<Int>? {
        val parts = version.substringBefore('-').substringBefore('+').split('.')
        if (parts.size != 3) return null
        return parts.map { it.toIntOrNull() ?: return null }
    }

    private fun rememberVersion(marker: File, version: String) {
        val stored = if (marker.isFile) marker.readText().trim() else null
        if (stored == null || isNewer(version, stored)) {
            // best effort: without the marker the next open just tries the store
            runCatching { marker.writeText(version) }
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
            File(directory, VERSION_FILE).delete()
            // deleting does not use the key, but the type wants 32 bytes
            destroyStore(StoreConfig.Sqlite(directory.path, ByteArray(KEY_BYTES)))
        } catch (e: CryptoException) {
            throw e.toE2ee()
        }
    }
}
