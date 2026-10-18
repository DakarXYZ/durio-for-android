package chat.stoat.core.crypto

import chat.stoat.api.fork.e2ee.E2eeException
import chat.stoat.api.fork.e2ee.E2eeRequestKind
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
}
