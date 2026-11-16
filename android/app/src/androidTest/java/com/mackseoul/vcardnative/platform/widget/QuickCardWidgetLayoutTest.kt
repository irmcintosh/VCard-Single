package com.mackseoul.vcardnative.platform.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.test.InstrumentationTestCase
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import com.mackseoul.vcardnative.R
import com.mackseoul.vcardnative.domain.profile.Accent

/** On-device inflation and light/dark contrast checks for the refined Quick Card widget layouts. */
@Suppress("DEPRECATION")
class QuickCardWidgetLayoutTest : InstrumentationTestCase() {
    private val ids = listOf(R.id.quick_card_root, R.id.quick_card_avatar, R.id.quick_card_photo, R.id.quick_card_initials,
        R.id.quick_card_person, R.id.quick_card_name, R.id.quick_card_title, R.id.quick_card_menu)

    fun testEveryLayoutInflatesWithAllIdsAnd48DpMenuTarget() {
        val context = instrumentation.targetContext
        val edge = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48f, context.resources.displayMetrics).toInt()
        for (layout in listOf(R.layout.quick_card_widget_compact, R.layout.quick_card_widget_standard,
            R.layout.quick_card_widget_expanded)) {
            val root = LayoutInflater.from(context).inflate(layout, null)
            ids.forEach { assertNotNull(root.findViewById<View>(it)) }
            val menu = root.findViewById<View>(R.id.quick_card_menu)
            assertEquals(edge, menu.layoutParams.width)
            assertEquals(edge, menu.layoutParams.height)
        }
    }

    fun testPaletteTextAndAccentPairsAreReadableInLightAndDark() {
        val context = instrumentation.targetContext
        val light = night(context, Configuration.UI_MODE_NIGHT_NO)
        val dark = night(context, Configuration.UI_MODE_NIGHT_YES)
        for (accent in Accent.entries) {
            val palette = QuickCardWidgetPalette.resolve(context, accent)
            for (pair in listOf(palette.text, palette.secondary, palette.accent)) {
                assertTrue("$accent light", contrast(pair.light, light.getColor(R.color.quick_card_background)) >= 4.5)
                assertTrue("$accent dark", contrast(pair.dark, dark.getColor(R.color.quick_card_background)) >= 4.5)
            }
            assertTrue("$accent avatar light", contrast(palette.accent.light, light.getColor(R.color.quick_card_avatar)) >= 4.5)
            assertTrue("$accent avatar dark", contrast(palette.accent.dark, dark.getColor(R.color.quick_card_avatar)) >= 4.5)
        }
    }

    private fun night(context: Context, mode: Int): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
        return context.createConfigurationContext(configuration)
    }

    private fun contrast(a: Int, b: Int): Double {
        fun luminance(color: Int): Double {
            fun channel(value: Int): Double = (value / 255.0).let {
                if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(Color.red(color)) + 0.7152 * channel(Color.green(color)) +
                0.0722 * channel(Color.blue(color))
        }
        val (high, low) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (high + 0.05) / (low + 0.05)
    }
}
