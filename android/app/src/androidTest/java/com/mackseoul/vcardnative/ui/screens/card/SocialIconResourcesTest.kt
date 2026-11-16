package com.mackseoul.vcardnative.ui.screens.card

import android.test.InstrumentationTestCase
import android.util.TypedValue

/** Every Social Icon Grid symbol drawable inflates on-device as a 24 dp local vector (no network, no bitmap). */
@Suppress("DEPRECATION")
class SocialIconResourcesTest : InstrumentationTestCase() {
    fun testEverySocialIconDrawableInflatesAt24Dp() {
        val context = instrumentation.targetContext
        val expected = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, context.resources.displayMetrics)
        SocialIcon.entries.mapNotNull { it.drawable }.distinct().forEach { id ->
            val name = context.resources.getResourceEntryName(id)
            val drawable = requireNotNull(context.getDrawable(id)) { name }
            assertTrue(name, kotlin.math.abs(drawable.intrinsicWidth - expected) <= 1f)
            assertTrue(name, kotlin.math.abs(drawable.intrinsicHeight - expected) <= 1f)
        }
    }

    fun testEveryPlatformHasExactlyOneSymbol() {
        assertEquals(11, SocialIcon.entries.size)
        SocialIcon.entries.forEach { assertTrue(it.name, (it.drawable == null) != (it.glyph == null)) }
    }
}
