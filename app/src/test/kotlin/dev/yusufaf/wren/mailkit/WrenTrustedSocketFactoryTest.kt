package dev.yusufaf.wren.mailkit

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Generous: it only has to exceed scheduling jitter, never real I/O. */
private const val SOCKET_TIMEOUT_MS = 5_000

class WrenTrustedSocketFactoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fixture = TlsFixture.keyStore
    private val serverCert = TlsFixture.certificate

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

    @Test
    fun `revoking with a differently-cased host is not bypassed by a resumed session`() {
        val factory = WrenTrustedSocketFactory(tmp.newFolder("ssl"))
        val port = startServer()
        factory.addTrustException("LOCALHOST", port, serverCert)

        val first = handshake(factory, port, host = "LOCALHOST")
        val second = handshake(factory, port, host = "LOCALHOST")
        assertArrayEquals("second handshake must resume the first session", first, second)

        factory.removeTrustException("localhost", port)

        assertThrows(SSLException::class.java) { handshake(factory, port, host = "LOCALHOST") }
    }

    @Test
    fun `trust exceptions are listed until removed`() {
        val factory = WrenTrustedSocketFactory(tmp.newFolder("ssl"))
        factory.addTrustException("localhost", 993, serverCert)
        factory.addTrustException("localhost", 995, serverCert)

        assertEquals(
            setOf(
                TrustException("localhost", 993, serverCert),
                TrustException("localhost", 995, serverCert),
            ),
            factory.trustExceptions().toSet(),
        )

        factory.removeTrustException("localhost", 993)

        assertEquals(listOf(TrustException("localhost", 995, serverCert)), factory.trustExceptions())
    }

    @Test
    fun `trust exceptions survive a restart`() {
        val directory = tmp.newFolder("ssl")
        WrenTrustedSocketFactory(directory).addTrustException("localhost", 993, serverCert)

        val restarted = WrenTrustedSocketFactory(directory)

        assertEquals(listOf(TrustException("localhost", 993, serverCert)), restarted.trustExceptions())
    }

    @Test
    fun `an IPv6 host is listed intact`() {
        val factory = WrenTrustedSocketFactory(tmp.newFolder("ssl"))
        factory.addTrustException("::1", 993, serverCert)

        assertEquals(listOf(TrustException("::1", 993, serverCert)), factory.trustExceptions())
    }

    /**
     * Pinned to TLSv1.2 so resumption goes through session IDs, without
     * depending on when a TLSv1.3 NewSessionTicket gets read.
     */
    private fun startServer(): Int {
        val serverContext = SSLContext.getInstance("TLS").apply {
            val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(fixture, TlsFixture.PASSWORD.toCharArray()) }
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
    private fun handshake(factory: WrenTrustedSocketFactory, port: Int, host: String = "localhost"): ByteArray =
        factory.createSocket(null, host, port, null).use {
            it.soTimeout = SOCKET_TIMEOUT_MS
            it.connect(InetSocketAddress("localhost", port), SOCKET_TIMEOUT_MS)
            (it as SSLSocket).startHandshake()
            it.inputStream.read()
            it.outputStream.write(1)
            it.session.id
        }
}
