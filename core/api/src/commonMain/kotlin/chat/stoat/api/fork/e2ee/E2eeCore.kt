package chat.stoat.api.fork.e2ee

/**
 * The crypto core of one account as the rest of the app sees it (plan AN-20). Implemented by
 * `:core:crypto` over the UniFFI bindings of stoat-crypto; `:core:api` never sees JNA or UniFFI
 * types, so it stays KMP-friendly and testable with a fake.
 *
 * The core does no HTTP and has no timers: [E2eeSync] runs its requests and schedules its passes
 * (host contract in `stoat-crypto/crates/stoat-crypto/src/lib.rs`). One instance per account and
 * process; [close] releases the store lock.
 *
 * Decrypt/encrypt (AN-25/26), devices and trust (AN-30), SAS (AN-31), the key backup (AN-32), key
 * files (CR-21) and the diagnostic report (CR-22).
 */
interface E2eeCore : AutoCloseable {
    val userId: String
    val deviceId: String

    /** `GET /` → `features.e2ee`; call once after opening. */
    suspend fun setServerLimits(limits: E2eeLimits)

    /** The request pass: every request at most once at a time (ids in flight). */
    suspend fun outgoingRequests(): List<E2eeRequest>

    /** 2xx; [body] is the response JSON, empty for 204. */
    suspend fun markRequestSent(request: E2eeRequest, body: String)

    /**
     * Non-2xx, or no response at all ([status] 0): the real status, the core tells permanent
     * refusals (4xx other than 401, 408, 429) from the rest.
     * @throws E2eeException.ActionRequired when the UI has to act (MFA, backup replaced, key conflict)
     * @throws E2eeException.RequestRejected once, when a request of the core itself was refused
     *   for good and dropped (review O-2): a "sync problem" notice, not a retry
     */
    suspend fun markRequestFailed(request: E2eeRequest, status: Int)

    /** When to run the next pass without any other event; null: nothing scheduled. */
    suspend fun wakeupInMs(): Long?

    /** `GET /e2ee/to_device?limit=N` response body. */
    suspend fun receiveToDevice(body: String): ToDeviceOutcome

    /** `POST /e2ee/to_device/ack` succeeded for these ids. */
    suspend fun markAckSent(ids: List<String>)

    /** `E2eeDeviceListUpdate {user}`. */
    suspend fun receiveDeviceListUpdate(users: List<String>)

    /** Socket `Ready` (every connect, including the first after opening). */
    suspend fun receiveReconnect()

    /** `GET /e2ee/keys/count` response body. */
    suspend fun receiveOtkCounts(body: String)

    /** Full member list of an encrypted DM/group (AN-24, AN-39). */
    suspend fun setChannelMembers(channelId: String, members: List<String>, settings: E2eeChannelSettings)

    /** Requests to run before encrypting for [channelId] (claim, key share); loop until empty. */
    suspend fun prepareSend(channelId: String): List<E2eeRequest>

    /** Devices whose mailbox stays full and that miss keys (shown in AN-30). */
    suspend fun undeliveredDevices(): List<E2eeUndeliveredDevice>

    /**
     * A page of messages, results in input order (AN-25). A failure is a value, not an
     * exception; `WaitingForKey` ones are decrypted again on [E2eeSyncEvent.RoomKeys].
     */
    suspend fun decrypt(messages: List<E2eeIncomingMessage>): List<E2eeDecryptResult>

    /**
     * Payload v1 JSON → envelope v1 JSON for `encrypted` of the send/edit request (AN-26). Only
     * after [prepareSend] returned nothing; call once per message (every call advances the
     * ratchet).
     * @throws E2eeException.RoomKeyNotShared back to [prepareSend]
     * @throws E2eeException.PayloadTooLarge the text is too long for one message
     */
    suspend fun encrypt(channelId: String, payloadJson: String): String

    /** Known devices of [userId] (ULID); empty if it has none with E2EE (AN-26, AN-30). */
    suspend fun devices(userId: String): List<E2eeDevice>

    /** This device's public keys; users compare the Ed25519 one (AN-30). */
    suspend fun identityKeys(): E2eeIdentityKeys

    /**
     * Local trust of a device set by hand (AN-30): back to unverified, or blocked. Verified
     * comes from a finished SAS only, so it cannot be asked for here (review O-3).
     */
    suspend fun setDeviceTrust(userId: String, deviceId: String, trust: E2eeTrust)

