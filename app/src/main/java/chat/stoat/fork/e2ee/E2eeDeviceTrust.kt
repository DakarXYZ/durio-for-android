package chat.stoat.fork.e2ee

import chat.stoat.api.fork.e2ee.E2eeTrust
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import logcat.logcat

/**
 * Blocking and unblocking a device by hand (the core keeps it, local to this device; nothing
 * goes to the server). A blocked device gets no room keys from now on: the core leaves it out
 * of every later key share (matrix-sdk-crypto also rotates a room key it had shared with it).
 * Unblocking makes it unverified, never verified (review O-3: that takes a finished SAS).
 */
object E2eeDeviceTrust {
    /** False when E2EE is not ready or the core refused (an unknown device). */
    suspend fun set(userId: String, deviceId: String, action: TrustAction): Boolean {
        val active = E2eeManager.active ?: return false
        return try {
            active.core.setDeviceTrust(
                userId,
                deviceId,
                when (action) {
                    TrustAction.Block -> E2eeTrust.Blocked
                    TrustAction.Unblock -> E2eeTrust.Unverified
                },
            )
            // a key share or rotation may be due now
            active.sync.requestPass()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "Device trust not set: ${e::class.simpleName}" }
            false
        }
    }
}
