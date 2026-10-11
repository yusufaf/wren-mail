package dev.yusufaf.wren.ui

import dev.yusufaf.wren.mailkit.CertificateProblem.HOSTNAME_MISMATCH
import dev.yusufaf.wren.mailkit.CertificateProblem.SELF_SIGNED
import org.junit.Assert.assertEquals
import org.junit.Test

class CertificatePromptTextTest {
    @Test
    fun `a hostname mismatch says the certificate was not issued for the host`() {
        assertEquals(
            "Not issued for 192.168.1.10",
            problemText(HOSTNAME_MISMATCH, "192.168.1.10", commonNameOnly = false),
        )
    }

    @Test
    fun `an IP host named only by the common name is explained`() {
        assertEquals(
            "Names 192.168.1.10 only as its common name, not as an IP address",
            problemText(HOSTNAME_MISMATCH, "192.168.1.10", commonNameOnly = true),
        )
    }

    @Test
    fun `other problems ignore the common-name case`() {
        assertEquals("Self-signed", problemText(SELF_SIGNED, "192.168.1.10", commonNameOnly = true))
    }

    @Test
    fun `the mismatch warning says the certificate is for another server`() {
        assertEquals(
            "This certificate is for a different server. Trusting it lets that server read your mail.",
            mismatchWarning(commonNameOnly = false),
        )
    }

    @Test
    fun `the mismatch warning for a common-name-only address does not claim another server`() {
        assertEquals(
            "Only trust this if it is your server's certificate. Trusting it lets whoever holds it read your mail.",
            mismatchWarning(commonNameOnly = true),
        )
    }
}
