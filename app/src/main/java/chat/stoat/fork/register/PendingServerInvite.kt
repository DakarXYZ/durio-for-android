package chat.stoat.fork.register

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import chat.stoat.activities.InviteActivity
import chat.stoat.api.routes.invites.fetchInviteByCode
import chat.stoat.core.model.data.STOAT_INVITES
import chat.stoat.fork.links.inviteUrlFromInput
import kotlinx.coroutines.CancellationException

/**
 * The invite a friend signed up with. On dur.io servers that is the standard invite to a server
 * (`/invite/<code>`, which an invite-only server takes for signing up too): once the account is
 * in the chat, the app opens that invite, one tap away from joining.
 */
object PendingServerInvite {
    @Volatile
    private var code: String? = null

    /** After a successful sign-up with [invite] (a link or a bare code). */
    fun remember(invite: String) {
        code = RegistrationInvite.parse(invite).code.takeIf { it.isNotEmpty() }
    }

    private fun take(): String? = code.also { code = null }

    /**
     * In the chat: opens the remembered invite, if it is an invite to a server or group (the
     * manual sign-up codes of a new server are not; nothing opens then).
     */
    @Composable
    fun OpenOnce() {
        val context = LocalContext.current
        LaunchedEffect(Unit) {
            val code = take() ?: return@LaunchedEffect
            val known = try {
                fetchInviteByCode(code).ok
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (!known) return@LaunchedEffect
            val url = inviteUrlFromInput(code, STOAT_INVITES) ?: return@LaunchedEffect
            context.startActivity(Intent(context, InviteActivity::class.java).setData(url.toUri()))
        }
    }
}
