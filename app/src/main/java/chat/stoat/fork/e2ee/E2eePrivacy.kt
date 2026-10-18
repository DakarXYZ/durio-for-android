package chat.stoat.fork.e2ee

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/**
 * Privacy options of encrypted chats (plan AN-33), for this device and every account on it.
 * Plain preferences: they hold no secret, and the push service reads them without an account.
 *
 * - [hideNotificationText]: "New message from X" instead of the text — Android 11+ keeps a
 *   notification history and watches/cars copy notifications (Q65). Off by default.
 * - [secureScreen]: `FLAG_SECURE` on encrypted chats — no screenshots, no recording, a blank
 *   preview in "Recents". Off by default (screenshots are a normal thing to do).
 * - [incognitoKeyboard]: the keyboard is asked not to learn from what is typed in encrypted
 *   chats (`IME_FLAG_NO_PERSONALIZED_LEARNING`; a request, keyboards may ignore it). On by default.
 */
object E2eePrivacy {
    private const val PREFERENCES = "durio_e2ee_privacy"
    private const val HIDE_TEXT = "hide_notification_text"
    private const val SECURE_SCREEN = "secure_screen"
    private const val INCOGNITO_KEYBOARD = "incognito_keyboard"

    @Volatile
    private var initialized = false

    var hideNotificationText by mutableStateOf(false)
        private set
    var secureScreen by mutableStateOf(false)
        private set
    var incognitoKeyboard by mutableStateOf(true)
        private set

    /** Reads the options once per process (cheap; also from the push service). */
    fun init(context: Context) {
        if (initialized) return
        val prefs = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        hideNotificationText = prefs.getBoolean(HIDE_TEXT, false)
        secureScreen = prefs.getBoolean(SECURE_SCREEN, false)
        incognitoKeyboard = prefs.getBoolean(INCOGNITO_KEYBOARD, true)
        initialized = true
    }

    fun hideNotificationText(context: Context): Boolean {
        init(context)
        return hideNotificationText
    }

    fun setHideNotificationText(context: Context, value: Boolean) = save(context, HIDE_TEXT, value) { hideNotificationText = it }

    fun setSecureScreen(context: Context, value: Boolean) = save(context, SECURE_SCREEN, value) { secureScreen = it }

    fun setIncognitoKeyboard(context: Context, value: Boolean) = save(context, INCOGNITO_KEYBOARD, value) { incognitoKeyboard = it }

    private fun save(context: Context, key: String, value: Boolean, apply: (Boolean) -> Unit) {
        init(context)
        apply(value)
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit { putBoolean(key, value) }
    }
}
