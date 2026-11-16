package com.mackseoul.vcardnative.ui.screens.card

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mackseoul.vcardnative.R
import com.mackseoul.vcardnative.ui.profile.SocialPlatformOption
import com.mackseoul.vcardnative.ui.profile.SocialPlatformSelection

/**
 * Owner-approved custom VCard Native symbols (APPROVED_ICON_DESIGN.md v1.1.0). Each symbol is either an original
 * vector drawable or a plain typographic glyph; none is an official platform logo. X and Mastodon intentionally
 * share the single speech bubble, and Other reuses the existing generic `ic_external` symbol.
 */
internal enum class SocialIcon(@DrawableRes val drawable: Int?, val glyph: String?, val solidGlyph: Boolean = false) {
    LinkedIn(null, "in", solidGlyph = true),
    GitHub(R.drawable.ic_social_code, null),
    Instagram(R.drawable.ic_social_camera, null),
    Facebook(null, "f", solidGlyph = true),
    YouTube(R.drawable.ic_social_play, null),
    TikTok(R.drawable.ic_social_note, null),
    X(R.drawable.ic_social_bubble, null),
    Threads(null, "@"),
    Bluesky(R.drawable.ic_social_bubbles, null),
    Mastodon(R.drawable.ic_social_bubble, null),
    Other(R.drawable.ic_external, null),
}

/** Uses the editor's saved-platform normalization, so aliases such as `twitter` and unknown values match it exactly. */
internal fun socialIconFor(platform: String): SocialIcon = when (SocialPlatformSelection.fromSaved(platform).option) {
    SocialPlatformOption.LinkedIn -> SocialIcon.LinkedIn
    SocialPlatformOption.GitHub -> SocialIcon.GitHub
    SocialPlatformOption.Facebook -> SocialIcon.Facebook
    SocialPlatformOption.Instagram -> SocialIcon.Instagram
    SocialPlatformOption.XTwitter -> SocialIcon.X
    SocialPlatformOption.YouTube -> SocialIcon.YouTube
    SocialPlatformOption.TikTok -> SocialIcon.TikTok
    SocialPlatformOption.Threads -> SocialIcon.Threads
    SocialPlatformOption.Bluesky -> SocialIcon.Bluesky
    SocialPlatformOption.Mastodon -> SocialIcon.Mastodon
    SocialPlatformOption.Other -> SocialIcon.Other
}

internal const val SOCIAL_GRID_MAX_COLUMNS = 4
internal const val SOCIAL_GRID_GAP_DP = 8f
internal const val SOCIAL_GRID_MIN_TARGET_DP = 48f
private const val SOCIAL_GRID_BASE_CELL_DP = 72f

/** Minimum cell width; grows with font scale so two-line labels stay readable, never below the 48 dp target. */
internal fun socialGridMinCellDp(fontScale: Float): Float =
    maxOf(SOCIAL_GRID_MIN_TARGET_DP, SOCIAL_GRID_BASE_CELL_DP * fontScale.coerceAtLeast(1f))

/** 1–4 columns from the measured container width; never more columns than links, never zero. */
internal fun socialIconColumns(count: Int, widthDp: Float, fontScale: Float): Int {
    if (count <= 0) return 1
    val minCell = socialGridMinCellDp(fontScale)
    val fit = if (widthDp.isFinite()) ((widthDp + SOCIAL_GRID_GAP_DP) / (minCell + SOCIAL_GRID_GAP_DP)).toInt() else 1
    return fit.coerceIn(1, SOCIAL_GRID_MAX_COLUMNS).coerceAtMost(count)
}

/** Row-major rows; flattening returns the original list, so saved order and duplicates are preserved. */
internal fun <T> socialGridRows(items: List<T>, columns: Int): List<List<T>> = items.chunked(columns.coerceAtLeast(1))

/**
 * Responsive labeled icon grid for professional links: icon above the saved label (two lines, ellipsis), full
 * label and URL announced to accessibility services, at least 48 dp targets. [divided] separates rows like the
 * former list dividers; [compact] tightens vertical padding without shrinking targets. [onOpen] receives the
 * tapped link; the caller keeps the existing Social action and URL handling.
 */
@Composable
internal fun SocialIconGrid(
    links: List<CardProfessionalLink>,
    onOpen: (CardProfessionalLink) -> Unit,
    divided: Boolean = false,
    compact: Boolean = false,
) {
    if (links.isEmpty()) return
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = socialIconColumns(links.size, maxWidth.value, fontScale)
        Column(verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
            socialGridRows(links, columns).forEachIndexed { rowIndex, row ->
                if (divided && rowIndex > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SOCIAL_GRID_GAP_DP.dp)) {
                    row.forEach { link ->
                        SocialIconCell(link, onOpen, compact, Modifier.weight(1f))
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun SocialIconCell(
    link: CardProfessionalLink,
    onOpen: (CardProfessionalLink) -> Unit,
    compact: Boolean,
    modifier: Modifier,
) {
    val description = stringResource(R.string.social_open_description, link.label, link.url.value)
    Surface(
        onClick = { onOpen(link) },
        modifier = modifier.heightIn(min = 48.dp).semantics {
            contentDescription = description
            role = Role.Button
        },
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 4.dp, vertical = if (compact) 6.dp else 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SocialSymbol(socialIconFor(link.platform))
            Text(
                link.label,
                modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Decorative symbol in a fixed 40 dp accent-tinted disc; typographic glyphs use dp-based size so they match vectors. */
@Composable
private fun SocialSymbol(icon: SocialIcon) {
    val tint = MaterialTheme.colorScheme.primary
    Box(
        Modifier.size(40.dp).background(tint.copy(alpha = 0.12f), CircleShape).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        val drawable = icon.drawable
        if (drawable != null) {
            Icon(painterResource(drawable), contentDescription = null, modifier = Modifier.size(22.dp), tint = tint)
        } else {
            val size = with(LocalDensity.current) { 20.dp.toSp() }
            Text(
                requireNotNull(icon.glyph),
                color = tint,
                fontSize = size,
                lineHeight = size,
                fontWeight = if (icon.solidGlyph) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
