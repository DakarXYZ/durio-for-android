package chat.stoat.api.fork.e2ee

import kotlin.test.Test
import kotlin.test.assertEquals

// Review O-3: by hand a device can only be blocked or put back to unverified; it becomes
// verified by a finished SAS only (host contract, stoat-crypto lib.rs "Verification")
class E2eeTrustTest {
    @Test
    fun verifiedIsNotAManualChoice() {
        assertEquals(listOf(E2eeTrust.Unverified, E2eeTrust.Blocked), E2eeTrust.entries)
    }
}
