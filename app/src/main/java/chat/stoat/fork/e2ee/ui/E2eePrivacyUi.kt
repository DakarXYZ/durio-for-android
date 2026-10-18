package chat.stoat.fork.e2ee.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.unit.dp
import chat.stoat.api.fork.e2ee.E2eeChannels
import chat.stoat.fork.e2ee.E2eePrivacy
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.durio_privacy_hide_text
import chat.stoat.shared.resources.durio_privacy_hide_text_summary
import chat.stoat.shared.resources.durio_privacy_keyboard
import chat.stoat.shared.resources.durio_privacy_keyboard_summary
import chat.stoat.shared.resources.durio_privacy_secure
import chat.stoat.shared.resources.durio_privacy_secure_summary
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * `FLAG_SECURE` while this is shown: no screenshots or recordings, a blank preview in "Recents"
 * (plan AN-32 recovery key — always; AN-33 encrypted chats — as an option). Restores the
 * window as it was.
 */
@Composable
fun SecureWindow(enabled: Boolean = true) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity, enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        val window = activity.window
        val wasSecure = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) activity.setRecentsScreenshotEnabled(false)
        onDispose {
            if (!wasSecure) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) activity.setRecentsScreenshotEnabled(true)
        }
    }
}

/** The channel screen's hook (AN-33): secure window for an encrypted chat, if the user asked. */
@Composable
fun EncryptedChannelPrivacy(channelId: String) {
    SecureWindow(enabled = E2eePrivacy.secureScreen && E2eeChannels.isEncrypted(channelId))
}

/**
 * Asks the keyboard not to learn from what is typed into [content] in an encrypted chat
 * (`IME_FLAG_NO_PERSONALIZED_LEARNING`, AN-33). Compose has no such keyboard option, so the
 * platform text input request is wrapped and the flag added to its `EditorInfo`.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun IncognitoKeyboard(channelId: String, content: @Composable () -> Unit) {
    if (!E2eePrivacy.incognitoKeyboard || !E2eeChannels.isEncrypted(channelId)) {
        content()
        return
    }
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            val incognito = object : PlatformTextInputMethodRequest {
                override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                    val connection = request.createInputConnection(outAttributes)
                    outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    return connection
                }
            }
            nextHandler.startInputMethod(incognito)
        },
        content = content,
    )
}

/** The privacy switches of encrypted chats (AN-33), on the devices screen. */
@Composable
fun E2eePrivacySettings() {
    val context = LocalContext.current
    Column(Modifier.padding(vertical = 4.dp)) {
        PrivacySwitch(Res.string.durio_privacy_hide_text, Res.string.durio_privacy_hide_text_summary, E2eePrivacy.hideNotificationText) {
            E2eePrivacy.setHideNotificationText(context, it)
        }
        PrivacySwitch(Res.string.durio_privacy_secure, Res.string.durio_privacy_secure_summary, E2eePrivacy.secureScreen) {
            E2eePrivacy.setSecureScreen(context, it)
        }
        PrivacySwitch(Res.string.durio_privacy_keyboard, Res.string.durio_privacy_keyboard_summary, E2eePrivacy.incognitoKeyboard) {
            E2eePrivacy.setIncognitoKeyboard(context, it)
        }
    }
}

@Composable
private fun PrivacySwitch(title: StringResource, summary: StringResource, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(summary), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
