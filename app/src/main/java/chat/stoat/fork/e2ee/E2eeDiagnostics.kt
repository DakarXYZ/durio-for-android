package chat.stoat.fork.e2ee

import android.content.Context
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import chat.stoat.BuildConfig
import chat.stoat.core.crypto.CryptoStores
import chat.stoat.fork.account.AccountManager
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * "Send diagnostic report" instead of Sentry (plan CR-22, O8): the core's `debugReport()` (counts,
 * states and error codes; its own user and device id are the only ids in it, ADR A7) plus what
 * the app knows without secrets — version, build, ABI, Android version, E2EE status, number of
 * accounts. No server address, no other ids, no message text. The user sees the text before it
 * leaves and picks where it goes (system share sheet); nothing is sent by the app itself.
 */
object E2eeDiagnostics {
    const val FORMAT = 1

    /** The app's part of the report: no ids, no addresses. */
    data class AppInfo(
        val versionName: String,
        val versionCode: Long,
        /** ABI of the installed APK (from the versionCode, AN-41) and of the device. */
        val apkAbi: String,
        val deviceAbi: String,
        val androidRelease: String,
        val sdk: Int,
        val coreVersion: String,
        val e2eeStatus: String,
        val syncProblem: Boolean,
        val accounts: Int,
        val ownServerAccounts: Int,
    )

    /** versionCode = base × 10 + ABI code (app/build.gradle.kts, AN-41). */
    fun apkAbi(versionCode: Long): String = when ((versionCode % 10).toInt()) {
        0 -> "universal"
        1 -> "armeabi-v7a"
        2 -> "arm64-v8a"
        3 -> "x86_64"
        else -> "unknown"
    }

    /** E2EE status by name only ([E2eeStatus.Failed] carries a class name, never content). */
    fun statusName(status: E2eeStatus): String = when (status) {
        is E2eeStatus.Failed -> "Failed(${status.code})"
        else -> status::class.simpleName ?: "Unknown"
    }

    fun appInfo(context: Context): AppInfo {
        val versionCode = try {
            PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
        } catch (e: Exception) {
            BuildConfig.VERSION_CODE.toLong()
        }
        val accounts = AccountManager.state.value.accounts
        return AppInfo(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = versionCode,
            apkAbi = apkAbi(versionCode),
            deviceAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            androidRelease = Build.VERSION.RELEASE ?: "?",
            sdk = Build.VERSION.SDK_INT,
            coreVersion = try {
                CryptoStores.coreVersion()
            } catch (e: Throwable) {
                // the native library did not load: worth reporting by itself
                "unavailable"
            },
            e2eeStatus = statusName(E2eeManager.status.value),
            syncProblem = E2eeManager.syncProblem.value,
            accounts = accounts.size,
            ownServerAccounts = accounts.count { it.ownServer },
        )
    }

    /** The core's report of the active device; null without one (the status says why). */
    suspend fun coreReport(): String? {
        val core = E2eeManager.active?.core ?: return null
        return try {
            core.debugReport()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** The text the user sees and shares. */
    fun compose(app: AppInfo, coreReport: String?, now: Instant = Instant.now()): String = buildString {
        appendLine("dur.io diagnostic report")
        appendLine("format: $FORMAT")
        appendLine("created: ${now.truncatedTo(ChronoUnit.SECONDS)}")
        appendLine("app: ${app.versionName} (${app.versionCode}), apk ${app.apkAbi}, device ${app.deviceAbi}")
        appendLine("android: ${app.androidRelease} (API ${app.sdk})")
        appendLine("e2ee core: ${app.coreVersion}")
        appendLine("e2ee status: ${app.e2eeStatus}${if (app.syncProblem) ", sync problem" else ""}")
        appendLine("accounts: ${app.accounts} (${app.ownServerAccounts} on dur.io servers)")
        appendLine()
        appendLine("core report:")
        appendLine(coreReport ?: "unavailable (no E2EE device on the active account)")
    }

    suspend fun build(context: Context): String = compose(appInfo(context), coreReport())

    fun fileName(now: Instant = Instant.now()) =
        "dur.io-diagnostics-${now.truncatedTo(ChronoUnit.SECONDS).toString().replace(":", "-")}.txt"
}
