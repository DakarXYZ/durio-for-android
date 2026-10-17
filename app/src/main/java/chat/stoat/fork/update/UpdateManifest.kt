package chat.stoat.fork.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * `update.json`, published next to the APKs of every GitHub release by the release workflow
 * (plan AN-14, AN-18). One entry per ABI split plus `universal`.
 */
@Serializable
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apks: List<UpdateApk>,
)

@Serializable
data class UpdateApk(
    val abi: String,
    val file: String,
    val sha256: String,
)

/** The part of GitHub's `releases/latest` answer we read. */
@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
data class GithubAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long = 0,
)

/** An update the user can install: which APK, from where, and its expected hash. */
data class AvailableUpdate(
    val versionName: String,
    val versionCode: Int,
    val apk: UpdateApk,
    val downloadUrl: String,
    val releaseUrl: String,
)

/** Pure rules of the update check (plan AN-18), kept apart for unit tests. */
object UpdateRules {
    const val MANIFEST_ASSET = "update.json"
    const val UNIVERSAL = "universal"
    const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    fun manifestUrl(release: GithubRelease): String? {
        if (release.draft || release.prerelease) return null
        return release.assets.firstOrNull { it.name == MANIFEST_ASSET }?.downloadUrl
    }

    /**
     * The APK for this device: the first of its ABIs (`Build.SUPPORTED_ABIS`, preferred first)
     * that has a split, else the universal one.
     */
    fun pickApk(manifest: UpdateManifest, supportedAbis: List<String>): UpdateApk? =
        supportedAbis.firstNotNullOfOrNull { abi -> manifest.apks.firstOrNull { it.abi == abi } }
            ?: manifest.apks.firstOrNull { it.abi == UNIVERSAL }

    /** Null when the release is not newer or has nothing for this device. */
    fun availableUpdate(
        release: GithubRelease,
        manifest: UpdateManifest,
        currentVersionCode: Int,
        supportedAbis: List<String>,
    ): AvailableUpdate? {
        if (manifest.versionCode <= currentVersionCode) return null
        val apk = pickApk(manifest, supportedAbis) ?: return null
        if (!isSafeFileName(apk.file) || !isSha256(apk.sha256)) return null
        val asset = release.assets.firstOrNull { it.name == apk.file } ?: return null
        return AvailableUpdate(manifest.versionName, manifest.versionCode, apk, asset.downloadUrl, release.htmlUrl)
    }

    fun isDue(lastCheck: Long?, now: Long): Boolean =
        lastCheck == null || now - lastCheck >= CHECK_INTERVAL_MS || now < lastCheck

    // the name becomes a file in cacheDir/updates
    fun isSafeFileName(name: String) =
        name.isNotEmpty() && name.endsWith(".apk") && name.all { it.isLetterOrDigit() || it in "._-+" } &&
            !name.startsWith(".")

    fun isSha256(hex: String) = hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun matches(file: File, expectedSha256: String): Boolean =
        file.inputStream().use { sha256(it) }.equals(expectedSha256, ignoreCase = true)
}
