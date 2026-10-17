package chat.stoat.fork.account

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import chat.stoat.core.model.fork.ServerEndpoints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AccountStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val store = AccountStore(
        PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile("accounts") }
    )

    private fun account(host: String, user: String) = ServerEndpoints(
        api = "https://$host/api", ws = "wss://$host/ws", files = "", proxy = "", webApp = "https://$host"
    ).let { Account(accountKey(it.api, user), it, user, "session-$user", addedAt = 1) }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun addSwitchAndRemove() = runTest {
        val a = account("duri0.duckdns.org", "A")
        val b = account("duri1.duckdns.org", "B")

        store.upsert(a, makeActive = true)
        store.upsert(b)
        assertEquals(a, store.load().active)
        assertEquals(listOf(a, b), store.load().accounts)

        store.setActive(b.key)
        assertEquals(b, store.load().active)

        // logging in again replaces the record
        val renamed = b.copy(displayName = "bob")
        store.upsert(renamed)
        assertEquals(listOf(a, renamed), store.load().accounts)

        store.remove(b.key)
        assertEquals(listOf(a), store.load().accounts)
        assertNull(store.load().activeKey)

        store.setActive(null)
        assertNull(store.load().active)
        assertFailsWith<IllegalArgumentException> { store.setActive("unknown") }
    }
}
