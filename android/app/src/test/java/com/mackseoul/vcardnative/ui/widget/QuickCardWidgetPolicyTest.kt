package com.mackseoul.vcardnative.ui.widget

import org.junit.Assert.*
import org.junit.Test

class QuickCardWidgetPolicyTest {
    @Test fun expiredRequestsStopBeforePublishingRemainingWidgets() {
        val sequence = QuickCardWidgetUpdateSequence()
        var active = true
        val updated = mutableListOf<Int>()
        sequence.publishWidgetsIfCurrent(sequence.next(), intArrayOf(10, 20), isActive = { active }) {
            updated += it
            active = false
        }
        assertEquals(listOf(10), updated)
    }

    @Test fun latestRefreshUpdatesEveryActiveWidgetOnce() {
        val sequence = QuickCardWidgetUpdateSequence()
        val ticket = sequence.next()
        val updated = mutableListOf<Int>()
        assertTrue(sequence.publishWidgetsIfCurrent(ticket, intArrayOf(10, 20, 10, 30),
            isActive = { it != 30 }, publish = { updated += it }))
        assertEquals(listOf(10, 20), updated)
        sequence.next()
        assertFalse(sequence.publishWidgetsIfCurrent(ticket, intArrayOf(10, 20),
            isActive = { true }, publish = { updated += it }))
        assertEquals(listOf(10, 20), updated)
    }

    @Test fun noInstalledWidgetsDoesNotPublishOrFail() {
        val sequence = QuickCardWidgetUpdateSequence()
        val ticket = sequence.next()
        assertTrue(sequence.publishWidgetsIfCurrent(ticket, intArrayOf(),
            isActive = { fail("No widgets should be checked"); true },
            publish = { fail("No widgets should be updated") }))
    }

    @Test fun measuredDpBreakpointsSelectCompactStandardAndExpanded() {
        assertEquals(QuickCardWidgetSize.Compact, QuickCardWidgetLayoutPolicy.select(180f, 64f))
        assertEquals(QuickCardWidgetSize.Standard, QuickCardWidgetLayoutPolicy.select(180f, 180f))
        assertEquals(QuickCardWidgetSize.Standard, QuickCardWidgetLayoutPolicy.select(180f, 120f))
        assertEquals(QuickCardWidgetSize.Compact, QuickCardWidgetLayoutPolicy.select(180f, 119f))
        assertEquals(QuickCardWidgetSize.Expanded, QuickCardWidgetLayoutPolicy.select(300f, 180f))
        assertEquals(QuickCardWidgetSize.Expanded, QuickCardWidgetLayoutPolicy.select(260f, 120f))
        assertEquals(QuickCardWidgetSize.Compact, QuickCardWidgetLayoutPolicy.select(300f, 80f))
        assertEquals(QuickCardWidgetSize.VeryNarrow, QuickCardWidgetLayoutPolicy.select(90f, 40f))
        assertEquals(QuickCardWidgetSize.VeryNarrow, QuickCardWidgetLayoutPolicy.select(120f, 48f))
        assertEquals(QuickCardWidgetSize.VeryNarrow, QuickCardWidgetLayoutPolicy.select(179f, 90f))
        assertEquals(QuickCardWidgetSize.VeryNarrow, QuickCardWidgetLayoutPolicy.select(140f, 151f))
        assertEquals(QuickCardWidgetSize.Standard, QuickCardWidgetLayoutPolicy.select(140f, 152f))
    }

    @Test fun compactTitleShowsInitialsBelowTheFullTitleWidthAndFullTitleAboveIt() {
        fun initials(width: Float, height: Float) = QuickCardWidgetLayoutPolicy.entryFor(width, height).showsTitleInitials
        // 2x1-like single row: observed Compact-sized width, short height.
        assertTrue(initials(120f, 60f))
        assertTrue(initials(185f, 60f))
        assertTrue(initials(239f, 100f))
        // 3x1 and 4x1-like rows keep the full title.
        assertFalse(initials(240f, 60f))
        assertFalse(initials(300f, 60f))
        assertFalse(initials(400f, 110f))
        // Taller layouts (2x2, 3x2, 4x2 and up) never use initials.
        for ((width, height) in listOf(130f to 160f, 185f to 120f, 200f to 200f, 300f to 130f, 300f to 300f)) {
            assertFalse("$width x $height", initials(width, height))
        }
        assertEquals(QuickCardWidgetSize.Compact, QuickCardWidgetLayoutPolicy.select(185f, 60f))
        assertEquals(QuickCardWidgetSize.Compact, QuickCardWidgetLayoutPolicy.select(300f, 60f))
        // Name, avatar and one-line limits stay those of the Compact class in both entries.
        for (width in listOf(185f, 300f)) {
            assertEquals(QuickCardWidgetTextPolicy.limits(QuickCardWidgetSize.Compact, 1f),
                QuickCardWidgetTextPolicy.limits(QuickCardWidgetLayoutPolicy.entryFor(width, 60f), 1f))
        }
    }

