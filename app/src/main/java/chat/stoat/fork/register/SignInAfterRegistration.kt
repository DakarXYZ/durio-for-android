package chat.stoat.fork.register

import chat.stoat.api.StoatAPI
import chat.stoat.api.fork.http.ApiClient
import chat.stoat.api.fork.routes.fetchServerRoot
import chat.stoat.api.routes.account.negotiateAuthentication
import chat.stoat.api.routes.onboard.needsOnboarding
import chat.stoat.fork.account.AccountManager
import kotlinx.coroutines.CancellationException

/**
 * A server without SMTP (`features.email` false, the default of dur.io servers) sends no
 * verification mail and verifies the account at once. Upstream's "check your mail" screen then
 * waits for a mail that never comes: instead the app signs in with the credentials just entered
 * and goes on to choosing the username, as the login screen does.
 */
object SignInAfterRegistration {
    /** `GET /` → `features.email` of the chosen server; null when it could not be asked. */
    suspend fun emailVerification(): Boolean? = try {
        fetchServerRoot(ApiClient.Active).features?.email
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * Signs in a freshly registered account. The route to go to ("register/onboarding" for the
     * username, or "chat"), or null when signing in did not work: then the login screen.
     */
    suspend fun signIn(email: String, password: String): String? {
        val response = try {
            negotiateAuthentication(email, password)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        if (response.error != null || response.proceedMfa) return null
        val hints = response.firstUserHints ?: return null

        return try {
            AccountManager.completeLogin(hints)
            if (needsOnboarding(hints.token)) {
                "register/onboarding"
            } else {
                StoatAPI.loginAs(hints.token)
                StoatAPI.setSessionId(hints.id)
                AccountManager.onLoggedIn()
                "chat"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
