package dev.yusufaf.wren.mailkit

import com.fsck.k9.mail.ssl.LocalKeyStore
import com.fsck.k9.mail.ssl.TrustManagerFactory
import com.fsck.k9.mail.ssl.TrustedSocketFactory
import java.io.File
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * TLS sockets built on the vendored mail-stack trust plumbing:
 * [TrustManagerFactory] validates the chain against the system store AND
 * verifies the hostname against the certificate ([LocalKeyStore] is its
 * fallback for certificates the user explicitly accepted — unused in v1 but
 * required by the API).
 *
 * The null-socket branch must return an UNCONNECTED socket — the IMAP
 * connection connects it itself with its own timeout; a pre-connected socket
 * makes that second connect throw "already connected".
 */
class WrenTrustedSocketFactory(keyStoreDirectory: File) : TrustedSocketFactory {

    private val trustManagerFactory = TrustManagerFactory.createInstance(
        LocalKeyStore { keyStoreDirectory.apply { mkdirs() } },
    )

    override fun createSocket(
        socket: Socket?,
        host: String,
        port: Int,
        clientCertificateAlias: String?,
    ): Socket {
        val trustManager = trustManagerFactory.getTrustManagerForDomain(host, port)
        val factory = SSLContext.getInstance("TLS")
            .apply { init(null, arrayOf(trustManager), null) }
            .socketFactory
        val sslSocket = if (socket == null) {
            factory.createSocket()
        } else {
            // STARTTLS: wrap the already-connected plain socket.
            factory.createSocket(socket, host, port, true)
        }
        // SNI, so shared hosts present the certificate for [host]. Hard cast:
        // an SSLContext factory always returns SSLSocket, and silently skipping
        // SNI would be worse than crashing here.
        (sslSocket as SSLSocket).sslParameters = sslSocket.sslParameters.apply {
            serverNames = listOf(SNIHostName(host))
        }
        return sslSocket
    }
}
