package com.mackseoul.vcardnative.domain.sharing

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

class ContactQrGeometryTest {
    private fun qr(version: Int) = ContactQr(version, BooleanArray((17 + 4 * version) * (17 + 4 * version)))

    @Test fun approvedDensityThreeVersion24Preferred29CapAnd30Rejected() {
        val preferred = qr(24).geometry(960, 3.0)
        assertEquals(840, preferred.panelPixels)
        assertEquals(6, preferred.modulePixels)
        val expanded = qr(29).geometry(960, 3.0)
        assertEquals(960, expanded.panelPixels)
        assertEquals(6, expanded.modulePixels)
        try { qr(30).geometry(960, 3.0); fail("Unreadable v30 rendered") }
        catch (error: ContactQrException) { assertEquals(QrFailure.Geometry, error.failure) }
    }

    @Test fun measuredWidthFractionalDensityQuietZoneAndCenteredIntegerModules() {
        for (density in listOf(0.75, 1.0, 1.25, 1.5, 2.625, 3.0, 3.5, 4.0)) {
            for (width in listOf(180, 220, 279, 280, 300, 320, 400)) for (version in 1..40) {
                val qr = qr(version)
                val available = floor(width * density).toInt()
                val maximum = minOf(available, floor(320 * density).toInt())
                val pad = ceil(16 * density).toInt()
                val floor = max(3, ceil(2 * density).toInt())
                val fits = (maximum - 2 * pad) / (qr.side + 8) >= floor
                try {
                    val g = qr.geometry(available, density)
                    assertTrue(fits)
                    assertTrue(g.panelPixels <= available && g.panelPixels <= maximum)
                    assertTrue(g.modulePixels >= floor)
                    val quietStart = g.symbolOrigin - 4 * g.modulePixels
                    val quietEnd = g.symbolOrigin + (qr.side + 4) * g.modulePixels
                    assertTrue(quietStart >= pad)
                    assertTrue(g.panelPixels - quietEnd >= pad)
                    assertTrue(kotlin.math.abs(quietStart - (g.panelPixels - quietEnd)) <= 1)
                } catch (error: ContactQrException) {
                    assertFalse(fits)
                    assertEquals(QrFailure.Geometry, error.failure)
                }
            }
        }
    }

    @Test fun zeroWidthFailsAndMatrixInputIsDefensivelyCopied() {
        try { qr(1).geometry(0, 3.0); fail() }
        catch (error: ContactQrException) { assertEquals(QrFailure.Geometry, error.failure) }
        val bytes = BooleanArray(21 * 21)
        val qr = ContactQr(1, bytes)
        bytes.fill(true)
        assertFalse(qr.isBlack(0, 0))
    }
}
