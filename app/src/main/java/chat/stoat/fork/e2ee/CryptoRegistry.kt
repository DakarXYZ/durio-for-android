package chat.stoat.fork.e2ee

import chat.stoat.api.fork.e2ee.E2eeCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The crypto cores of this process, one per account (plan AN-23). The UI, push handling and
 * notification actions run in one process and must share an instance: two machines on one store
 * would corrupt the Olm state (the core's file lock refuses the second, `StoreLocked`).
 *
 * Reference counted: [acquire] opens on first use, [release] closes when the last user is done
 * — at once for an account switch, after [idleCloseMs] for background work (a burst of pushes
 * should not reopen the store every time).
 */
class CryptoRegistry(
    private val scope: CoroutineScope,
    private val idleCloseMs: Long = 60_000,
    private val open: suspend (accountKey: String) -> E2eeCore,
    private val destroyStore: suspend (accountKey: String) -> Unit,
) {
    private class Entry(val core: E2eeCore) {
        var refs = 0
        var idle: Job? = null
    }

    private val mutex = Mutex()
    private val entries = HashMap<String, Entry>()

    /** The account's core, opened if needed. Every acquire needs one [release]. */
    suspend fun acquire(accountKey: String): E2eeCore = mutex.withLock {
        val entry = entries[accountKey] ?: Entry(open(accountKey)).also { entries[accountKey] = it }
        entry.idle?.cancel()
        entry.idle = null
        entry.refs++
        entry.core
    }

    suspend fun release(accountKey: String, closeNow: Boolean = false) = mutex.withLock {
        val entry = entries[accountKey] ?: return@withLock
        entry.refs = (entry.refs - 1).coerceAtLeast(0)
        if (entry.refs > 0) return@withLock
        if (closeNow) {
            close(accountKey, entry)
        } else {
            entry.idle?.cancel()
            entry.idle = scope.launch {
                delay(idleCloseMs)
                mutex.withLock {
                    if (entry.refs == 0 && entries[accountKey] === entry) close(accountKey, entry)
                }
            }
        }
    }

    /** Uses the account's core for [block] (push, notification actions). */
    suspend fun <T> withCore(accountKey: String, block: suspend (E2eeCore) -> T): T {
        val core = acquire(accountKey)
        try {
            return block(core)
        } finally {
            release(accountKey)
        }
    }

    fun isOpen(accountKey: String): Boolean = entries.containsKey(accountKey)

    /**
     * Logout or device reset: closes the core whoever holds it, then deletes its store. Stop
     * its users (the account's `E2eeSync`) first.
     */
    suspend fun destroy(accountKey: String) = mutex.withLock {
        entries[accountKey]?.let { close(accountKey, it) }
        destroyStore(accountKey)
    }

    private fun close(accountKey: String, entry: Entry) {
        entry.idle?.cancel()
        entries.remove(accountKey)
        entry.core.close()
    }
}
