package chat.stoat.fork.e2ee

import chat.stoat.api.fork.e2ee.E2eeDevice
import chat.stoat.api.fork.e2ee.E2eeIdentityKeys
import chat.stoat.api.fork.e2ee.E2eeUndeliveredDevice
import chat.stoat.api.fork.routes.DeviceSession
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The devices screen's list (AN-30): sessions merged with E2EE devices, fingerprints
class E2eeDeviceListTest {
    private val me = "01JA0000000000000000000001"
    private val old = "01JA0000000000000000000002"
    private val web = "01JA0000000000000000000003"
    private val revoked = "01JA0000000000000000000004"

    private fun device(id: String, verified: Boolean = false, deleted: Boolean = false) =
        E2eeDevice("01USER", id, "ed$id", verified, blocked = false, deleted = deleted)

    @Test
    fun sessionsAndDevicesAreOneList() {
        val rows = E2eeDeviceList.own(
            currentSessionId = me,
            sessions = listOf(
                DeviceSession(old, "Pixel", JsonPrimitive("2026-10-17T10:00:00Z")),
                DeviceSession(web, "Firefox"),
                DeviceSession(me, "This phone"),
            ),
            devices = listOf(device(me), device(old), device(revoked)),
            undelivered = listOf(E2eeUndeliveredDevice("01USER", old, attempts = 9, gaveUp = true)),
        )
        assertEquals(listOf(me, old, web, revoked), rows.map { it.deviceId })
        assertTrue(rows[0].current)
        assertEquals(DeviceTrust.Unverified, rows[1].trust)
        assertTrue(rows[1].missesKeys)
        assertEquals(1_792_231_200_000L, rows[1].lastSeenMs)
        assertEquals(DeviceTrust.NoEncryption, rows[2].trust)
        assertEquals(DeviceTrust.Removed, rows[3].trust)
        assertTrue(E2eeDeviceList.needsVerification(rows))

        val verified = E2eeDeviceList.own(me, listOf(DeviceSession(me, "a"), DeviceSession(old, "b")), listOf(device(me), device(old, verified = true)))
        assertFalse(E2eeDeviceList.needsVerification(verified))
        // a single device has nothing to verify with
        assertFalse(E2eeDeviceList.needsVerification(E2eeDeviceList.own(me, listOf(DeviceSession(me, "a")), listOf(device(me)))))
    }

    @Test
    fun anotherUsersDevicesWithoutTheDeletedOnes() {
        val rows = E2eeDeviceList.of(listOf(device(old, verified = true), device(revoked, deleted = true)))
        assertEquals(listOf(old), rows.map { it.deviceId })
        assertEquals(DeviceTrust.Verified, rows.single().trust)
    }

    @Test
    fun fingerprintIsGroupedByFour() {
        assertEquals("abcd efgh ij", E2eeIdentityKeys("c", "abcdefghij").fingerprint)
    }

    @Test
    fun lastSeenAcceptsMillisAndIsoAndNothing() {
        assertEquals(5L, DeviceSession("a", "n", JsonPrimitive(5)).lastSeenMs)
        assertNull(DeviceSession("a", "n", JsonPrimitive("yesterday")).lastSeenMs)
        assertNull(DeviceSession("a", "n").lastSeenMs)
    }
}
