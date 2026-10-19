package chat.stoat.api.fork.e2ee

/**
 * A recovery being set up (AN-32): [recoveryKey] is shown once (null for a rotation, the key
 * stays the same), then [request] runs. Secret: in memory only, never logged or saved by the
 * app — the user writes it down.
 */
class E2eeRecoverySetup(val recoveryKey: String?, val request: E2eeRequest) {
    override fun toString() = "E2eeRecoverySetup(${request.kind})"
}

/** The four bodies a restore needs, as the server returned them (ciphertext and public keys). */
class E2eeRecoverySecrets(
    val defaultKey: String,
    val keyDescription: String,
    val backupSecret: String,
    val backupVersion: String,
) {
    override fun toString() = "E2eeRecoverySecrets"
}

/** The backup as the settings screen shows it. */
data class E2eeBackupStatus(
    /** Version this device uploads to. */
    val version: String?,
    /** Uploading new keys. */
    val enabled: Boolean,
    /** This device holds the recovery key's secret storage key (can rotate). */
    val recoverySetUp: Boolean,
    /** The backup was replaced elsewhere: unlock again with the recovery key. */
    val outdated: Boolean,
    val keysTotal: Long,
    val keysBackedUp: Long,
)

/** An import in batches; holds the account's core open until closed. */
interface E2eeBackupRestore : AutoCloseable {
    suspend fun progress(): E2eeRestoreProgress

    /** Imports up to [max] sessions (0: the core's batch). */
    suspend fun next(max: Int = 0): E2eeRestoreProgress
}

data class E2eeRestoreProgress(
    val done: Long,
    val total: Long,
    val imported: Long,
    val failed: Long,
    val finished: Boolean,
)

enum class E2eeRecoveryKeyCheck {
    Valid,

    /** Shaped like a recovery key, with a typo. */
    Typo,

    /** Not a recovery key: a passphrase. */
    NotAKey,
}

/** What the server answered for its active backup version. */
sealed interface ServerVersion {
    /** [version] null: the server has no backup. */
    data class Known(val version: String?) : ServerVersion

    /** No answer. */
    data object Unknown : ServerVersion
}

/** The backup as the settings screen explains it. */
enum class E2eeBackupState {
    /** This device does not back up (set up or restore). */
    Off,

    /** Uploading to the server's active backup. */
    On,

    /** Another backup replaced this device's one: unlock it with the recovery key. */
    Replaced,

    /** The backup was deleted on another device: set up a new recovery key to back up again. */
    TurnedOffElsewhere,
    ;

    companion object {
        /** [status] of this device against [server]: what the user has to do, if anything. */
        fun of(status: E2eeBackupStatus, server: ServerVersion): E2eeBackupState = when {
            !status.enabled && !status.outdated -> Off
            server !is ServerVersion.Known -> if (status.outdated) Replaced else On
            server.version == null -> TurnedOffElsewhere
            status.outdated || server.version != status.version -> Replaced
            else -> On
        }
    }
}
