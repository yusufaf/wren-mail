package dev.yusufaf.wren.mailkit

import dev.yusufaf.wren.mailkit.CertificateProblem.EXPIRED
import dev.yusufaf.wren.mailkit.CertificateProblem.HOSTNAME_MISMATCH
import dev.yusufaf.wren.mailkit.CertificateProblem.NOT_YET_VALID
import dev.yusufaf.wren.mailkit.CertificateProblem.SELF_SIGNED
import dev.yusufaf.wren.mailkit.CertificateProblem.UNTRUSTED_ISSUER
import java.security.cert.X509Certificate
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CertificateProblemsTest {
    private val certificate = TlsFixture.certificate
    private val caIssued = TlsFixture.caIssuedCertificate
    private val cnOnly = TlsFixture.cnOnlyCertificate
    private val ipCnOnly = TlsFixture.ipCnOnlyCertificate
    private val otherSan = TlsFixture.otherSanCertificate
    private val ipSanOnly = TlsFixture.ipSanOnlyCertificate

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
    fun `the fixtures without subject alternative names really have none`() {
        assertNull(cnOnly.subjectAlternativeNames)
        assertNull(ipCnOnly.subjectAlternativeNames)
    }

    @Test
    fun `a certificate with no alternative names matches the host by its common name`() {
        assertEquals(listOf(SELF_SIGNED), cnOnly.problemsFor("localhost", cnOnly.validAt))
    }

    @Test
    fun `the common name is matched case-insensitively`() {
        assertEquals(listOf(SELF_SIGNED), cnOnly.problemsFor("LOCALHOST", cnOnly.validAt))
    }

    @Test
    fun `a common name for another host is a hostname mismatch`() {
        assertEquals(
            listOf(HOSTNAME_MISMATCH, SELF_SIGNED),
            cnOnly.problemsFor("mail.example.com", cnOnly.validAt),
        )
    }

    @Test
    fun `an IP host is never matched against the common name`() {
        // The verifier only checks IP names for an IP host (RFC 2818), same as the trust manager.
        assertEquals(
            listOf(HOSTNAME_MISMATCH, SELF_SIGNED),
            ipCnOnly.problemsFor("127.0.0.1", ipCnOnly.validAt),
        )
    }

    @Test
    fun `the fixtures with alternative names have only the expected ones`() {
        assertEquals(listOf(listOf(2, "other.example")), otherSan.subjectAlternativeNames.toList())
        assertEquals(listOf(listOf(7, "127.0.0.1")), ipSanOnly.subjectAlternativeNames.toList())
    }

    @Test
    fun `a DNS alternative name takes precedence over a matching common name`() {
        // RFC 6125 6.4.4 / RFC 2818 3.1: the CN is ignored once a DNS SAN is present.
        assertEquals(
            listOf(HOSTNAME_MISMATCH, SELF_SIGNED),
            otherSan.problemsFor("localhost", otherSan.validAt),
        )
    }

    @Test
    fun `the common name is still used when the only alternative names are IP addresses`() {
        // RFC 2818 only rules out the CN when a dNSName SAN is present, and
        // DefaultHostnameVerifier falls back to the CN when there are no DNS-type SANs.
        assertEquals(listOf(SELF_SIGNED), ipSanOnly.problemsFor("localhost", ipSanOnly.validAt))
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
