package chat.stoat.fork.e2ee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

// The diagnostic report (CR-22): what the app adds to the core's report, and nothing more
class E2eeDiagnosticsTest {
    private val app = E2eeDiagnostics.AppInfo(
        versionName = "1.8.1",
        versionCode = 10080012,
        apkAbi = E2eeDiagnostics.apkAbi(10080012),
        deviceAbi = "arm64-v8a",
        androidRelease = "15",
        sdk = 35,
        coreVersion = "0.2.0",
        e2eeStatus = E2eeDiagnostics.statusName(E2eeStatus.Ready),
        syncProblem = true,
        accounts = 2,
        ownServerAccounts = 1,
    )

    @Test
    fun apkAbiFromTheVersionCode() {
        assertEquals("universal", E2eeDiagnostics.apkAbi(10080010))
        assertEquals("armeabi-v7a", E2eeDiagnostics.apkAbi(10080011))
        assertEquals("arm64-v8a", E2eeDiagnostics.apkAbi(10080012))
        assertEquals("x86_64", E2eeDiagnostics.apkAbi(10080013))
    }

    @Test
    fun statusByNameOnly() {
        assertEquals("KeyLost", E2eeDiagnostics.statusName(E2eeStatus.KeyLost))
        assertEquals("Failed(StoreLocked)", E2eeDiagnostics.statusName(E2eeStatus.Failed("StoreLocked")))
    }

    @Test
    fun reportHasTheAppPartAndTheCoreReportAsIs() {
        val core = """{"format":1,"user":"@01ka:stoat.invalid","sessions":{"total":3}}"""
        val report = E2eeDiagnostics.compose(app, core, Instant.parse("2026-10-18T12:34:56.789Z"))
        assertTrue(report.startsWith("dur.io diagnostic report\nformat: 1\n"))
        assertTrue("created: 2026-10-18T12:34:56Z" in report)
        assertTrue("app: 1.8.1 (10080012), apk arm64-v8a, device arm64-v8a" in report)
        assertTrue("android: 15 (API 35)" in report)
        assertTrue("e2ee core: 0.2.0" in report)
        assertTrue("e2ee status: Ready, sync problem" in report)
        assertTrue("accounts: 2 (1 on dur.io servers)" in report)
        assertTrue(report.trimEnd().endsWith(core))
        // no server address of any kind
        assertFalse(Regex("https?://|wss?://|\\.duckdns\\.|\\b\\d{1,3}(\\.\\d{1,3}){3}\\b").containsMatchIn(report))
    }

    @Test
    fun withoutADeviceTheReportSaysSo() {
        val report = E2eeDiagnostics.compose(app.copy(e2eeStatus = "Off", syncProblem = false), null)
        assertTrue("e2ee status: Off\n" in report)
        assertTrue("unavailable (no E2EE device on the active account)" in report)
    }

    @Test
    fun fileNameIsSafe() {
        assertEquals(
            "dur.io-diagnostics-2026-10-18T12-34-56Z.txt",
            E2eeDiagnostics.fileName(Instant.parse("2026-10-18T12:34:56.5Z")),
        )
    }
}
