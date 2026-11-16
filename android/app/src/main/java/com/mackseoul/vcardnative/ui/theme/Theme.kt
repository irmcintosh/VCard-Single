package com.mackseoul.vcardnative.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.mackseoul.vcardnative.domain.profile.Accent
import com.mackseoul.vcardnative.domain.profile.Appearance
import com.mackseoul.vcardnative.domain.profile.Theme

private val DarkColorScheme = darkColorScheme(
    primary = VCardDarkColors.Primary,
    onPrimary = VCardDarkColors.OnPrimary,
    primaryContainer = VCardDarkColors.PrimaryContainer,
    onPrimaryContainer = VCardDarkColors.OnPrimaryContainer,
    secondary = VCardDarkColors.Secondary,
    onSecondary = VCardDarkColors.OnSecondary,
    background = VCardDarkColors.Background,
    onBackground = VCardDarkColors.OnSurface,
    surface = VCardDarkColors.Surface,
    onSurface = VCardDarkColors.OnSurface,
    surfaceVariant = VCardDarkColors.SurfaceRaised,
    onSurfaceVariant = VCardDarkColors.OnSurfaceVariant,
    outline = VCardDarkColors.Outline,
    outlineVariant = VCardDarkColors.OutlineSubtle,
    error = VCardDarkColors.Error,
    errorContainer = VCardDarkColors.ErrorContainer,
    onErrorContainer = VCardDarkColors.OnErrorContainer,
)

private val LightColorScheme = lightColorScheme(
    primary = VCardLightColors.Primary,
    onPrimary = VCardLightColors.OnPrimary,
    primaryContainer = VCardLightColors.PrimaryContainer,
    onPrimaryContainer = VCardLightColors.OnPrimaryContainer,
    secondary = VCardLightColors.Secondary,
    onSecondary = VCardLightColors.OnSecondary,
    background = VCardLightColors.Background,
    onBackground = VCardLightColors.OnSurface,
    surface = VCardLightColors.Surface,
    onSurface = VCardLightColors.OnSurface,
    surfaceVariant = VCardLightColors.SurfaceRaised,
    onSurfaceVariant = VCardLightColors.OnSurfaceVariant,
    outline = VCardLightColors.Outline,
    outlineVariant = VCardLightColors.OutlineSubtle,
    error = VCardLightColors.Error,
    errorContainer = VCardLightColors.ErrorContainer,
    onErrorContainer = VCardLightColors.OnErrorContainer,
)

internal fun appearanceColorScheme(appearance: Appearance): androidx.compose.material3.ColorScheme {
    val dark = appearance.theme == Theme.dark
    val base = if (dark) DarkColorScheme else LightColorScheme
    val primary = when (appearance.accent) {
        Accent.sapphire -> return base
        Accent.jade -> if (dark) {
            listOf(0xFF9DD9BD, 0xFF0D3928, 0xFF214B38, 0xFFC3F2DC)
        } else listOf(0xFF23684C, 0xFFFFFFFF, 0xFFD0EDDD, 0xFF103D29)
        Accent.amethyst -> if (dark) {
            listOf(0xFFD2BCFF, 0xFF34205B, 0xFF4C3673, 0xFFEBDEFF)
        } else listOf(0xFF67449A, 0xFFFFFFFF, 0xFFEADDF9, 0xFF352050)
    }
    return base.copy(
        primary = Color(primary[0]),
        onPrimary = Color(primary[1]),
        primaryContainer = Color(primary[2]),
        onPrimaryContainer = Color(primary[3]),
    )
}

// P0.3 excludes dynamic color and an automatic theme mode.
@Composable
fun VCardTheme(
    appearance: Appearance = Appearance(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = appearanceColorScheme(appearance),
        typography = VCardTypography,
        content = content,
    )
}
