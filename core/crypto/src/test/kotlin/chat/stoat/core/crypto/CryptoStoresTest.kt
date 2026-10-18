package chat.stoat.core.crypto

import chat.stoat.api.fork.e2ee.E2eeException
import chat.stoat.api.fork.e2ee.E2eeRequestKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The facade over the real core (plan AN-20, AN-22): store lifecycle, key wiping, error mapping
class CryptoStoresTest {
    private val user = "01JA0000000000000000000001"
    private val device = "01JD0000000000000000000001"

    @Before
    fun hostLibrary() = HostLibrary.assume()

    @Test
    fun newDeviceUploadsItsKeys() = runBlocking {
        CryptoStores.openInMemory(user, device).use { core ->
            assertEquals(user, core.userId)
            assertEquals(device, core.deviceId)
            val requests = core.outgoingRequests()
            assertTrue(requests.any { it.kind == E2eeRequestKind.KeysUpload && it.path == "/e2ee/keys/upload" })
            // the request's toString names it without its body
            assertFalse(requests.first().toString().contains(requests.first().body))
        }
    }

    @Test
    fun sqliteStoreLifecycle() = runBlocking {
        val dir = Files.createTempDirectory("durio-crypto").toFile().resolve("store")
        val key = ByteArray(32) { 7 }
        try {
            val core = CryptoStores.open(user, device, dir, key.copyOf())
            // one machine per store and process
            assertFailsWith<E2eeException.StoreLocked> {
                CryptoStores.open(user, device, dir, key.copyOf())
            }
            core.close()

            // another key cannot read it (AN-42 "store unusable")
            assertFailsWith<E2eeException.StoreUnusable> {
                CryptoStores.open(user, device, dir, ByteArray(32) { 8 })
            }

            CryptoStores.open(user, device, dir, key.copyOf()).close()
            CryptoStores.destroy(dir)
            assertFalse(dir.exists())
        } finally {
            dir.parentFile.deleteRecursively()
        }
    }

    @Test
    fun keyIsWipedEvenOnFailure() = runBlocking {
        val dir = Files.createTempDirectory("durio-crypto").toFile()
        try {
            val short = ByteArray(16) { 1 }
            assertFailsWith<IllegalArgumentException> { CryptoStores.open(user, device, dir, short) }
            assertContentEquals(ByteArray(16), short)

            val key = ByteArray(32) { 3 }
            CryptoStores.open(user, device, dir.resolve("s"), key).close()
            assertContentEquals(ByteArray(32), key)
        } finally {
            dir.deleteRecursively()
        }
    }

    // CR-23: stores only move forward; a downgraded app does not open a newer core's store
    @Test
    fun storeOfANewerCoreIsLeftAlone() = runBlocking {
        val dir = Files.createTempDirectory("durio-crypto").toFile().resolve("store")
        val key = ByteArray(32) { 5 }
        try {
            CryptoStores.open(user, device, dir, key.copyOf()).close()
            val marker = dir.resolve(CryptoStores.VERSION_FILE)
            assertEquals(CryptoStores.coreVersion(), marker.readText())

            // an older core's marker is moved forward
            marker.writeText("0.0.1")
            CryptoStores.open(user, device, dir, key.copyOf()).close()
            assertEquals(CryptoStores.coreVersion(), marker.readText())

            // a newer one: refused before the library touches the store; the key is still wiped
            marker.writeText("99.0.0")
            // the last close checkpoints SQLite in the background: let it finish first
            delay(1_000)
            val before = dir.listFiles()!!.associate { it.name to it.lastModified() }
            val wiped = key.copyOf()
            assertFailsWith<E2eeException.StoreTooNew> { CryptoStores.open(user, device, dir, wiped) }
            assertContentEquals(ByteArray(32), wiped)
            assertEquals(before, dir.listFiles()!!.associate { it.name to it.lastModified() })

            CryptoStores.destroy(dir)
            assertFalse(dir.exists())
        } finally {
            dir.parentFile.deleteRecursively()
        }
    }

    @Test
    fun semverComparison() {
        assertTrue(CryptoStores.isNewer("0.3.0", "0.2.0"))
        assertTrue(CryptoStores.isNewer("1.0.0", "0.99.99"))
        assertTrue(CryptoStores.isNewer("0.2.10", "0.2.9"))
        assertFalse(CryptoStores.isNewer("0.2.0", "0.2.0"))
        assertFalse(CryptoStores.isNewer("0.2.0-rc.1", "0.2.0"))
        assertFalse(CryptoStores.isNewer("garbage", "0.2.0"))
        assertFalse(CryptoStores.isNewer("0.1.0", "0.2.0"))
    }
}
