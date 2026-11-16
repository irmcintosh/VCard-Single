package com.mackseoul.vcardnative

import org.junit.Assert.assertEquals
import org.junit.Test

class ApplicationIdentityTest {
    @Test
    fun applicationIdMatchesApprovedIdentifier() {
        assertEquals("com.mackseoul.vcardnative", MainActivity::class.java.`package`?.name)
    }
}
