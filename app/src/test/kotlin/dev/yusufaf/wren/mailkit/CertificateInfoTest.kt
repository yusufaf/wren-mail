package dev.yusufaf.wren.mailkit

import org.junit.Assert.assertEquals
import org.junit.Test

class CertificateInfoTest {

    @Test
    fun `describes the fixture certificate`() {
        val certificate = TlsFixture.certificate

        val info = certificate.toCertificateInfo()

        assertEquals("CN=localhost", info.subject)
        assertEquals("CN=localhost", info.issuer)
        // keytool -list -v on tls/localhost-self-signed.p12
        assertEquals(
            "9E:60:79:69:37:A1:9A:83:2A:BE:9C:74:54:F4:60:B6:69:F7:63:4A:CB:23:8B:D7:92:50:E9:1A:C8:B1:E3:B2",
            info.sha256Fingerprint,
        )
        assertEquals(certificate.notBefore, info.validFrom)
        assertEquals(certificate.notAfter, info.validUntil)
    }
}
