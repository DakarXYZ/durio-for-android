package chat.stoat.fork.account

import android.content.Context
import chat.stoat.fork.keystore.KeyWrapper
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide owner of [AccountStorage] instances (plan AN-08): one per account key, shared by
 * the UI, push handling and notification actions, so no file is ever opened twice. Not a Koin
 * scope: a scope cannot be safely reopened under live ViewModels.
 */
class AccountStorageRegistry(
    private val context: Context,
    private val wrapper: KeyWrapper,
    private val factory: (Context, String, KeyWrapper) -> AccountStorage = { ctx, key, w ->
        AccountStorage(ctx, key, w)
    },
) {
    private val storages = ConcurrentHashMap<String, AccountStorage>()

    fun get(key: String): AccountStorage =
        storages.computeIfAbsent(key) { factory(context, it, wrapper) }

    fun isOpen(key: String): Boolean = storages.containsKey(key)

    /** Closes the account's storage; with [deleteFiles] its files go too (logout, AN-11). */
    suspend fun remove(key: String, deleteFiles: Boolean) {
        storages.remove(key)?.close()
        if (deleteFiles) AccountStorage.deleteFiles(context, key)
    }
}
