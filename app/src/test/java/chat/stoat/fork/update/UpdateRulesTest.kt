package chat.stoat.fork.update

import chat.stoat.api.StoatJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Update check against our GitHub Releases (plan AN-18): manifest, ABI choice, version, hash
class UpdateRulesTest {
    private val sha = "a".repeat(64)

    // the shape the release workflow writes (AN-14)
    private val manifestJson = """
        {
          "versionCode": 1008002,
          "versionName": "1.8.2",
          "apks": [
            { "abi": "arm64-v8a", "file": "durio-1.8.2-arm64-v8a.apk", "sha256": "$sha" },
            { "abi": "x86_64", "file": "durio-1.8.2-x86_64.apk", "sha256": "$sha" },
            { "abi": "universal", "file": "durio-1.8.2-universal.apk", "sha256": "$sha" }
          ]
        }
    """.trimIndent()

    // trimmed answer of GET /repos/DakarXYZ/durio-for-android/releases/latest
    private val releaseJson = """
        {
          "tag_name": "v1.8.2",
          "html_url": "https://github.com/DakarXYZ/durio-for-android/releases/tag/v1.8.2",
          "draft": false,
          "prerelease": false,
          "author": { "login": "DakarXYZ" },
          "assets": [
            { "name": "update.json", "browser_download_url": "https://github.com/dl/update.json", "size": 300 },
            { "name": "durio-1.8.2-arm64-v8a.apk", "browser_download_url": "https://github.com/dl/arm64.apk", "size": 1 },
            { "name": "durio-1.8.2-x86_64.apk", "browser_download_url": "https://github.com/dl/x86_64.apk", "size": 1 },
            { "name": "durio-1.8.2-universal.apk", "browser_download_url": "https://github.com/dl/universal.apk", "size": 1 }
          ]
        }
    """.trimIndent()

    private val manifest = StoatJson.decodeFromString(UpdateManifest.serializer(), manifestJson)
    private val release = StoatJson.decodeFromString(GithubRelease.serializer(), releaseJson)

    @Test
    fun parsesManifestAndRelease() {
        assertEquals(1008002, manifest.versionCode)
        assertEquals(3, manifest.apks.size)
        assertEquals("v1.8.2", release.tagName)
        assertEquals("https://github.com/dl/update.json", UpdateRules.manifestUrl(release))
    }

    @Test
    fun picksTheDevicesAbiThenUniversal() {
        assertEquals("arm64-v8a", UpdateRules.pickApk(manifest, listOf("arm64-v8a", "armeabi-v7a"))?.abi)
        assertEquals("x86_64", UpdateRules.pickApk(manifest, listOf("x86_64", "x86", "arm64-v8a"))?.abi)
        assertEquals("universal", UpdateRules.pickApk(manifest, listOf("armeabi-v7a"))?.abi)
        assertNull(UpdateRules.pickApk(manifest.copy(apks = manifest.apks.take(1)), listOf("x86")))
    }

    @Test
    fun offersOnlyNewerVersions() {
        val update = UpdateRules.availableUpdate(release, manifest, 1008001, listOf("arm64-v8a"))
        assertEquals("1.8.2", update?.versionName)
        assertEquals("https://github.com/dl/arm64.apk", update?.downloadUrl)
        assertNull(UpdateRules.availableUpdate(release, manifest, 1008002, listOf("arm64-v8a")))
        assertNull(UpdateRules.availableUpdate(release, manifest, 1009000, listOf("arm64-v8a")))
    }

    @Test
    fun ignoresDraftsPrereleasesAndBrokenEntries() {
        assertNull(UpdateRules.manifestUrl(release.copy(prerelease = true)))
        assertNull(UpdateRules.manifestUrl(release.copy(draft = true)))
        assertNull(UpdateRules.manifestUrl(release.copy(assets = release.assets.drop(1))))

        val badHash = manifest.copy(apks = manifest.apks.map { it.copy(sha256 = "xyz") })
        assertNull(UpdateRules.availableUpdate(release, badHash, 1, listOf("arm64-v8a")))
        val badName = manifest.copy(apks = listOf(manifest.apks[0].copy(file = "../../evil.apk")))
        assertNull(UpdateRules.availableUpdate(release, badName, 1, listOf("arm64-v8a")))
        // the manifest names a file the release does not have
        val missing = manifest.copy(apks = listOf(manifest.apks[0].copy(file = "other.apk")))
        assertNull(UpdateRules.availableUpdate(release, missing, 1, listOf("arm64-v8a")))
    }

    @Test
    fun checksAtMostOnceADay() {
        val day = UpdateRules.CHECK_INTERVAL_MS
        assertTrue(UpdateRules.isDue(null, 1000))
        assertFalse(UpdateRules.isDue(1000, 1000 + day - 1))
        assertTrue(UpdateRules.isDue(1000, 1000 + day))
        // clock moved back
        assertTrue(UpdateRules.isDue(5000, 1000))
    }

    @Test
    fun verifiesTheDownloadsHash() {
        val file = File.createTempFile("update", ".apk")
        try {
            file.writeText("abc")
            val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            assertTrue(UpdateRules.matches(file, abc))
            assertTrue(UpdateRules.matches(file, abc.uppercase()))
            assertFalse(UpdateRules.matches(file, sha))
        } finally {
            file.delete()
        }
    }
}
