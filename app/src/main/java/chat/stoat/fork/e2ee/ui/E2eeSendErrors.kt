package chat.stoat.fork.e2ee.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import chat.stoat.fork.e2ee.E2eeSendError
import chat.stoat.fork.e2ee.OutgoingMessages
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.durio_e2ee_send_network
import chat.stoat.shared.resources.durio_e2ee_send_no_devices
import chat.stoat.shared.resources.durio_e2ee_send_refused
import chat.stoat.shared.resources.durio_e2ee_send_too_long
import chat.stoat.shared.resources.durio_e2ee_send_unavailable
import org.jetbrains.compose.resources.getString

/** Why an encrypted message failed, as a toast on the channel screen (plan AN-26). */
@Composable
fun E2eeSendErrors() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        OutgoingMessages.errors.collect { error ->
            val text = when (error) {
                E2eeSendError.NoRecipientDevices -> Res.string.durio_e2ee_send_no_devices
                E2eeSendError.TooLong -> Res.string.durio_e2ee_send_too_long
                E2eeSendError.Unavailable -> Res.string.durio_e2ee_send_unavailable
                E2eeSendError.Network -> Res.string.durio_e2ee_send_network
                E2eeSendError.Refused -> Res.string.durio_e2ee_send_refused
            }
            Toast.makeText(context, getString(text), Toast.LENGTH_LONG).show()
        }
    }
}