    /**
     * Asks a device to verify with SAS (AN-31); the request leaves with the next request pass.
     * @throws E2eeException.Core `UnknownDevice` for this device or one the core does not know
     */
    suspend fun requestVerification(userId: String, deviceId: String): E2eeSas

    /** The flow of an id from [ToDeviceOutcome.verifications]; null if this core never saw it. */
    suspend fun verification(flowId: String): E2eeSas?

    /** Flows that are not finished, incoming ones included. */
    suspend fun verifications(): List<E2eeSas>

    /**
     * A new recovery key and backup (AN-32): show [E2eeRecoverySetup.recoveryKey] once, then run
     * its request (`POST /e2ee/backup/version`). [passphrase] (UTF-8) is wiped by the caller.
     */
    suspend fun createRecovery(passphrase: ByteArray? = null): E2eeRecoverySetup

    /** A new backup under the same recovery key (always needs MFA); `NoRecovery` without one. */
    suspend fun rotateBackup(): E2eeRecoverySetup

    /**
     * Unlocks the server's backup with the recovery key or passphrase ([input], UTF-8; the caller
     * wipes it) and the secret storage bodies. Returns the backup version.
     * @throws E2eeException.RecoveryKeyTypo, E2eeException.WrongRecoveryKey,
     *   E2eeException.BackupKeyMismatch
     */
    suspend fun unlockBackup(input: ByteArray, secrets: E2eeRecoverySecrets): String

    /** `GET /e2ee/backup/keys?version=<v>` body → an import in batches. */
    suspend fun restoreBackup(keysBody: String): E2eeBackupRestore

    suspend fun backupStatus(): E2eeBackupStatus

    /** Stops uploading from this device; the server copy stays. */
    suspend fun disableBackup()

    /** What the recovery field holds, as the user types; [input] is wiped by the caller. */
    fun recoveryKeyCheck(input: ByteArray): E2eeRecoveryKeyCheck

    /**
     * Room keys of [channelIds] (all if empty) → a key file encrypted with [passphrase] (UTF-8,
     * not empty; wiped by the caller). About a second of PBKDF2 (CR-21).
     * @throws E2eeException.PassphraseRequired empty passphrase
     * @throws E2eeException.InvalidKeyFile the file would pass 64 MiB: export fewer channels
     */
    suspend fun exportRoomKeys(passphrase: ByteArray, channelIds: List<String> = emptyList()): E2eeKeyExport

    /**
     * Imports a key file ([file] = its text; another Matrix client's works too); nothing is
     * imported on failure (CR-21). [passphrase] is wiped by the caller.
     * @throws E2eeException.WrongPassphrase wrong passphrase, or the file was modified
     * @throws E2eeException.InvalidKeyFile not a key file (or a cut-off or oversized one)
     */
    suspend fun importRoomKeys(file: String, passphrase: ByteArray): E2eeKeyImport

    /**
     * Diagnostic report (CR-22): JSON with counts, states and error codes only — no keys,
     * session ids, others' ids, ciphertext or text. Recent failures live in memory.
     */
    suspend fun debugReport(): String
}

/** A device of a user as the core knows it. Keys are public. */
data class E2eeDevice(
    val userId: String,
    val deviceId: String,
    val ed25519: String?,
    val verified: Boolean,
    val blocked: Boolean,
    val deleted: Boolean,
)

enum class E2eeRequestKind {
    KeysUpload,
    KeysQuery,
    KeysClaim,
    ToDevice,
    BackupVersionCreate,
    BackupKeys,
    SecretPut,
}

/**
 * One HTTP request of the core. [id] stays the same in every retry (for to-device it is the
 * transaction id in the path), so the server deduplicates. [body] holds public keys and
 * Olm-encrypted payloads: not secret, but never logged either.
 */
class E2eeRequest(
    val id: String,
    val kind: E2eeRequestKind,
    val method: String,
    val path: String,
    val body: String,
) {
    override fun toString() = "E2eeRequest($kind $method $path, id=$id)"

    override fun equals(other: Any?) =
        other is E2eeRequest && other.id == id && other.kind == kind && other.method == method &&
            other.path == path && other.body == body

    override fun hashCode() = id.hashCode()
}

