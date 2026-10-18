package chat.stoat.api.fork.e2ee

/** Public identity keys of this device (AN-30). Not secret. */
data class E2eeIdentityKeys(val curve25519: String, val ed25519: String) {
    /** The Ed25519 key in groups of four, as users read it aloud to each other (AN-30). */
    val fingerprint: String
        get() = fingerprint(ed25519)

    companion object {
        fun fingerprint(key: String): String = key.chunked(4).joinToString(" ")
    }
}

/** Local trust of a device (no cross-signing in v1: nothing is uploaded). */
enum class E2eeTrust { Verified, Unverified, Blocked }

/**
 * One SAS verification flow (plan AN-31) as the UI drives it. The core keeps the state and has
 * no callbacks: re-read [state] after every mailbox pass ([E2eeSyncEvent.Verifications]) and
 * every action, and run a request pass after every action (the messages leave with it).
 *
 * Holds the account's core open: [close] it when the flow is no longer shown.
 */
interface E2eeSas : AutoCloseable {
    val flowId: String

    /** The other user (ULID); our own for one of our devices. */
    val otherUserId: String

    val isSelfVerification: Boolean

    /** Known once the other device answered (an incoming request names it at once). */
    fun otherDeviceId(): String?

    fun state(): E2eeSasState

    /** Accept an incoming request. */
    suspend fun accept()

    /** Start comparing on a ready request (either side). */
    suspend fun start()

    /** The emoji match: verified once the other side confirms too. */
    suspend fun confirm()

    /** The emoji differ: cancelled as a possible attack, nobody is trusted. */
    suspend fun mismatch()

    suspend fun cancel()
}

/** What the verification dialog shows. */
sealed interface E2eeSasState {
    /** We asked; waiting for the other device. */
    data object Requested : E2eeSasState

    /** The other device asks: accept or cancel. */
    data object Incoming : E2eeSasState

    /** Both accepted: either side starts. */
    data object Ready : E2eeSasState

    data object Started : E2eeSasState

    /** Compare with the other screen. */
    data class ShowCodes(val emoji: List<E2eeSasEmoji>, val decimals: List<Int>) : E2eeSasState

    /** We confirmed, the other side not yet. */
    data object Confirmed : E2eeSasState

    /** The device is verified. */
    data object Done : E2eeSasState

    data class Cancelled(val reason: E2eeCancelReason, val code: String, val byUs: Boolean) : E2eeSasState

    val isFinal: Boolean
        get() = this == Done || this is Cancelled
}

/** One of the seven emoji; [index] into the Matrix table (0..63), the name is translated by it. */
data class E2eeSasEmoji(val index: Int, val symbol: String, val description: String)

enum class E2eeCancelReason {
    User,
    Timeout,

    /** The codes or keys did not match: possible attack, the device is not trusted. */
    Mismatch,
    AcceptedElsewhere,
    Other,
}
