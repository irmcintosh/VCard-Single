package com.mackseoul.vcardnative.fg00harness

import org.junit.Assert.assertEquals
import org.junit.Test

class VerificationMessageTest {
    @Test
    fun verificationMessageMatchesRequiredScreenText() {
        assertEquals("FG-00 Toolchain Verification", VERIFICATION_MESSAGE)
    }
}
