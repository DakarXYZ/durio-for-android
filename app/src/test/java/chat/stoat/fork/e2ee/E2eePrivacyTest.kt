package chat.stoat.fork.e2ee

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import chat.stoat.core.model.fork.ServerEndpoints
import chat.stoat.fork.account.Account
import chat.stoat.fork.push.PushDecryption
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Privacy options (plan AN-33): kept per device, and "hide text" keeps encrypted pushes generic
// without even opening the account's core (no decryption, no retry job)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class E2eePrivacyTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val account = Account(
        key = "k1",
        endpoints = ServerEndpoints("https://a.example/api", "wss://a.example/ws", "https://a.example/autumn", "https://a.example/january", "https://a.example"),
        userId = "01USER",
        sessionId = "01SESSION",
        addedAt = 0,
        ownServer = true,
    )

    @Test
    fun defaultsAndPersistence() {
        E2eePrivacy.init(context)
        assertFalse(E2eePrivacy.hideNotificationText)
        assertFalse(E2eePrivacy.secureScreen)
        assertTrue(E2eePrivacy.incognitoKeyboard)

        E2eePrivacy.setSecureScreen(context, true)
        assertTrue(context.getSharedPreferences("durio_e2ee_privacy", 0).getBoolean("secure_screen", false))
        E2eePrivacy.setSecureScreen(context, false)
    }

    @Test
    fun hiddenTextIsNeverDecrypted() = runTest {
        E2eePrivacy.setHideNotificationText(context, true)
        try {
            val data = mapOf("encrypted" to "1", "message" to "m", "channel" to "c", "author" to "a", "envelope" to "{}")
            assertNull(PushDecryption.text(context, account, data))
            // WorkManager is not set up here: reaching it would throw
            PushDecryption.retryLater(context, account.key, "c", "m", "a")
        } finally {
            E2eePrivacy.setHideNotificationText(context, false)
        }
    }
}
