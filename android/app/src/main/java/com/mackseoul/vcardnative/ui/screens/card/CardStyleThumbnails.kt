package com.mackseoul.vcardnative.ui.screens.card

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mackseoul.vcardnative.domain.profile.CardStyle

/**
 * Lightweight schematic of a style for the Appearance gallery. It reads no profile data, decodes no images and
 * has no actions; the caller supplies selection semantics.
 */
@Composable
internal fun CardStyleThumbnail(style: CardStyle, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.clearAndSetSemantics {},
        shape = RoundedCornerShape(12.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Box(Modifier.fillMaxSize()) {
            when (style) {
                CardStyle.Classic -> Column {
                    Box(Modifier.fillMaxWidth().height(34.dp).background(colors.surfaceVariant))
                    Box(Modifier.offset(x = 10.dp, y = (-14).dp).size(28.dp).clip(CircleShape).background(colors.surface).padding(2.dp).clip(CircleShape).background(colors.outline))
                    Lines(Modifier.padding(horizontal = 10.dp).offset(y = (-6).dp), listOf(0.8f, 0.55f, 0.65f, 0.5f))
                }
                CardStyle.Minimal -> Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Spacer(Modifier.height(18.dp))
                    Bar(0.75f, 8.dp, colors.onSurface)
                    Bar(0.25f, 2.dp, colors.primary)
                    Spacer(Modifier.height(6.dp))
                    Bar(0.5f, 4.dp, colors.onSurfaceVariant.copy(alpha = 0.5f))
                }
                CardStyle.Professional -> Column {
                    Box(Modifier.fillMaxWidth().height(4.dp).background(colors.primary))
                    Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Dot(20.dp, RoundedCornerShape(4.dp), colors.outline)
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Bar(0.9f, 5.dp, colors.onSurface)
                            Bar(0.6f, 3.dp, colors.primary)
                        }
                    }
                    Column(
                        Modifier.padding(horizontal = 8.dp).fillMaxWidth().clip(RoundedCornerShape(4.dp))
                            .background(colors.surfaceVariant.copy(alpha = 0.6f)).padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Bar(0.4f, 3.dp, colors.primary)
                        Bar(0.8f, 3.dp, colors.onSurfaceVariant.copy(alpha = 0.6f))
                        Bar(0.7f, 3.dp, colors.onSurfaceVariant.copy(alpha = 0.6f))
                    }
                }
                CardStyle.Modern -> Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.primaryContainer).padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Dot(18.dp, CircleShape, colors.primary)
                        Bar(0.8f, 6.dp, colors.onPrimaryContainer)
                    }
                    repeat(2) {
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            repeat(2) {
                                Box(Modifier.weight(1f).height(20.dp).clip(RoundedCornerShape(6.dp)).background(colors.surfaceVariant)) {
                                    Box(Modifier.align(Alignment.Center).size(6.dp).clip(CircleShape).background(colors.primary))
                                }
                            }
                        }
                    }
                }
                CardStyle.Executive -> Column(
                    Modifier.fillMaxWidth().padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Dot(22.dp, CircleShape, colors.outline)
                    Bar(0.7f, 5.dp, colors.onSurface)
                    Bar(0.2f, 1.dp, colors.primary)
                    Bar(0.5f, 3.dp, colors.onSurfaceVariant.copy(alpha = 0.6f))
                    Spacer(Modifier.height(2.dp))
                    Bar(1f, 1.dp, colors.outlineVariant)
                    Bar(0.6f, 3.dp, colors.onSurfaceVariant.copy(alpha = 0.5f))
                }
                CardStyle.Editorial -> Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Bar(0.4f, 3.dp, colors.primary)
                    Bar(0.95f, 11.dp, colors.onSurface)
                    Bar(0.7f, 11.dp, colors.onSurface)
                    Bar(1f, 2.dp, colors.primary)
                    Row(Modifier.height(22.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.width(2.dp).fillMaxHeight().background(colors.primary))
                        Lines(Modifier, listOf(0.9f, 0.8f, 0.6f), 3.dp)
                    }
                }
                CardStyle.Horizon -> Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier.fillMaxWidth().height(62.dp)
                            .background(Brush.linearGradient(listOf(colors.primary, colors.primaryContainer))),
                    )
                    Column(
                        Modifier.padding(top = 48.dp).fillMaxSize()
                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)).background(colors.surface).padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Dot(18.dp, CircleShape, colors.outline)
                            Bar(0.8f, 5.dp, colors.onSurface)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box(Modifier.weight(1f)) { Bar(1f, 3.dp, colors.onSurfaceVariant.copy(alpha = 0.6f)) }
                            Box(Modifier.weight(1f)) { Bar(1f, 3.dp, colors.onSurfaceVariant.copy(alpha = 0.6f)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Bar(fraction: Float, height: Dp, color: Color) {
    Box(Modifier.fillMaxWidth(fraction).height(height).clip(RoundedCornerShape(2.dp)).background(color))
}

@Composable
private fun Dot(size: Dp, shape: Shape, color: Color) {
    Box(Modifier.size(size).clip(shape).background(color))
}

@Composable
private fun Lines(modifier: Modifier, fractions: List<Float>, height: Dp = 4.dp) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        fractions.forEach { Bar(it, height, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)) }
    }
}