    @Test fun everyWidgetSizeHasOneLargestFittingEntryAndNarrowStandardAlwaysClearsTheMenu() {
        val keys = QuickCardWidgetLayoutPolicy.breakpoints.map { it.widthDp to it.heightDp }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(QuickCardWidgetSize.entries.toSet(), QuickCardWidgetLayoutPolicy.breakpoints.map { it.size }.toSet())
        for (width in 100..400 step 4) for (height in 40..420 step 4) {
            val fitting = QuickCardWidgetLayoutPolicy.breakpoints.filter {
                width >= it.widthDp && height >= it.heightDp
            }
            if (fitting.isEmpty()) continue
            val largest = fitting.maxOf { it.widthDp * it.heightDp }
            assertEquals("$width x $height", 1, fitting.count { it.widthDp * it.heightDp == largest })
            val chosen = QuickCardWidgetLayoutPolicy.entryFor(width.toFloat(), height.toFloat())
            if (chosen.size == QuickCardWidgetSize.Standard) {
                assertEquals("$width x $height", width < QuickCardWidgetLayoutPolicy.STANDARD_MENU_CLEAR_WIDTH_DP,
                    chosen.reservesMenuRow)
            } else assertFalse(chosen.reservesMenuRow)
        }
        // 56 dp avatar centered: half width 28 dp, plus the 48 dp menu column on each side of the midpoint.
        assertEquals(2f * (28f + 48f), QuickCardWidgetLayoutPolicy.STANDARD_MENU_CLEAR_WIDTH_DP)
    }

    @Test fun titleLinesFollowEntryHeightAndFontScale() {
        fun limits(width: Float, height: Float, scale: Float) =
            QuickCardWidgetTextPolicy.limits(QuickCardWidgetLayoutPolicy.entryFor(width, height), scale)
        assertEquals(QuickCardWidgetTextLimits(1, 1, true), limits(152f, 120f, 1f))
        assertEquals(QuickCardWidgetTextLimits(1, 2, true), limits(152f, 136f, 1f))
        assertEquals(QuickCardWidgetTextLimits(2, 2, true), limits(152f, 160f, 1f))
        assertEquals(QuickCardWidgetTextLimits(1, 1, true), limits(120f, 152f, 1f))
        assertEquals(QuickCardWidgetTextLimits(2, 2, true), limits(120f, 184f, 1f))
        assertEquals(QuickCardWidgetTextLimits(2, 2, true), limits(260f, 120f, 1f))
        assertEquals(QuickCardWidgetTextLimits(2, 4, true), limits(260f, 160f, 1f))
        assertEquals(QuickCardWidgetTextLimits(2, 4, true), limits(400f, 500f, 1f))
        // Between 1.0 and 1.3 the old fixed 2 + 2 lines overflowed a 120 dp Expanded widget.
        assertEquals(QuickCardWidgetTextLimits(2, 1, true), limits(260f, 120f, 1.15f))
        assertEquals(QuickCardWidgetTextLimits(1, 2, true), limits(260f, 120f, 1.3f))
        assertEquals(QuickCardWidgetTextLimits(1, 1, true), limits(260f, 120f, 2f))
        assertEquals(QuickCardWidgetTextLimits(1, 1, true), limits(152f, 120f, 1.3f))
        for (breakpoint in QuickCardWidgetLayoutPolicy.breakpoints) for (scale in listOf(0.85f, 1f, 1.15f, 1.3f, 1.5f, 2f)) {
            val result = QuickCardWidgetTextPolicy.limits(breakpoint, scale)
            assertTrue(result.nameLines >= 1 && result.titleLines >= 1)
            assertTrue(result.titleLines <= 4)
        }
    }

    @Test fun compactAndVeryNarrowKeepTheApprovedFontScaleRules() {
        for (breakpoint in QuickCardWidgetLayoutPolicy.breakpoints.filter {
            it.size == QuickCardWidgetSize.VeryNarrow || it.size == QuickCardWidgetSize.Compact
        }) for (scale in listOf(1f, 1.3f, 1.5f, 2f)) {
            assertEquals(QuickCardWidgetTextPolicy.limits(breakpoint.size, scale),
                QuickCardWidgetTextPolicy.limits(breakpoint, scale))
        }
    }

    @Test fun newerRequestsPreventOlderResultsFromPublishing() {
        val sequence = QuickCardWidgetUpdateSequence()
        val old = sequence.next()
        val latest = sequence.next()
        var published = 0
        assertFalse(sequence.publishIfCurrent(old) { published++ })
        assertTrue(sequence.publishIfCurrent(latest) { published++ })
        assertEquals(1, published)
        sequence.next()
        assertFalse(sequence.publishIfCurrent(latest) { published++ })
        assertEquals(1, published)
    }

    @Test fun photoSamplingBoundsBothDimensionsWithoutFullResolutionDecoding() {
        assertEquals(1, QuickCardWidgetPhotoPolicy.sampleSize(64, 64))
        assertEquals(4, QuickCardWidgetPhotoPolicy.sampleSize(1024, 1024))
        assertEquals(8, QuickCardWidgetPhotoPolicy.sampleSize(1024, 512, 128))
        assertEquals(4, QuickCardWidgetPhotoPolicy.sampleSize(257, 600))
        assertThrows(IllegalArgumentException::class.java) { QuickCardWidgetPhotoPolicy.sampleSize(0, 20) }
        assertThrows(IllegalArgumentException::class.java) { QuickCardWidgetPhotoPolicy.sampleSize(2048, 20) }
        assertThrows(IllegalArgumentException::class.java) { QuickCardWidgetPhotoPolicy.sampleSize(20, 20, 257) }
    }
}