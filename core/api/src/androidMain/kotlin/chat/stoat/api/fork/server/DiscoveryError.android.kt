package chat.stoat.api.fork.server

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import java.security.GeneralSecurityException
import javax.net.ssl.SSLException

internal actual fun platformDiscoveryError(e: Throwable): DiscoveryError? {
    val chain = generateSequence(e) { it.cause }.take(8).toList()
    return when {
        // SSLHandshakeException, SSLPeerUnverifiedException; CertificateException and
        // CertPathValidatorException are GeneralSecurityException
        chain.any { it is SSLException || it is GeneralSecurityException } -> DiscoveryError.Certificate(e)

        chain.any {
            it is UnknownHostException || it is UnresolvedAddressException || it is ConnectException ||
                it is NoRouteToHostException || it is SocketTimeoutException
        } -> DiscoveryError.NoConnection(e)

        else -> null
    }
}
