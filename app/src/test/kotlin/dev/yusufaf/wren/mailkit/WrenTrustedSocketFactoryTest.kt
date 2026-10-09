package dev.yusufaf.wren.mailkit

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Generous: it only has to exceed scheduling jitter, never real I/O. */
private const val SOCKET_TIMEOUT_MS = 5_000

/**
 * Password of tls/localhost-self-signed.p12, a 100-year self-signed EC cert.
 * Regenerate with: keytool -genkeypair -alias server -keyalg EC -groupname
 * secp256r1 -sigalg SHA256withECDSA -dname CN=localhost
 * -ext SAN=dns:localhost,ip:127.0.0.1 -validity 36500 -storetype PKCS12
 * -keystore localhost-self-signed.p12 -storepass changeit -keypass changeit
 */
private const val FIXTURE_PASSWORD = "changeit"

class WrenTrustedSocketFactoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fixture = KeyStore.getInstance("PKCS12").apply {
        WrenTrustedSocketFactoryTest::class.java.getResourceAsStream("/tls/localhost-self-signed.p12")!!.use {
            load(it, FIXTURE_PASSWORD.toCharArray())
        }
    }
    private val serverCert = fixture.getCertificate("server") as X509Certificate

    private var server: SSLServerSocket? = null

    @After
    fun closeServer() {
        server?.close()
    }

    @Test
    fun `self-signed server is rejected until a trust exception is added`() {
        val factory = WrenTrustedSocketFactory(tmp.newFolder("ssl"))
        val port = startServer()

        assertThrows(SSLException::class.java) { handshake(factory, port) }

        factory.addTrustException("localhost", port, serverCert)

        handshake(factory, port)
    }

    @Test
    fun `removing a trust exception is not bypassed by a resumed TLS session`() {
        val factory = WrenTrustedSocketFactory(tmp.newFolder("ssl"))
        val port = startServer()
        factory.addTrustException("localhost", port, serverCert)

        val first = handshake(factory, port)
        val second = handshake(factory, port)
        // Without this the test passes vacuously if the provider stops resuming.
        assertArrayEquals("second handshake must resume the first session", first, second)

        factory.removeTrustException("localhost", port)

        assertThrows(SSLException::class.java) { handshake(factory, port) }
    }

    /**
     * Pinned to TLSv1.2 so resumption goes through session IDs, without
     * depending on when a TLSv1.3 NewSessionTicket gets read.
     */
    private fun startServer(): Int {
        val serverContext = SSLContext.getInstance("TLS").apply {
            val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(fixture, FIXTURE_PASSWORD.toCharArray()) }
            init(keyManagers.keyManagers, null, null)
        }
        val server = (serverContext.serverSocketFactory.createServerSocket(
            0,
            50,
            InetAddress.getLoopbackAddress(),
        ) as SSLServerSocket).apply { enabledProtocols = arrayOf("TLSv1.2") }
        this.server = server
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                try {
                    socket.use {
                        it.outputStream.write(1)
                        it.outputStream.flush()
                        it.inputStream.read()
                    }
                } catch (e: Exception) {
                    // A failed client handshake must not stop the server.
                }
            }
        }
        return server.localPort
    }

    /** Returns the TLS session id, so callers can tell a resumed session from a new one. */
    private fun handshake(factory: WrenTrustedSocketFactory, port: Int): ByteArray =
        factory.createSocket(null, "localhost", port, null).use {
            it.soTimeout = SOCKET_TIMEOUT_MS
            it.connect(InetSocketAddress("localhost", port), SOCKET_TIMEOUT_MS)
            (it as SSLSocket).startHandshake()
            it.inputStream.read()
            it.outputStream.write(1)
            it.session.id
        }
}
