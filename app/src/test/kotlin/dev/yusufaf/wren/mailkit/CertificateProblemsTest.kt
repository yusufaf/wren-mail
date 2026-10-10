package dev.yusufaf.wren.mailkit

import dev.yusufaf.wren.mailkit.CertificateProblem.EXPIRED
import dev.yusufaf.wren.mailkit.CertificateProblem.HOSTNAME_MISMATCH
import dev.yusufaf.wren.mailkit.CertificateProblem.NOT_YET_VALID
import dev.yusufaf.wren.mailkit.CertificateProblem.SELF_SIGNED
import dev.yusufaf.wren.mailkit.CertificateProblem.UNTRUSTED_ISSUER
import java.security.cert.X509Certificate
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Test

class CertificateProblemsTest {
    private val certificate = TlsFixture.certificate
    private val caIssued = TlsFixture.caIssuedCertificate

    // Each fixture is checked inside its own validity window: the CA-issued
    // one is generated later than the p12 and keytool doesn't backdate.
    private val X509Certificate.validAt: Date get() = Date(notBefore.time + 1)

    @Test
    fun `a self-signed certificate for the host is only self-signed`() {
        assertEquals(listOf(SELF_SIGNED), certificate.problemsFor("localhost", certificate.validAt))
    }

    @Test
    fun `an IP host matches the certificate's IP name`() {
        assertEquals(listOf(SELF_SIGNED), certificate.problemsFor("127.0.0.1", certificate.validAt))
    }

    @Test
    fun `a certificate for another host is a hostname mismatch`() {
        assertEquals(
            listOf(HOSTNAME_MISMATCH, SELF_SIGNED),
            certificate.problemsFor("mail.example.com", certificate.validAt),
        )
    }

    @Test
    fun `a certificate past its end date is expired`() {
        val now = Date(certificate.notAfter.time + 1)
        assertEquals(listOf(EXPIRED, SELF_SIGNED), certificate.problemsFor("localhost", now))
    }

    @Test
    fun `a certificate before its start date is not yet valid`() {
        val now = Date(certificate.notBefore.time - 1)
        assertEquals(listOf(NOT_YET_VALID, SELF_SIGNED), certificate.problemsFor("localhost", now))
    }

    @Test
    fun `a CA-issued certificate with no other problem has an untrusted issuer`() {
        assertEquals(listOf(UNTRUSTED_ISSUER), caIssued.problemsFor("localhost", caIssued.validAt))
    }

    @Test
    fun `an untrusted issuer is not claimed alongside another problem`() {
        assertEquals(
            listOf(HOSTNAME_MISMATCH),
            caIssued.problemsFor("mail.example.com", caIssued.validAt),
        )
    }

    @Test
    fun `a failure's problems are checked against its own host`() {
        val failure = ConnectionFailure.UntrustedCertificate("mail.example.com", 993, certificate)
        assertEquals(listOf(HOSTNAME_MISMATCH, SELF_SIGNED), failure.problems(certificate.validAt))
    }
}
