package chat.stoat.fork.e2ee

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.stoat.persistence.Database
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// AN-39: cache_<key>.db files of stage 1 (schema version 1) get the EncryptedChannel table
// Robolectric, as TestInfrastructureTest: the JDBC sqlite driver registers under it
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class EncryptedChannelMigrationTest {
    @Test
    fun versionOneDatabaseIsMigrated() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // the stage-1 schema: Server and Channel only
        driver.execute(null, "CREATE TABLE Server (id TEXT PRIMARY KEY NOT NULL, owner TEXT NOT NULL, name TEXT NOT NULL, description TEXT, iconId TEXT, bannerId TEXT, flags INTEGER)", 0)
        driver.execute(null, "CREATE TABLE Channel (id TEXT PRIMARY KEY NOT NULL, channelType TEXT NOT NULL, userId TEXT, name TEXT, owner TEXT, description TEXT, dmPartner TEXT, iconId TEXT, lastMessageId TEXT, active INTEGER, nsfw INTEGER, server TEXT)", 0)

        assertEquals(2L, Database.Schema.version)
        Database.Schema.migrate(driver, 1, Database.Schema.version)

        val db = Database(driver)
        db.encryptedChannelQueries.pin("c1")
        db.encryptedChannelQueries.pin("c1")
        assertEquals(listOf("c1"), db.encryptedChannelQueries.selectAll().executeAsList())
        driver.close()
    }

    @Test
    fun freshDatabaseHasTheTable() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        Database(driver).encryptedChannelQueries.pin("c2")
        assertEquals(listOf("c2"), Database(driver).encryptedChannelQueries.selectAll().executeAsList())
        driver.close()
    }
}
