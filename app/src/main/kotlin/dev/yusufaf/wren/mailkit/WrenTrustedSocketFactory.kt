package dev.yusufaf.wren.mailkit

import com.fsck.k9.mail.ssl.LocalKeyStore
import com.fsck.k9.mail.ssl.TrustManagerFactory
import com.fsck.k9.mail.ssl.TrustedSocketFactory
import java.io.File
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * TLS sockets built on the vendored mail-stack trust plumbing:
 * [TrustManagerFactory] validates the chain against the system store AND
 * verifies the hostname against the certificate ([LocalKeyStore] is its
 * fallback for certificates the user explicitly accepted). Exceptions must go
 * through [addTrustException] / [removeTrustException]: this factory's
 * [LocalKeyStore] loads its file once, so a second [LocalKeyStore] on the same
 * directory would go unseen.
 *
 * The null-socket branch must return an UNCONNECTED socket — the IMAP
 * connection connects it itself with its own timeout; a pre-connected socket
 * makes that second connect throw "already connected".
 */
class WrenTrustedSocketFactory(keyStoreDirectory: File) : TrustedSocketFactory {

    private val localKeyStore = LocalKeyStore { keyStoreDirectory.apply { mkdirs() } }
    private val trustManagerFactory = TrustManagerFactory.createInstance(localKeyStore)

    // Keyed by host+port — [TrustManagerFactory.getTrustManagerForDomain] and
    // the [LocalKeyStore] exception store underneath it are themselves keyed
    // by host+port, so a host-only cache could hand a connection on one port
    // a trust manager (and its accepted-certificate exceptions) recorded for
    // a different port on the same host. Distinct servers each get their own
    // trust manager and context; repeat calls to the same host+port reuse
    // one SSLContext and get a shot at TLS session resumption instead of a
    // fresh handshake state machine per socket. A trust change evicts the
    // host+port entry: a cached context's session cache resumes sessions
    // without calling the trust manager, so a revoked exception would keep
    // working.
    private val contextsByHostPort = mutableMapOf<Pair<String, Int>, SSLContext>()

    /**
     * Trusts [certificate] for [host]:[port]. The trust change and the cache
     * eviction take effect even if persisting the keystore fails; that can
     * surface as [CertificateException] or as an [java.io.IOException] from
     * opening the keystore file.
     */
    @Throws(CertificateException::class)
    fun addTrustException(host: String, port: Int, certificate: X509Certificate) {
        synchronized(contextsByHostPort) {
            try {
                localKeyStore.addCertificate(host, port, certificate)
            } finally {
                contextsByHostPort.remove(host to port)
            }
        }
    }

    /**
     * Revokes the exception for [host]:[port]. Connections that are already
     * open or pooled are unaffected; the caller closes them. The revocation
     * and the cache eviction take effect even if persisting the keystore
     * fails — a write failure is only logged, so the certificate would be
     * trusted again after a restart.
     */
    fun removeTrustException(host: String, port: Int) {
        synchronized(contextsByHostPort) {
            try {
                localKeyStore.deleteCertificate(host, port)
            } finally {
                contextsByHostPort.remove(host to port)
            }
        }
    }

    override fun createSocket(
        socket: Socket?,
        host: String,
        port: Int,
        clientCertificateAlias: String?,
    ): Socket {
        val factory = synchronized(contextsByHostPort) {
            contextsByHostPort.getOrPut(host to port) {
                val trustManager = trustManagerFactory.getTrustManagerForDomain(host, port)
                SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
            }
        }.socketFactory
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
