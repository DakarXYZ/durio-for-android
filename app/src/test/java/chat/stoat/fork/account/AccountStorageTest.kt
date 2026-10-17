package chat.stoat.fork.account

import android.app.Application
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import chat.stoat.fork.keystore.AesGcmKeyWrapper
import chat.stoat.fork.keystore.KeyWrapper
import chat.stoat.fork.keystore.UnwrapException
import chat.stoat.persistence.Database
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.KeyGenerator
import kotlin.test.assertFailsWith

/** Software AES key in place of the Keystore one (Robolectric has no AndroidKeyStore). */
internal fun fakeWrapper(): KeyWrapper {
    val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    return AesGcmKeyWrapper { key }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AccountStorageTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun oneInstancePerKey() = runTest {
        val registry = AccountStorageRegistry(context, fakeWrapper())

        val first = registry.get("aaaaaaaaaaaaaaaa")
        assertSame(first, registry.get("aaaaaaaaaaaaaaaa"))
        assertNotSame(first, registry.get("bbbbbbbbbbbbbbbb"))

        registry.remove("aaaaaaaaaaaaaaaa", deleteFiles = false)
        registry.remove("bbbbbbbbbbbbbbbb", deleteFiles = false)
    }

    @Test
    fun removeClosesAndReopensTheSameFile() = runTest {
        val registry = AccountStorageRegistry(context, fakeWrapper())
        val key = "aaaaaaaaaaaaaaaa"
        val storage = registry.get(key)
        storage.kv.set("draftContent/1", "hello")

        // DataStore throws on a second live instance for the same file; after remove() the old
        // one is cancelled, so a new instance may open it and sees the data
        registry.remove(key, deleteFiles = false)
        assertFalse(registry.isOpen(key))

        val reopened = registry.get(key)
        assertNotSame(storage, reopened)
        assertEquals("hello", reopened.kv.get("draftContent/1"))
        registry.remove(key, deleteFiles = false)
    }

    @Test
    fun tokenIsStoredWrapped() = runTest {
        val registry = AccountStorageRegistry(context, fakeWrapper())
        val storage = registry.get("aaaaaaaaaaaaaaaa")

        assertNull(storage.readToken())
        storage.writeToken("secret-session-token")

        assertEquals("secret-session-token", storage.readToken())
        // nothing in the file reads as the token
        val file = context.preferencesDataStoreFile(AccountStorage.kvName("aaaaaaaaaaaaaaaa"))
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("secret-session-token"))
        registry.remove("aaaaaaaaaaaaaaaa", deleteFiles = false)
    }

    @Test
    fun tokenFromAnotherKeyIsUnreadable() = runTest {
        val key = "aaaaaaaaaaaaaaaa"
        val before = AccountStorageRegistry(context, fakeWrapper())
        before.get(key).writeToken("secret")
        before.remove(key, deleteFiles = false)

        // e.g. the Keystore key was lost: a typed error, the caller asks for a new login
        val after = AccountStorageRegistry(context, fakeWrapper())
        assertFailsWith<UnwrapException> { after.get(key).readToken() }
        after.remove(key, deleteFiles = false)
    }

    @Test
    fun removeWithDeleteFilesDeletesEverythingOfTheAccount() = runTest {
        val registry = AccountStorageRegistry(context, fakeWrapper())
        val key = "aaaaaaaaaaaaaaaa"
        val other = "bbbbbbbbbbbbbbbb"

        val storage = registry.get(key)
        storage.writeToken("secret")
        Database(storage.sqlDriver).serverQueries.clear()
        val crypto = File(context.noBackupFilesDir, "crypto/$key").apply { mkdirs() }
        File(crypto, "store.db").writeText("x")
        registry.get(other).writeToken("other")

        val kvFile = context.preferencesDataStoreFile(AccountStorage.kvName(key))
        val dbFile = context.getDatabasePath(AccountStorage.databaseName(key))
        assertTrue(kvFile.exists())
        assertTrue(dbFile.exists())

        registry.remove(key, deleteFiles = true)

        assertFalse(kvFile.exists())
        assertFalse(dbFile.exists())
        assertFalse(crypto.exists())
        // other accounts keep their files
        assertTrue(context.preferencesDataStoreFile(AccountStorage.kvName(other)).exists())
        assertEquals("other", registry.get(other).readToken())
        registry.remove(other, deleteFiles = false)
    }
}
