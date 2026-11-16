package com.mackseoul.vcardnative.ui.widget

import androidx.compose.ui.graphics.toArgb
import com.mackseoul.vcardnative.domain.profile.Accent
import com.mackseoul.vcardnative.domain.profile.Appearance
import com.mackseoul.vcardnative.domain.profile.Theme
import com.mackseoul.vcardnative.ui.theme.appearanceColorScheme

/** A RemoteViews light/dark color pair; the launcher picks one from the current system night mode. */
data class QuickCardWidgetColorPair(val light: Int, val dark: Int)

object QuickCardWidgetColors {
    /**
     * The saved accent's primary color in the app's light and dark schemes. Derived from [appearanceColorScheme]
     * so the widget cannot drift from the app theme. The widget follows the system mode, not the saved theme.
     */
    fun accent(accent: Accent): QuickCardWidgetColorPair = QuickCardWidgetColorPair(
        light = appearanceColorScheme(Appearance(theme = Theme.light, accent = accent)).primary.toArgb(),
        dark = appearanceColorScheme(Appearance(theme = Theme.dark, accent = accent)).primary.toArgb(),
    )
}
