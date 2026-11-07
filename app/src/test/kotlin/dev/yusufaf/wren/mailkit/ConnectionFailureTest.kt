package dev.yusufaf.wren.mailkit

import com.fsck.k9.mail.AuthenticationFailedException
import com.fsck.k9.mail.CertificateChainException
import com.fsck.k9.mail.CertificateValidationException
import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import net.thunderbird.core.common.exception.MessagingException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionFailureTest {

    private val certificate = TlsFixture.certificate

    // Shaped the way ImapResponseParser words a tagged NO.
    private val opRejected = MessagingException(
        "Command: UID MOVE 1 \"Archive\"; response: #3# [NO, [CANNOT], read-only folder]",
        true,
    )
    private val loginRejected = MessagingException(
        "Command: *sensitive*; response: #1# [NO, [ALERT], Please log in via your web browser]",
        true,
    )
    private val authenticateRejected = MessagingException(
        "Command: AUTHENTICATE PLAIN; response: #2# [NO, [UNAVAILABLE], try later]",
        true,
    )
    private val ioError = MessagingException("IO Error", IOException("reset"))

    /** Shaped the way RealImapConnection reports a rejected certificate. */
    private fun handshakeFailure(chain: Array<java.security.cert.X509Certificate>) =
        SSLHandshakeException("handshake").apply {
            initCause(CertificateChainException("untrusted", chain, null))
        }

    @Test
    fun `a certificate validation failure yields the leaf for the account's host and port`() {
        val failure = CertificateValidationException(listOf(certificate), handshakeFailure(arrayOf(certificate)))

        assertEquals(
            ConnectionFailure.UntrustedCertificate(TEST_ACCOUNT.host, TEST_ACCOUNT.port, certificate),
            failure.toConnectionFailure(TEST_ACCOUNT),
        )
    }

    @Test
    fun `a certificate failure wrapped as an IO error is still recognised`() {
        val failure = MessagingException("IO Error", handshakeFailure(arrayOf(certificate)))

        assertEquals(
            ConnectionFailure.UntrustedCertificate(TEST_ACCOUNT.host, TEST_ACCOUNT.port, certificate),
            failure.toConnectionFailure(TEST_ACCOUNT),
        )
    }

    @Test
    fun `other failures keep their message`() {
        assertEquals(
            ConnectionFailure.Other("NO login failed"),
            MessagingException("NO login failed").toConnectionFailure(TEST_ACCOUNT),
        )
    }

    @Test
    fun `a failure without a message falls back to its type`() {
        assertEquals(
            ConnectionFailure.Other("java.io.IOException"),
            IOException().toConnectionFailure(TEST_ACCOUNT),
        )
    }

    @Test
    fun `an empty certificate chain is not an untrusted-certificate failure`() {
        assertEquals(
            ConnectionFailure.Other("handshake"),
            handshakeFailure(emptyArray()).toConnectionFailure(TEST_ACCOUNT),
        )
    }

    @Test
    fun `a server rejection is not connection-level`() {
        assertFalse(opRejected.isConnectionLevel())
    }

    @Test
    fun `an IO error is connection-level`() {
        assertTrue(ioError.isConnectionLevel())
    }

    @Test
    fun `an authentication failure is connection-level`() {
        assertTrue(AuthenticationFailedException("Authentication failed").isConnectionLevel())
    }

    @Test
    fun `a login rejected with a response code is connection-level`() {
        assertTrue(loginRejected.isConnectionLevel())
    }

    @Test
    fun `an AUTHENTICATE rejected with a response code is connection-level`() {
        assertTrue(authenticateRejected.isConnectionLevel())
    }

    @Test
    fun `a bare IOException is connection-level`() {
        assertTrue(IOException().isConnectionLevel())
    }

    @Test
    fun `an unexpected runtime error is not connection-level`() {
        assertFalse(IllegalStateException("bug").isConnectionLevel())
    }

    @Test
    fun `an authentication failure is a login rejection`() {
        assertTrue(AuthenticationFailedException("Authentication failed").isLoginRejection())
    }

    @Test
    fun `a login rejected with a response code is a login rejection`() {
        assertTrue(loginRejected.isLoginRejection())
    }

    @Test
    fun `an IO error is not a login rejection`() {
        assertFalse(ioError.isLoginRejection())
    }

    @Test
    fun `a server rejection of an op is not a login rejection`() {
        assertFalse(opRejected.isLoginRejection())
    }
}
