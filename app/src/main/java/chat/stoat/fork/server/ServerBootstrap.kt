package chat.stoat.fork.server

import android.content.Context
import androidx.core.content.edit
import chat.stoat.BuildConfig
import chat.stoat.api.StoatJson
import chat.stoat.api.fork.server.ServerDiscovery
import chat.stoat.core.model.fork.ActiveServer
import chat.stoat.core.model.fork.ServerEndpoints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import logcat.LogPriority
import logcat.asLog
import logcat.logcat

/**
 * Fills [ActiveServer] before the first request (plan AN-07).
 *
 * Stage 1 keeps one account, so this stores just the server of that account.
 * TODO(AN-08): replace the preferences file with AccountStore (server per account).
 * TODO(AN-10): the login screen discovers the typed server and calls [select].
 */
object ServerBootstrap {
    private const val PREFERENCES = "durio_server"
    private const val KEY_ENDPOINTS = "endpoints"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Call first thing in Application.onCreate: one small synchronous read. */
    fun init(context: Context) {
        val stored = load(context)
        if (stored != null) {
            ActiveServer.set(stored)
            return
        }

        // No server yet: prefill from the build if there is one (empty by default, O13).
        // Until it resolves, or without it, the app starts at the login screen.
        val defaultServer = BuildConfig.DEFAULT_SERVER
        if (defaultServer.isBlank()) return

        scope.launch {
            try {
                val endpoints = ServerDiscovery.create(allowInsecure = BuildConfig.DEBUG)
                    .use { it.resolve(defaultServer) }
                if (ActiveServer.currentOrNull == null) select(context, endpoints)
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "Could not resolve default server: ${e.asLog()}" }
            }
        }
    }

    fun select(context: Context, endpoints: ServerEndpoints) {
        ActiveServer.set(endpoints)
        prefs(context).edit {
            putString(KEY_ENDPOINTS, StoatJson.encodeToString(ServerEndpoints.serializer(), endpoints))
        }
    }

    private fun load(context: Context): ServerEndpoints? {
        val json = prefs(context).getString(KEY_ENDPOINTS, null) ?: return null
        return runCatching { StoatJson.decodeFromString(ServerEndpoints.serializer(), json) }
            .onFailure { logcat(LogPriority.ERROR) { "Stored server is unreadable: ${it.asLog()}" } }
            .getOrNull()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
