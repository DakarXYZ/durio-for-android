package chat.stoat.core.crypto

import org.junit.Assume.assumeTrue
import java.io.File

/**
 * Tests against the real core need its host build (linux-x86-64) in `.durio-crypto/host`
 * (scripts/durio-crypto-local.sh); elsewhere — Windows, CI without a local core — they are
 * skipped and the fakes in `:core:api` cover the logic.
 */
object HostLibrary {
    fun assume() {
        val dir = System.getProperty("jna.library.path").orEmpty()
        val linux = System.getProperty("os.name").orEmpty().lowercase().contains("linux")
        assumeTrue(
            "host build of the core not available",
            linux && File(dir, "libstoat_crypto_ffi.so").isFile
        )
    }
}
