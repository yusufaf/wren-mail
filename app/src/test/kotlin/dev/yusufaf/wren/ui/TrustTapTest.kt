package dev.yusufaf.wren.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TrustTapTest {
    @Test
    fun `without a hostname mismatch the first tap trusts`() {
        assertEquals(TrustTap.TRUST, trustTap(mismatch = false, confirmingSince = null, now = 0))
    }

    @Test
    fun `with a hostname mismatch the first tap asks to confirm`() {
        assertEquals(TrustTap.CONFIRM, trustTap(mismatch = true, confirmingSince = null, now = 0))
    }

    @Test
    fun `a confirming tap at the same instant is ignored`() {
        assertEquals(TrustTap.IGNORE, trustTap(mismatch = true, confirmingSince = 1_000, now = 1_000))
    }

    @Test
    fun `a confirming tap just inside the delay is ignored`() {
        val now = 1_000 + TRUST_CONFIRM_DELAY_MILLIS - 1
        assertEquals(TrustTap.IGNORE, trustTap(mismatch = true, confirmingSince = 1_000, now = now))
    }

    @Test
    fun `a confirming tap once the delay has passed trusts`() {
        val now = 1_000 + TRUST_CONFIRM_DELAY_MILLIS
        assertEquals(TrustTap.TRUST, trustTap(mismatch = true, confirmingSince = 1_000, now = now))
    }
}
