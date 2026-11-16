package com.mackseoul.vcardnative.ui.theme

import androidx.compose.ui.graphics.toArgb
import com.mackseoul.vcardnative.domain.profile.Accent
import com.mackseoul.vcardnative.domain.profile.Appearance
import com.mackseoul.vcardnative.domain.profile.Theme
import org.junit.Assert.assertEquals
import org.junit.Test

class AppearanceColorsTest {
    @Test
    fun approvedPrimaryPairsAreAppliedTogetherForEveryThemeAndAccent() {
        val expected = listOf(
            Appearance(Theme.dark, Accent.sapphire) to listOf(0xFFA9C7FF, 0xFF102D55, 0xFF263E60, 0xFFD8E6FF),
            Appearance(Theme.light, Accent.sapphire) to listOf(0xFF285C9E, 0xFFFFFFFF, 0xFFD8E6FF, 0xFF102D55),
            Appearance(Theme.dark, Accent.jade) to listOf(0xFF9DD9BD, 0xFF0D3928, 0xFF214B38, 0xFFC3F2DC),
            Appearance(Theme.light, Accent.jade) to listOf(0xFF23684C, 0xFFFFFFFF, 0xFFD0EDDD, 0xFF103D29),
            Appearance(Theme.dark, Accent.amethyst) to listOf(0xFFD2BCFF, 0xFF34205B, 0xFF4C3673, 0xFFEBDEFF),
            Appearance(Theme.light, Accent.amethyst) to listOf(0xFF67449A, 0xFFFFFFFF, 0xFFEADDF9, 0xFF352050),
        )
        expected.forEach { (appearance, argb) ->
            val colors = appearanceColorScheme(appearance)
            assertEquals(
                argb.map { it.toInt() },
                listOf(colors.primary, colors.onPrimary, colors.primaryContainer, colors.onPrimaryContainer).map { it.toArgb() },
            )
        }
    }

    @Test
    fun accentNeverChangesPrivacyNoticeOrNeutralColors() {
        Theme.entries.forEach { theme ->
            val sapphire = appearanceColorScheme(Appearance(theme, Accent.sapphire))
            Accent.entries.forEach { accent ->
                val colors = appearanceColorScheme(Appearance(theme, accent))
                assertEquals(sapphire.background, colors.background)
                assertEquals(sapphire.surface, colors.surface)
                assertEquals(sapphire.onSurface, colors.onSurface)
                assertEquals(sapphire.error, colors.error)
            }
        }
    }
}
