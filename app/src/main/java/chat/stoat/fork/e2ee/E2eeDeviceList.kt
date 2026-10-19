package chat.stoat.fork.e2ee

import chat.stoat.api.fork.e2ee.E2eeDevice
import chat.stoat.api.fork.e2ee.E2eeUndeliveredDevice
import chat.stoat.api.fork.routes.DeviceSession
import chat.stoat.api.internals.ULID

/** E2EE of one device, as a badge (AN-30). */
enum class DeviceTrust {
    Verified,
    Unverified,
    Blocked,

    /** A session without E2EE keys: an old app version, a bot, the web client. */
    NoEncryption,

    /** Keys the server dropped (the session was revoked) but the core still remembers. */
    Removed,
}

/** One row of the devices screen: a session merged with the E2EE device of the same id (C22). */
data class DeviceRow(
    val deviceId: String,
    val name: String?,
    val createdMs: Long?,
    val lastSeenMs: Long?,
    val current: Boolean,
    val trust: DeviceTrust,
    val ed25519: String?,
    /** Its mailbox stays full: it misses keys (undelivered). */
    val missesKeys: Boolean,
)

/** Blocking by hand (local trust): a blocked device gets no room keys from this one. */
enum class TrustAction { Block, Unblock }

object E2eeDeviceList {
    /**
     * Own devices: every session (newest activity first, this one on top) with its E2EE state,
     * then E2EE devices without a session (revoked, still known to the core).
     */
    fun own(
        currentSessionId: String,
        sessions: List<DeviceSession>,
        devices: List<E2eeDevice>,
        undelivered: List<E2eeUndeliveredDevice> = emptyList(),
    ): List<DeviceRow> {
        val byId = devices.associateBy { it.deviceId }
        val missing = undelivered.filter { it.gaveUp }.map { it.deviceId }.toSet()
        val rows = sessions.map { session ->
            val device = byId[session.id]
            DeviceRow(
                deviceId = session.id,
                name = session.name,
                createdMs = created(session.id),
                lastSeenMs = session.lastSeenMs,
                current = session.id == currentSessionId,
                trust = if (session.id == currentSessionId) DeviceTrust.Verified else trust(device),
                ed25519 = device?.ed25519,
                missesKeys = session.id in missing,
            )
        }
        val sessionIds = sessions.map { it.id }.toSet()
        val orphans = devices.filter { it.deviceId !in sessionIds && !it.deleted && it.deviceId != currentSessionId }.map {
            DeviceRow(it.deviceId, null, created(it.deviceId), null, false, DeviceTrust.Removed, it.ed25519, it.deviceId in missing)
        }
        return rows.sortedWith(compareByDescending<DeviceRow> { it.current }.thenByDescending { it.lastSeenMs ?: it.createdMs ?: 0 }) + orphans
    }

    /** Devices of another user (a profile, AN-30): no sessions, only what the core knows. */
    fun of(devices: List<E2eeDevice>, undelivered: List<E2eeUndeliveredDevice> = emptyList()): List<DeviceRow> {
        val missing = undelivered.filter { it.gaveUp }.map { it.deviceId }.toSet()
        return devices.filter { !it.deleted }.map {
            DeviceRow(it.deviceId, null, created(it.deviceId), null, false, trust(it), it.ed25519, it.deviceId in missing)
        }.sortedByDescending { it.createdMs ?: 0 }
    }

    /** Whether this device should be compared with another one of the user (T7): none verified. */
    fun needsVerification(rows: List<DeviceRow>): Boolean {
        val others = rows.filter { !it.current && it.trust != DeviceTrust.NoEncryption && it.trust != DeviceTrust.Removed }
        return others.isNotEmpty() && others.none { it.trust == DeviceTrust.Verified }
    }

    /**
     * What a row offers by hand: a device with E2EE keys can be blocked (verified ones too), a
     * blocked one unblocked; never this device, nor one without keys or already removed.
     */
    fun trustAction(row: DeviceRow): TrustAction? = when {
        row.current -> null
        row.trust == DeviceTrust.Blocked -> TrustAction.Unblock
        row.trust == DeviceTrust.Verified || row.trust == DeviceTrust.Unverified -> TrustAction.Block
        else -> null
    }

    private fun trust(device: E2eeDevice?): DeviceTrust = when {
        device == null -> DeviceTrust.NoEncryption
        device.deleted -> DeviceTrust.Removed
        device.blocked -> DeviceTrust.Blocked
        device.verified -> DeviceTrust.Verified
        else -> DeviceTrust.Unverified
    }

    private fun created(id: String): Long? = try {
        ULID.asTimestamp(id)
    } catch (e: Exception) {
        null
    }
}
