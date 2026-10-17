package chat.stoat.api.fork.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Which keys of upstream's single revolt_kv belong to the account (plan AN-08)
class AccountKvTest {
    private class NamedStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) =
            error("not used")
    }

    @AfterTest
    fun tearDown() {
        AccountKv.active = null
    }

    @Test
    fun accountKeys() {
        listOf(
            "sessionToken", "sessionId", "selfId", "selfName", "selfAvatarUrl",
            "currentDestination", "mentionOnReply", "lastChannel/01SERVER", "draftContent/01CHANNEL",
        ).forEach { assertTrue(AccountKv.isAccountScoped(it), it) }
    }

    @Test
    fun deviceKeys() {
        listOf(
            "fcmToken", "pushNotificationsRejected", "keyboardHeight", "experimentsEnabled",
            "exp/usePolar", "ageGateUnlocked", "spark/earlyAccess/dismissed", "durio/serverHistory",
        ).forEach { assertFalse(AccountKv.isAccountScoped(it), it) }
    }

    @Test
    fun routesAccountKeysToTheActiveAccount() {
        val device = NamedStore()
        val account = NamedStore()

        // no account (before login): everything stays in the device file, as upstream had it
        assertSame(device, AccountKv.storeFor("draftContent/1", device))

        AccountKv.active = account
        assertSame(account, AccountKv.storeFor("draftContent/1", device))
        assertSame(device, AccountKv.storeFor("fcmToken", device))
    }
}
