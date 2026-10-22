package chat.stoat.fork.e2ee

import chat.stoat.fork.keystore.KeyWrapper
import chat.stoat.fork.keystore.UnwrapException
import java.io.File
import java.io.IOException
import java.security.SecureRandom

/**
 * Keys of the accounts' crypto stores (plan AN-22). Each account has its own random 32-byte
 * data key, kept only wrapped by the device's Keystore key (the same [KeyWrapper] as the session
 * tokens: no user authentication, usable while locked, so push can decrypt in the background).
 *
 * Files, all in `noBackupFilesDir` (never in a backup, Q40):
 * - `keys/<accountKey>.bin` — the wrapped data key (`version | IV | ciphertext`, AesGcmKeyWrapper);
 * - `crypto/<accountKey>/` — the core's SQLite store (plus `-wal`, `-shm`, its lock file);
 * - `keys/<accountKey>.session` — the session (= device, C22) the store was made for.
 *
 * Unwrapping can be slow (StrongBox), so a key is unwrapped once per process and kept in memory
 * until the account goes ([forget]). Callers get copies and wipe them after use.
 */
class E2eeStoreKeys(
    private val baseDir: File,
    private val wrapper: KeyWrapper,
    private val random: SecureRandom = SecureRandom(),
) {
    private val cache = HashMap<String, ByteArray>()

    fun storeDir(account: String) = File(baseDir, "crypto/$account")

    fun keyFile(account: String) = File(baseDir, "keys/$account.bin")

    fun sessionFile(account: String) = File(baseDir, "keys/$account.session")

    /** A store exists if its directory has anything in it. */
    fun storeExists(account: String): Boolean = storeDir(account).list()?.isNotEmpty() == true

    /**
     * Whether the account's store (and its key) belong to another session than [sessionId]: the
     * account logged in again while they stayed (a login without logout, a logout that could not
     * delete them). Device = session (C22), so they can never open as this device. A store
     * without the note (made before the note existed) counts as this session's.
     */
    fun madeForOtherSession(account: String, sessionId: String): Boolean {
        if (!storeExists(account) && !keyFile(account).isFile) return false
        val made = sessionFile(account).takeIf { it.isFile }?.readText()?.trim().orEmpty()
        return made.isNotEmpty() && made != sessionId
    }

    /** Notes the session the account's store was opened for (not a secret: an id). */
    fun rememberSession(account: String, sessionId: String) {
        val file = sessionFile(account)
        if (file.isFile && file.readText().trim() == sessionId) return
        writeAtomically(file, sessionId.toByteArray(Charsets.UTF_8))
    }

    /**
     * A copy of the account's data key, created on first use. The caller wipes it
     * (`CryptoStores.open` does).
     *
     * @throws E2eeKeyLostException if the Keystore key is gone or the file is damaged (data
     *   cleared, restored to another device, vendor bugs), or the store exists without its key:
     *   the store cannot be opened again (AN-42). Never replaced silently.
     */
    @Synchronized
    fun dataKey(account: String): ByteArray {
        cache[account]?.let { return it.copyOf() }

        val file = keyFile(account)
        val key = if (file.isFile) {
            val unwrapped = try {
                wrapper.unwrap(file.readBytes())
            } catch (e: UnwrapException) {
                throw E2eeKeyLostException(e)
            }
            if (unwrapped.size != KEY_BYTES) {
                unwrapped.fill(0)
                throw E2eeKeyLostException(null)
            }
            unwrapped
        } else {
            if (storeExists(account)) throw E2eeKeyLostException(null)
            ByteArray(KEY_BYTES).also { fresh ->
                random.nextBytes(fresh)
                writeAtomically(file, wrapper.wrap(fresh))
            }
        }
        cache[account] = key
        return key.copyOf()
    }

    /** Wipes the cached key (account removed, or its store reset). */
    @Synchronized
    fun forget(account: String) {
        cache.remove(account)?.fill(0)
    }

    /** Logout or reset: the key goes; the store must be destroyed too (it is unreadable now). */
    @Synchronized
    fun delete(account: String) {
        forget(account)
        keyFile(account).delete()
        sessionFile(account).delete()
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Could not store the key of ${file.name}")
        }
    }

    companion object {
        const val KEY_BYTES = 32
    }
}

/** The account's crypto store cannot be opened any more; the device must be reset (AN-42). */
class E2eeKeyLostException(cause: Throwable?) : Exception("E2EE store key is lost", cause)
