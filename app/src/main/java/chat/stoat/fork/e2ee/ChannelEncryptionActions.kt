package chat.stoat.fork.e2ee

import chat.stoat.api.StoatAPI
import chat.stoat.api.fork.e2ee.E2eeFrames
import chat.stoat.api.fork.routes.EnableEncryptionException
import chat.stoat.api.fork.routes.enableChannelEncryption
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import logcat.logcat

/** Outcome of "enable end-to-end encryption" (plan AN-27). */
enum class EnableEncryptionResult {
    Enabled,

    /** Somebody was faster: it is on anyway. */
    AlreadyEnabled,

    /** No ManageChannel in this group (O5: the owner can take it away). */
    NoPermission,

    /** A bot is a recipient or the group has webhooks. */
    NotEncryptable,

    /** The server has E2EE switched off, or this device has none. */
    Unavailable,
    Failed,
}

object ChannelEncryptionActions {
    /**
     * Turns encryption on for a DM or group, irreversibly. The answer goes into the cache right
     * away (pinned, members to the core) instead of waiting for the socket's `ChannelUpdate`.
     */
    suspend fun enable(channelId: String): EnableEncryptionResult {
        if (E2eeManager.status.value != E2eeStatus.Ready) return EnableEncryptionResult.Unavailable
        return try {
            val updated = enableChannelEncryption(channelId)
            val merged = StoatAPI.channelCache[channelId]?.mergeWithPartial(updated) ?: updated
            StoatAPI.channelCache[channelId] = merged
            E2eeFrames.onServerChannel(merged)
            EnableEncryptionResult.Enabled
        } catch (e: CancellationException) {
            throw e
        } catch (e: EnableEncryptionException) {
            logcat(LogPriority.WARN) { "Encryption not enabled: ${e.status} ${e.type}" }
            result(e)
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "Encryption not enabled: ${e::class.simpleName}" }
            EnableEncryptionResult.Failed
        }
    }

    internal fun result(e: EnableEncryptionException): EnableEncryptionResult = when (e.status) {
        409 -> EnableEncryptionResult.AlreadyEnabled
        403 -> EnableEncryptionResult.NoPermission
        404 -> EnableEncryptionResult.Unavailable
        400 -> if (e.type == "NotEncryptable") EnableEncryptionResult.NotEncryptable else EnableEncryptionResult.Failed
        else -> EnableEncryptionResult.Failed
    }
}
