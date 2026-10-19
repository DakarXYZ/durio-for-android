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
import chat.stoat.core.model.fork.ActiveServer
import chat.stoat.core.model.fork.ServerEndpoints
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.durio_register_invite
import chat.stoat.shared.resources.durio_register_invite_hint_optional
import chat.stoat.shared.resources.durio_register_invite_hint_required
import chat.stoat.shared.resources.durio_register_invite_invalid
import chat.stoat.shared.resources.durio_register_invite_missing
import chat.stoat.shared.resources.durio_register_invite_other_server
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

    /** What was pasted: a bare code, or a link `https://<host>/invite/<code>` ([host] then). */
    data class Parsed(val code: String, val host: String?)

    private val LINK = Regex("""^(?:[a-z][a-z0-9+.-]*://)?([^/?#\s]*)/(?:[^?#\s]*/)?invite/([^/?#\s]+)""", RegexOption.IGNORE_CASE)

    /** The code of a pasted invite link (admins share links), or the text itself. */
    fun parse(input: String): Parsed {
        val text = input.trim()
        val match = LINK.find(text) ?: return Parsed(text, null)
        val host = match.groupValues[1].substringAfterLast('@').substringBefore(':').lowercase().takeIf { it.isNotEmpty() }
        return Parsed(match.groupValues[2], host)
    }

    /**
     * Whether a pasted link belongs to another server than the one signing up: its host is none
     * of [endpoints]' hosts (API, web app, invites). A bare code never is.
     */
    fun otherServer(input: String, endpoints: ServerEndpoints?): Boolean {
        val host = parse(input).host ?: return false
        endpoints ?: return false
        val own = listOfNotNull(endpoints.api, endpoints.webApp, endpoints.invites).map { hostOf(it) }
        return own.none { it.equals(host, ignoreCase = true) }
    }

    /** Host of the selected server's API, for the links admins share. */
    fun hostOf(url: String) = url.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()

    /** The sign-up body; a blank code is left out (servers without invites ignore it anyway). */
    fun body(email: String, password: String, captcha: String?, invite: String): RegistrationBody = RegistrationBody(
        email = email,
        password = password,
        invite = parse(invite).code.takeIf { it.isNotEmpty() },
        captcha = captcha ?: "",
    )

    /** What to tell the user for the server's error type, if it is about the invite. */
    fun errorText(type: String?): StringResource? = when (type) {
        "MissingInvite" -> Res.string.durio_register_invite_missing
        "InvalidInvite" -> Res.string.durio_register_invite_invalid
        else -> null
    }

    /** Whether the form may be sent: an invite-only server needs a code. */
    fun complete(inviteOnly: Boolean?, invite: String): Boolean = inviteOnly != true || parse(invite).code.isNotEmpty()
}

/**
 * The sign-up form's hook: the invite code field (a pasted link works too). [inviteOnly] null
 * (unknown): shown as optional; false: not shown.
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
    if (RegistrationInvite.otherServer(value, ActiveServer.currentOrNull)) {
        Text(
            text = stringResource(Res.string.durio_register_invite_other_server, RegistrationInvite.parse(value).host.orEmpty()),
            color = MaterialTheme.colorScheme.error,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 40.dp, vertical = 4.dp),
        )
    }
    Text(
        text = stringResource(
            if (inviteOnly == true) Res.string.durio_register_invite_hint_required else Res.string.durio_register_invite_hint_optional
        ),
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 40.dp, vertical = 10.dp),
    )
}
