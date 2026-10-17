package chat.stoat.fork.account

import android.app.Application
import chat.stoat.api.StoatAPI
import chat.stoat.api.routes.sync.SyncedSetting
import chat.stoat.api.settings.LoadedSettings
import chat.stoat.api.settings.SyncedSettings
import chat.stoat.c2dm.NotificationDeepLink
import chat.stoat.core.model.schemas.Channel
import chat.stoat.core.model.schemas.User
import chat.stoat.internals.StoatWebLink
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Account switch (plan AN-09): the global state of the old account is gone afterwards, as far
 * as it can be checked without a running app. Voice and audio are stubbed (LiveKit, media3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AccountResetTest {
    @Test
    fun resetClearsTheGlobalStateOfTheOldAccount() = runTest {
        StoatAPI.setSessionHeader("token-of-a")
        StoatAPI.setSessionId("session-of-a")
        StoatAPI.selfId = "user-a"
        StoatAPI.userCache["user-a"] = User(id = "user-a")
        StoatAPI.channelCache["channel-a"] = Channel(id = "channel-a")
        SyncedSettings.applyRemoteUpdate(
            mapOf("ordering" to SyncedSetting(timestamp = 5, value = """{"servers":["server-a"]}"""))
        )
        assertEquals(listOf("server-a"), SyncedSettings.ordering.servers)
        LoadedSettings.avatarRadius = 7
        NotificationDeepLink.pendingNavigation.value = StoatWebLink.Channel("channel-a")

        var leftCall = false
        var stoppedAudio = false
        AccountReset.resetGlobalState(leaveCall = { leftCall = true }, stopAudio = { stoppedAudio = true })

        assertTrue(leftCall)
        assertTrue(stoppedAudio)
        assertEquals("", StoatAPI.sessionToken)
        assertEquals("", StoatAPI.sessionId)
        assertNull(StoatAPI.selfId)
        assertFalse(StoatAPI.isLoggedIn())
        assertTrue(StoatAPI.userCache.isEmpty())
        assertTrue(StoatAPI.channelCache.isEmpty())
        assertTrue(SyncedSettings.ordering.servers.isEmpty())
        assertEquals(50, LoadedSettings.avatarRadius)
        assertNull(NotificationDeepLink.pendingNavigation.value)

        // revisions are forgotten too: the next account's older settings are not ignored
        SyncedSettings.applyRemoteUpdate(
            mapOf("ordering" to SyncedSetting(timestamp = 1, value = """{"servers":["server-b"]}"""))
        )
        assertEquals(listOf("server-b"), SyncedSettings.ordering.servers)
    }
}