/** `GET /` → `features.e2ee` limits the core needs. */
data class E2eeLimits(
    val maxToDeviceBatch: Int,
    val maxRequestBytes: Int,
)

data class E2eeChannelSettings(
    val rotationPeriodMs: Long? = null,
    val rotationPeriodMsgs: Long? = null,
)

/** What one mailbox page did. Keys and plaintext never leave the core; only ids do. */
data class ToDeviceOutcome(
    /** Messages in the page: fetch again while it equals the requested limit. */
    val fetched: Int,
    /** Send in `POST /e2ee/to_device/ack` (≤ 100 per call), then [E2eeCore.markAckSent]. */
    val ackIds: List<String>,
    /** New room keys: messages waiting for them can be decrypted again (AN-25). */
    val roomKeys: List<E2eeRoomKey>,
    /** Verification flows that changed (AN-31). */
    val verifications: List<String>,
)

data class E2eeRoomKey(val channelId: String, val sessionId: String)

data class E2eeUndeliveredDevice(
    val userId: String,
    val deviceId: String,
    val attempts: Int,
    val gaveUp: Boolean,
)

/** Errors of the core, by code; messages are fixed and carry no keys or text. */
sealed class E2eeException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Another instance holds the account's store (one per account and process). */
    class StoreLocked(cause: Throwable? = null) : E2eeException("Store is locked", cause)

    /** The store cannot be opened: wrong key, damaged, or of another account/device (AN-42). */
    class StoreUnusable(cause: Throwable? = null) : E2eeException("Store is unusable", cause)

    /** A failed request needs the user (AN-31/32, AN-42). */
    class ActionRequired(val action: E2eeAction, cause: Throwable? = null) :
        E2eeException("Action required: $action", cause)

    /** `encrypt` before the room key went out: run `prepareSend` again. */
    class RoomKeyNotShared(cause: Throwable? = null) : E2eeException("Room key not shared", cause)

    /** The message is too long to encrypt. */
    class PayloadTooLarge(cause: Throwable? = null) : E2eeException("Payload too large", cause)

    /** An attachment blob is not the one the sender encrypted: show nothing (AN-28). */
    class AttachmentHashMismatch(cause: Throwable? = null) : E2eeException("Attachment hash mismatch", cause)

    /** The core has no member list for the channel yet (`setChannelMembers` first). */
    class UnknownChannel(cause: Throwable? = null) : E2eeException("Unknown channel", cause)

    /** Shaped like a recovery key, with a typo in it (AN-32). */
    class RecoveryKeyTypo(cause: Throwable? = null) : E2eeException("Recovery key typo", cause)

    /** Neither the recovery key nor the passphrase of this backup. */
    class WrongRecoveryKey(cause: Throwable? = null) : E2eeException("Wrong recovery key", cause)

    /**
     * The server's backup or secret storage does not fit together (a version of its own, or a
     * damaged secret): never restored from, never uploaded to.
     */
    class BackupKeyMismatch(cause: Throwable? = null) : E2eeException("Backup key mismatch", cause)

    /** No backup (to restore, or set up on this device to rotate). */
    class NoBackup(cause: Throwable? = null) : E2eeException("No backup", cause)

    /** A key export needs a passphrase (CR-21). */
    class PassphraseRequired(cause: Throwable? = null) : E2eeException("Passphrase required", cause)

    /** Not a key file, a cut-off or oversized one; on export: too many sessions for one file. */
    class InvalidKeyFile(cause: Throwable? = null) : E2eeException("Invalid key file", cause)

    /** The key file does not open with this passphrase, or it was modified (one MAC, CR-21). */
    class WrongPassphrase(cause: Throwable? = null) : E2eeException("Wrong passphrase", cause)

    /** A request of the core was refused for good and dropped (review O-2): a sync problem. */
    class RequestRejected(cause: Throwable? = null) : E2eeException("Request rejected", cause)

    /** Everything else; [code] is the core's error code. */
    class Core(val code: String, cause: Throwable? = null) : E2eeException("Core error $code", cause)
}

enum class E2eeAction {
    /** Backup version change: run the same request again with `x-mfa-ticket`. */
    MfaRequired,

    /** The backup was replaced elsewhere: ask for the recovery key again. */
    BackupVersionChanged,

    /** The server has other keys for this device: the device must be reset (AN-42). */
    DeviceKeysConflict,
}
