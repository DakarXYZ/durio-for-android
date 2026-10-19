package chat.stoat.fork.register

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding
import chat.stoat.api.fork.http.ApiClient
import chat.stoat.api.fork.routes.fetchServerRoot
import chat.stoat.api.routes.account.RegistrationBody
import chat.stoat.composables.generic.FormTextField
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.durio_register_invite
import chat.stoat.shared.resources.durio_register_invite_hint_optional
import chat.stoat.shared.resources.durio_register_invite_hint_required
import chat.stoat.shared.resources.durio_register_invite_invalid
import chat.stoat.shared.resources.durio_register_invite_missing
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The invite code of a sign-up (dur.io servers are usually `[api.registration] invite_only`):
 * upstream's form never sent `invite`, so nobody could register on such a server.
 */
object RegistrationInvite {
    /** `GET /` → `features.invite_only` of the chosen server; null when it could not be asked. */
    suspend fun inviteOnly(): Boolean? = try {
        fetchServerRoot(ApiClient.Active).features?.inviteOnly
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** The sign-up body; a blank code is left out (servers without invites ignore it anyway). */
    fun body(email: String, password: String, captcha: String?, invite: String): RegistrationBody = RegistrationBody(
        email = email,
        password = password,
        invite = invite.trim().takeIf { it.isNotEmpty() },
        captcha = captcha ?: "",
    )

    /** What to tell the user for the server's error type, if it is about the invite. */
    fun errorText(type: String?): StringResource? = when (type) {
        "MissingInvite" -> Res.string.durio_register_invite_missing
        "InvalidInvite" -> Res.string.durio_register_invite_invalid
        else -> null
    }

    /** Whether the form may be sent: an invite-only server needs a code. */
    fun complete(inviteOnly: Boolean?, invite: String): Boolean = inviteOnly != true || invite.isNotBlank()
}

/**
 * The sign-up form's hook: the invite code field. [inviteOnly] null (unknown): shown as
 * optional; false: not shown.
 */
@Composable
fun InviteCodeField(inviteOnly: Boolean?, value: String, onChange: (String) -> Unit) {
    if (inviteOnly == false) return
    FormTextField(
        value = value,
        onChange = onChange,
        label = stringResource(Res.string.durio_register_invite),
        type = KeyboardType.Ascii,
        action = ImeAction.Done,
    )
    Text(
        text = stringResource(
            if (inviteOnly == true) Res.string.durio_register_invite_hint_required else Res.string.durio_register_invite_hint_optional
        ),
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 40.dp, vertical = 10.dp),
    )
}
