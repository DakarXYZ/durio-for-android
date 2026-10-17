package chat.stoat.api.fork.server

import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DiscoveryErrorAndroidTest {
    private fun failingWith(error: Throwable) = ServerDiscovery(MockEngine { throw error })

    @Test
    fun handshakeFailureIsACertificateError() = runTest {
        assertFailsWith<DiscoveryError.Certificate> {
            failingWith(SSLHandshakeException("handshake failed")).resolve("chat.example")
        }
    }

    @Test
    fun untrustedChainIsACertificateError() = runTest {
        val error = SSLHandshakeException("untrusted").apply {
            initCause(CertPathValidatorException("Trust anchor for certification path not found."))
        }
        assertFailsWith<DiscoveryError.Certificate> { failingWith(error).resolve("chat.example") }
    }

    @Test
    fun unknownHostIsNoConnection() = runTest {
        assertFailsWith<DiscoveryError.NoConnection> {
            failingWith(UnknownHostException("chat.example")).resolve("chat.example")
        }
    }

    @Test
    fun otherErrorsAreRethrown() = runTest {
        assertFailsWith<IllegalStateException> {
            failingWith(IllegalStateException("bug")).resolve("chat.example")
        }
    }
}
