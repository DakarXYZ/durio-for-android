package chat.stoat.fork.keystore

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chat.stoat.fork.e2ee.E2eeKeyLostException
import chat.stoat.fork.e2ee.E2eeStoreKeys
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/**
 * The Keystore key under the crypto stores' data keys is deleted (plan AN-42, AN-34): "clear
 * credentials", a backup restored to another phone, vendor bugs. The store must be reported
 * lost — never opened with a new key, never replaced silently. A test alias: the app's own key
 * (`stoat.wrap.v1`) on the device is not touched.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreKeyLostTest {
    private val alias = "durio.test.wrap.v1"
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, "durio-test-keys")

    private fun deleteAlias() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)

    @Before
    fun clean() {
        dir.deleteRecursively()
        deleteAlias()
    }

    @After
    fun cleanup() = clean()

    @Test
    fun deletedKeystoreKeyMeansKeyLost() {
        val key = AndroidKeystoreAesKey(alias)
        val keys = E2eeStoreKeys(dir, AesGcmKeyWrapper(key::get))
        val dataKey = keys.dataKey("account")
        keys.storeDir("account").mkdirs()
        keys.storeDir("account").resolve("crypto.sqlite3").writeText("store")
        // another process start with the key in place: the same data key
        assertArrayEquals(dataKey, E2eeStoreKeys(dir, AesGcmKeyWrapper(AndroidKeystoreAesKey(alias)::get)).dataKey("account"))

        deleteAlias()

        // this process still holds the key's handle
        assertThrows(E2eeKeyLostException::class.java) {
            E2eeStoreKeys(dir, AesGcmKeyWrapper(key::get)).dataKey("account")
        }
        // the next start: a new Keystore key, the old blob does not open with it
        assertThrows(E2eeKeyLostException::class.java) {
            E2eeStoreKeys(dir, AesGcmKeyWrapper(AndroidKeystoreAesKey(alias)::get)).dataKey("account")
        }
        // the wrapped key and the store stay for the reset (AN-42), nothing is replaced
        assertTrue(keys.keyFile("account").isFile)
        assertTrue(keys.storeExists("account"))
    }
}
