package chat.stoat.fork.account

import android.content.Context
import chat.stoat.api.StoatJson
import chat.stoat.core.model.fork.ServerEndpoints
import logcat.LogPriority
import logcat.asLog
import logcat.logcat

/**
 * Server of an install from before multi-account: stage 1 block A kept it in the
 * `durio_server` preferences (plan AN-07). Read once for [LegacyAccountMigration], then removed.
 */
internal object LegacyServer {
    private const val PREFERENCES = "durio_server"
    private const val KEY_ENDPOINTS = "endpoints"

    fun load(context: Context): ServerEndpoints? {
        val json = prefs(context).getString(KEY_ENDPOINTS, null) ?: return null
        return runCatching { StoatJson.decodeFromString(ServerEndpoints.serializer(), json) }
            .onFailure { logcat(LogPriority.ERROR) { "Stored server is unreadable: ${it.asLog()}" } }
            .getOrNull()
    }

    fun clear(context: Context) {
        context.deleteSharedPreferences(PREFERENCES)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
