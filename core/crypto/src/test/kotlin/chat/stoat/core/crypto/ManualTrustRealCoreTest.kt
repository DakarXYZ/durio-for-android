package chat.stoat.core.crypto

import chat.stoat.api.fork.e2ee.E2eeSync
import chat.stoat.api.fork.e2ee.E2eeTrust
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Review O-3: by hand the app can only block a device or put it back to unverified; "verified"
// is not a choice (only SAS Done verifies, VerificationRealCoreTest).
class ManualTrustRealCoreTest {
    private val userA = "01KA".padEnd(26, 'A')
    private val userB = "01KB".padEnd(26, 'B')
    private val deviceA = "01KDA1".padEnd(26, '1')
    private val deviceB = "01KDB1".padEnd(26, '1')

    @Before
    fun hostLibrary() = HostLibrary.assume()

    @Test
    fun blockAndUnblockNeverVerify() = runBlocking {
        val server = MiniE2eeServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = CryptoStores.openInMemory(userA, deviceA)
        val b = CryptoStores.openInMemory(userB, deviceB)
        try {
            val syncA = E2eeSync(a, server.client(userA, deviceA), scope)
            E2eeSync(b, server.client(userB, deviceB), scope).start()
            syncA.start()
            withTimeout(30_000) { while (!server.hasKeysOf(deviceB)) delay(30) }
            syncA.prepareSend("01KH".padEnd(26, 'H'), listOf(userA, userB))
            withTimeout(30_000) { while (a.devices(userB).isEmpty()) { syncA.requestPass(); delay(30) } }

            a.setDeviceTrust(userB, deviceB, E2eeTrust.Blocked)
            a.devices(userB).single().let {
                assertTrue(it.blocked)
                assertFalse(it.verified)
            }
            a.setDeviceTrust(userB, deviceB, E2eeTrust.Unverified)
            a.devices(userB).single().let {
                assertFalse(it.blocked)
                assertFalse(it.verified)
            }
        } finally {
            scope.cancel()
            a.close()
            b.close()
        }
    }
}
