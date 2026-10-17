package chat.stoat.fork.account

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import chat.stoat.api.routes.push.subscribePush
import chat.stoat.core.model.fork.UpstreamStoat
import chat.stoat.persistence.KVStorage
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import java.io.IOException

/**
 * A new FCM token goes to every account, each with its own session token and server (plan
 * AN-11). Upstream subscribed only the global session, which is empty in a cold process.
 */
object PushResubscribe {
    private const val WORK_NAME = "push-resubscribe"

    /**
     * The upstream service sends push with its own Firebase project, never ours (O12, O13), so it
     * does not get our FCM token. Any other server may be one of ours.
     */
    fun wantsPush(account: Account): Boolean = !UpstreamStoat.isUpstream(account.endpoints)

    /** From `FirebaseMessagingService.onNewToken` (a background thread). */
    suspend fun onNewToken(context: Context, fcmToken: String) {
        KVStorage(context).set("fcmToken", fcmToken)
        enqueue(context)
    }

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<PushResubscribeWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}

class PushResubscribeWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val device = KVStorage(applicationContext)
        if (device.getBoolean("pushNotificationsRejected") == true) return Result.success()
        val fcmToken = device.get("fcmToken") ?: return Result.success()

        var retry = false
        AccountManager.accounts().filter(PushResubscribe::wantsPush).forEach { account ->
            val http = AccountManager.http(account.key) ?: return@forEach
            try {
                http.use { subscribePush(auth = fcmToken, client = it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                retry = true
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "Push subscription of ${account.key} failed: ${e.asLog()}" }
            }
        }
        return if (retry && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    private companion object {
        const val MAX_ATTEMPTS = 10
    }
}
