package chat.stoat.fork.e2ee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The first prompt of a device (AN-31, AN-32): verify or restore on a new one, set up recovery on
// the first one
class E2eeOnboardingTest {
    @Test
    fun firstPromptOfADevice() {
        // a new device of a user with others or a backup: verify or restore (T7)
        assertEquals(E2eeOnboarding.Prompt.NewDevice, E2eeOnboarding.decide(ownDevices = 1, backupOnThisDevice = false, backupOnServer = false))
        assertEquals(E2eeOnboarding.Prompt.NewDevice, E2eeOnboarding.decide(ownDevices = 0, backupOnThisDevice = false, backupOnServer = true))
        // the very first device: set up recovery
        assertEquals(E2eeOnboarding.Prompt.SetUpRecovery, E2eeOnboarding.decide(ownDevices = 0, backupOnThisDevice = false, backupOnServer = false))
        // backup already on: nothing to ask
        assertNull(E2eeOnboarding.decide(ownDevices = 3, backupOnThisDevice = true, backupOnServer = true))
    }
}
