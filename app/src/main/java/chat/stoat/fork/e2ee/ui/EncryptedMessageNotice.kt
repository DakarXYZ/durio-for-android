package chat.stoat.fork.e2ee.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.stoat.R
import chat.stoat.api.fork.e2ee.DecryptionStatus
import chat.stoat.api.fork.e2ee.E2eeState
import chat.stoat.api.fork.e2ee.E2eeVerification
import chat.stoat.core.model.schemas.Message
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.durio_e2ee_broken
import chat.stoat.shared.resources.durio_e2ee_decrypting
import chat.stoat.shared.resources.durio_e2ee_mismatch
import chat.stoat.shared.resources.durio_e2ee_no_device
import chat.stoat.shared.resources.durio_e2ee_suspicious
import chat.stoat.shared.resources.durio_e2ee_unauthenticated
import chat.stoat.shared.resources.durio_e2ee_update_app
import chat.stoat.shared.resources.durio_e2ee_waiting_key
import chat.stoat.shared.resources.durio_e2ee_withheld
import com.valentinilk.shimmer.shimmer
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Render guard of an encrypted message (plan AN-25, AN-27, AN-46), called by `Message.kt`
 * before the text: the server's placeholder in `content` is never drawn. Returns whether the
 * message body must be hidden — then this has drawn the reason instead.
 *
 * Decrypted messages get a line only when their author is not proven (key from the backup,
 * unknown device); verified/unverified devices look like normal messages (no cross-signing in
 * v1, O7: a warning on every message would teach users to ignore it).
 */
@Composable
fun EncryptedMessageNotice(message: Message): Boolean {
    if (message.encrypted == null) return false
    val status = message.id?.let { E2eeState.status[it] }

    val text: StringResource = when (status) {
        is DecryptionStatus.Decrypted -> {
            when (status.verification) {
                E2eeVerification.Unauthenticated -> NoticeLine(Res.string.durio_e2ee_unauthenticated, warning = false)
                E2eeVerification.UnknownDevice, E2eeVerification.Mismatch ->
                    NoticeLine(Res.string.durio_e2ee_mismatch, warning = true)
                E2eeVerification.Verified, E2eeVerification.Unverified -> Unit
            }
            return false
        }
        // no status yet: on its way through the decryptor
        null -> Res.string.durio_e2ee_decrypting
        DecryptionStatus.Pending -> Res.string.durio_e2ee_waiting_key
        DecryptionStatus.Suspicious -> Res.string.durio_e2ee_suspicious
        is DecryptionStatus.Failed -> when (status.reason) {
            DecryptionStatus.Failed.Reason.NoDevice -> Res.string.durio_e2ee_no_device
            DecryptionStatus.Failed.Reason.Withheld -> Res.string.durio_e2ee_withheld
            DecryptionStatus.Failed.Reason.UpdateApp -> Res.string.durio_e2ee_update_app
            DecryptionStatus.Failed.Reason.Broken -> Res.string.durio_e2ee_broken
        }
    }
    val pending = status == null || status == DecryptionStatus.Pending
    NoticeLine(text, warning = status == DecryptionStatus.Suspicious, shimmer = pending)
    return true
}

/** One line for reply previews and other single-line places; null: show the message's text. */
@Composable
fun encryptedMessagePreview(message: Message): String? {
    if (message.encrypted == null) return null
    return when (val status = message.id?.let { E2eeState.status[it] }) {
        is DecryptionStatus.Decrypted -> null
        null, DecryptionStatus.Pending -> stringResource(Res.string.durio_e2ee_waiting_key)
        DecryptionStatus.Suspicious -> stringResource(Res.string.durio_e2ee_suspicious)
        is DecryptionStatus.Failed -> stringResource(Res.string.durio_e2ee_broken)
    }
}

@Composable
private fun NoticeLine(text: StringResource, warning: Boolean, shimmer: Boolean = false) {
    val colour = if (warning) MaterialTheme.colorScheme.error else LocalContentColor.current.copy(alpha = 0.6f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .padding(vertical = 2.dp)
            .then(if (shimmer) Modifier.shimmer() else Modifier)
    ) {
        Icon(
            painter = painterResource(if (warning) R.drawable.ic_error_24dp else R.drawable.ic_lock_24dp),
            contentDescription = null,
            tint = colour,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = stringResource(text),
            color = colour,
            fontSize = 13.sp,
            fontStyle = FontStyle.Italic,
        )
    }
}
