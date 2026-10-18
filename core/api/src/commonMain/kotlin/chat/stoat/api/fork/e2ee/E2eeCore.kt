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
 * Decrypt/encrypt (AN-25/26), devices, SAS and backup (AN-30..32) are added with their tasks.
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
     * Non-2xx, or no response at all ([status] 0).
     * @throws E2eeException.ActionRequired when the UI has to act (MFA, backup replaced, key conflict)
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
}

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
