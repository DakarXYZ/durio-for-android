package chat.stoat.api.fork.e2ee

import chat.stoat.api.StoatJson
import chat.stoat.api.fork.http.ApiClient
import chat.stoat.api.fork.routes.e2ee.E2eeResponse
import chat.stoat.api.fork.routes.e2ee.E2eeRoutes
import chat.stoat.api.fork.routes.e2ee.fetchBackupKeys
import chat.stoat.api.fork.routes.e2ee.fetchBackupVersion
import chat.stoat.api.fork.routes.e2ee.fetchE2eeSecret
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The key backup of one account (plan AN-32, host contract "Key backup and recovery" in
 * `stoat-crypto/crates/stoat-crypto/src/lib.rs`), over the account's own [client] and its
 * [sync] (one actor per account). The recovery key is the secret storage key (ADR A4): a
 * rotation keeps it, and it unlocks the backup on a new device.
 *
 * Setup: [create] → show the key → [activate] (MFA ticket when a backup exists) → the secrets
 * and key uploads leave with the normal passes. Restore: [unlock] → [restore] → messages
 * waiting for keys are decrypted again by the caller.
 */
class E2eeBackup(
    private val core: E2eeCore,
    private val sync: E2eeSync,
    private val client: ApiClient,
) {
    /** A new recovery key (and backup); nothing changes on the server until [activate]. */
    suspend fun create(): E2eeRecoverySetup = core.createRecovery()

    /** A new backup under the same recovery key (after a suspected leak); needs MFA. */
    suspend fun rotate(): E2eeRecoverySetup = core.rotateBackup()

    /**
     * Creates the backup version on the server: the backup is on once this returns.
     *
     * @throws E2eeException.ActionRequired [E2eeAction.MfaRequired]: a backup exists, call
     *   again with an MFA ticket
     * @throws E2eeRequestException the server refused or did not answer
     */
    suspend fun activate(setup: E2eeRecoverySetup, mfaTicket: String? = null) = sync.runNow(setup.request, mfaTicket)

    /** Whether the server holds a backup of this account (404: none). */
    suspend fun exists(): Boolean {
        val response = fetchBackupVersion(client)
        return when {
            response.isSuccess -> true
            response.status == 404 -> false
            else -> throw E2eeRequestException(response.status)
        }
    }

    /**
     * Unlocks the server's backup on this device with the recovery key or passphrase ([input],
     * UTF-8; wiped here whatever happens). From now on this device uploads its keys too.
     * Returns the backup version for [restore].
     *
     * @throws E2eeException.NoBackup the server has no backup (or no recovery key for it)
     * @throws E2eeException.RecoveryKeyTypo, E2eeException.WrongRecoveryKey,
     *   E2eeException.BackupKeyMismatch
     * @throws E2eeRequestException the server did not answer
     */
    suspend fun unlock(input: ByteArray): String {
        try {
            val defaultKey = get { fetchE2eeSecret(client, E2eeRoutes.SECRET_DEFAULT_KEY) }
            val keyId = try {
                StoatJson.parseToJsonElement(defaultKey).jsonObject["key"]?.jsonPrimitive?.content
            } catch (e: Exception) {
                null
            } ?: throw E2eeException.BackupKeyMismatch()
            val secrets = E2eeRecoverySecrets(
                defaultKey = defaultKey,
                keyDescription = get { fetchE2eeSecret(client, E2eeRoutes.SECRET_KEY_PREFIX + keyId) },
                backupSecret = get { fetchE2eeSecret(client, E2eeRoutes.SECRET_MEGOLM_BACKUP) },
                backupVersion = get { fetchBackupVersion(client) },
            )
            val version = core.unlockBackup(input, secrets)
            // uploads of this device go to the unlocked version from now on
            sync.requestPass()
            return version
        } finally {
            input.fill(0)
        }
    }

    /**
     * Downloads the backup's keys and imports them in batches, reporting [onProgress] after each.
     * An interrupted restore can simply run again: an import never makes a key worse.
     */
    suspend fun restore(version: String, onProgress: (E2eeRestoreProgress) -> Unit = {}): E2eeRestoreProgress {
        val body = get { fetchBackupKeys(client, version) }
        core.restoreBackup(body).use { restore ->
            var progress = restore.progress()
            onProgress(progress)
            while (!progress.finished) {
                progress = restore.next()
                onProgress(progress)
            }
            return progress
        }
    }

    private suspend fun get(call: suspend () -> E2eeResponse): String {
        val response = try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw E2eeRequestException(0)
        }
        return when {
            response.isSuccess -> response.body
            response.status == 404 -> throw E2eeException.NoBackup()
            else -> throw E2eeRequestException(response.status)
        }
    }
}
