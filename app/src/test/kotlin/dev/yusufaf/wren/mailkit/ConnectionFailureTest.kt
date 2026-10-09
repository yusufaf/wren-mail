package dev.yusufaf.wren.mailkit

import com.fsck.k9.mail.CertificateChainException
import com.fsck.k9.mail.CertificateValidationException
import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import net.thunderbird.core.common.exception.MessagingException
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionFailureTest {

    private val certificate = TlsFixture.certificate

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
}
