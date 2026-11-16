package com.mackseoul.vcardnative.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private fun systemText(size: Int, height: Int, weight: FontWeight) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = height.sp,
    letterSpacing = 0.sp,
)

internal val VCardTypography = Typography(
    headlineLarge = systemText(32, 40, FontWeight.Medium),
    headlineMedium = systemText(24, 32, FontWeight.Medium),
    titleLarge = systemText(20, 28, FontWeight.Medium),
    bodyLarge = systemText(16, 24, FontWeight.Normal),
    bodyMedium = systemText(14, 20, FontWeight.Normal),
    labelLarge = systemText(14, 20, FontWeight.Medium),
    labelSmall = systemText(12, 16, FontWeight.Medium),
)
