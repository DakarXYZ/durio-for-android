package chat.stoat.fork.account

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import chat.stoat.core.model.fork.ServerEndpoints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** Migration of a single-account install (plan AN-08) from a `revolt_kv` fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LegacyAccountMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val device: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
        context.preferencesDataStoreFile("revolt_kv")
    }
    private val store = AccountStore(
        PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile("accounts") }
    )
    private val registry = AccountStorageRegistry(context, fakeWrapper())

    private val server = ServerEndpoints(
        api = "https://duri0.duckdns.org/api",
        ws = "wss://duri0.duckdns.org/ws",
        files = "https://duri0.duckdns.org/autumn",
        proxy = "https://duri0.duckdns.org/january",
        webApp = "https://duri0.duckdns.org",
    )
    private val userId = "01HZY0000000000000000000AA"

    private fun migration(legacyServer: ServerEndpoints? = server) = LegacyAccountMigration(
        device = device,
        legacyServer = { legacyServer },
        accounts = store,
        storageFor = registry::get,
        now = { 1_000L },
    )

    private suspend fun fixture(withSelfId: Boolean = true) {
        device.edit {
            it[key("sessionToken")] = "legacy-token"
            it[key("sessionId")] = "01SESSION"
            if (withSelfId) it[key("selfId")] = userId
            it[key("selfName")] = "alice"
            it[key("selfAvatarUrl")] = "https://duri0.duckdns.org/autumn/avatars/abc"
            it[key("currentDestination")] = "channel/01CHANNEL"
            it[key("lastChannel/01SERVER")] = "01CHANNEL"
            it[key("draftContent/01CHANNEL")] = "half-written"
            it[key("mentionOnReply")] = "true"
            // device keys stay
            it[key("fcmToken")] = "fcm"
            it[key("keyboardHeight")] = "900"
            it[key("exp/usePolar")] = "false"
            it[key("spark/earlyAccess/dismissed")] = "true"
        }
    }

    private fun key(name: String) = stringPreferencesKey(name)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun freshInstallHasNothingToMigrate() = runTest {
        assertEquals(LegacyAccountMigration.Result.NothingToMigrate, migration().migrate())
        assertTrue(store.load().accounts.isEmpty())
    }

    @Test
    fun migratesAccountKeysAndWrapsTheToken() = runTest {
        fixture()

        val result = migration().migrate()

        val expectedKey = accountKey(server.api, userId)
        val account = (result as LegacyAccountMigration.Result.Migrated).account
        assertEquals(expectedKey, account.key)
        assertEquals("01SESSION", account.sessionId)
        assertEquals("alice", account.displayName)
        assertEquals(server, account.endpoints)
        assertEquals(account, store.load().active)

        val storage = registry.get(expectedKey)
        assertEquals("legacy-token", storage.readToken())
        assertEquals("half-written", storage.kv.get("draftContent/01CHANNEL"))
        assertEquals("01CHANNEL", storage.kv.get("lastChannel/01SERVER"))
        assertEquals("channel/01CHANNEL", storage.kv.get("currentDestination"))
        assertEquals("true", storage.kv.get("mentionOnReply"))
        // the plain token is not copied
        assertNull(storage.kv.get("sessionToken"))

        val left = device.data.first()
        assertNull(left[key("sessionToken")])
        assertNull(left[key("draftContent/01CHANNEL")])
        assertNull(left[key("selfId")])
        assertEquals("fcm", left[key("fcmToken")])
        assertEquals("900", left[key("keyboardHeight")])
        assertEquals("false", left[key("exp/usePolar")])
        assertEquals("true", left[key("spark/earlyAccess/dismissed")])

        // a second start finds nothing left
        assertEquals(LegacyAccountMigration.Result.NothingToMigrate, migration().migrate())
        registry.remove(expectedKey, deleteFiles = false)
    }

    @Test
    fun withoutUserIdTheMigrationIsPostponedAndNothingChanges() = runTest {
        fixture(withSelfId = false)
        val before = device.data.first()

        assertEquals(LegacyAccountMigration.Result.Postponed, migration().migrate())

        assertEquals(before, device.data.first())
        assertTrue(store.load().accounts.isEmpty())
        // the app keeps using the old session meanwhile
        assertEquals("legacy-token" to "01SESSION", migration().legacySession())
    }

    @Test
    fun noNetworkKeepsItPostponedUntilTheUserIdIsKnown() = runTest {
        fixture(withSelfId = false)
        val migration = migration()

        val offline = migration.rememberUserId { _, _ -> throw IOException("no network") }
        assertFalse(offline)
        assertEquals(LegacyAccountMigration.Result.Postponed, migration.migrate())

        var askedWith: Pair<String, String>? = null
        val online = migration.rememberUserId { endpoints, token ->
            askedWith = endpoints.api to token
            userId
        }
        assertTrue(online)
        assertEquals(server.api to "legacy-token", askedWith)

        val result = migration.migrate()
        assertTrue(result is LegacyAccountMigration.Result.Migrated)
        registry.remove(accountKey(server.api, userId), deleteFiles = false)
    }

    @Test
    fun withoutAKnownServerTheOldSessionIsDropped() = runTest {
        fixture()

        assertEquals(LegacyAccountMigration.Result.Discarded, migration(legacyServer = null).migrate())

        val left = device.data.first()
        assertNull(left[key("sessionToken")])
        assertEquals("fcm", left[key("fcmToken")])
        assertTrue(store.load().accounts.isEmpty())
    }
}
