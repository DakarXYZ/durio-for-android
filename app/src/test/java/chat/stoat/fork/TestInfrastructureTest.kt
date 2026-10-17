package chat.stoat.fork

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.stoat.persistence.Database
import chat.stoat.persistence.KVStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Smoke test for the test dependencies themselves (plan AN-16): Robolectric gives a Context
// for DataStore, SQLDelight runs on an in-memory JDBC driver.
// A plain Application: StoatApplication starts Koin, Firebase and the realtime monitor.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TestInfrastructureTest {
    @Test
    fun dataStoreWorksUnderRobolectric() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val kv = KVStorage(
            PreferenceDataStoreFactory.create(scope = scope) {
                context.preferencesDataStoreFile("test_kv")
            }
        )

        kv.set("key", "value")
        assertEquals("value", kv.get("key"))
        scope.cancel()
    }

    @Test
    fun sqlDelightWorksInMemory() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val db = Database(driver)

        db.serverQueries.clear()
        assertEquals(0, db.channelQueries.selectAll().executeAsList().size)
        driver.close()
    }
}
