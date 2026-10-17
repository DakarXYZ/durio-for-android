package chat.stoat.fork.account

import android.content.Context
import android.util.Base64
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import chat.stoat.api.fork.http.AccountHttp
import chat.stoat.api.fork.http.ApiClient
import chat.stoat.api.fork.routes.logoutCurrentSession
import chat.stoat.api.routes.auth.logoutSessionById
import chat.stoat.api.routes.push.unsubscribePush
import chat.stoat.core.model.fork.ServerEndpoints
import chat.stoat.fork.keystore.AndroidKeystoreWrapKey
import chat.stoat.fork.keystore.UnwrapException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import java.io.IOException

/**
 * Ends a session on its server when the user logs out (plan AN-11). Without that the session,
 * its push subscription and (stage 4) its E2EE device stay on the server. Online it happens
 * right away; offline a WorkManager job retries it once there is a network.
 */
internal object SessionRevoker {
    private const val TIMEOUT_MILLIS = 5_000L

    suspend fun unsubscribeAndRevoke(
        context: Context,
        endpoints: ServerEndpoints,
        token: String,
        sessionId: String,
    ) {
        AccountHttp(endpoints, token).use { http ->
            // best effort: revoking the session drops the subscription on the server anyway
            reachedServer { unsubscribePush(http) }
        }
        revoke(context, endpoints, token, sessionId)
    }

    suspend fun revoke(context: Context, endpoints: ServerEndpoints, token: String, sessionId: String) {
        val done = AccountHttp(endpoints, token).use { http -> tryRevoke(http, sessionId) }
        if (!done) SessionRevokeWorker.enqueue(context, endpoints.api, token, sessionId)
    }

    /** True once the server answered; a refusal (session already gone) counts as done. */
    suspend fun tryRevoke(client: ApiClient, sessionId: String): Boolean = reachedServer {
        if (sessionId.isNotEmpty()) {
            logoutSessionById(sessionId, client)
        } else {
            logoutCurrentSession(client)
        }
    }

    private suspend fun reachedServer(block: suspend () -> Unit): Boolean {
        val result = withTimeoutOrNull(TIMEOUT_MILLIS) {
            try {
                block()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                false
            } catch (e: Exception) {
                // the server answered with an error: nothing left to revoke
                logcat(LogPriority.WARN) { "Session request refused: ${e.asLog()}" }
                true
            }
        }
        return result == true
    }
}

/** Retries [SessionRevoker] with network. The token is kept wrapped with the Keystore key. */
class SessionRevokeWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val api = inputData.getString(KEY_API) ?: return Result.failure()
        val sessionId = inputData.getString(KEY_SESSION).orEmpty()
        val wrapped = inputData.getString(KEY_TOKEN) ?: return Result.failure()
        val token = try {
            val blob = Base64.decode(wrapped, Base64.NO_WRAP)
            String(AndroidKeystoreWrapKey.wrapper().unwrap(blob), Charsets.UTF_8)
        } catch (e: UnwrapException) {
            return Result.failure()
        }

        val endpoints = ServerEndpoints(api = api, ws = "", files = "", proxy = "", webApp = "")
        val done = AccountHttp(endpoints, token).use { SessionRevoker.tryRevoke(it, sessionId) }
        return when {
            done -> Result.success()
            runAttemptCount >= MAX_ATTEMPTS -> Result.failure()
            else -> Result.retry()
        }
    }

    companion object {
        private const val KEY_API = "api"
        private const val KEY_SESSION = "session"
        private const val KEY_TOKEN = "token"
        private const val MAX_ATTEMPTS = 20

        fun enqueue(context: Context, api: String, token: String, sessionId: String) {
            val wrapped = Base64.encodeToString(
                AndroidKeystoreWrapKey.wrapper().wrap(token.toByteArray(Charsets.UTF_8)),
                Base64.NO_WRAP
            )
            val request = OneTimeWorkRequestBuilder<SessionRevokeWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setInputData(
                    workDataOf(KEY_API to api, KEY_SESSION to sessionId, KEY_TOKEN to wrapped)
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "revoke-session-${api.hashCode()}-$sessionId",
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
