package com.mackseoul.vcardnative.ui.screens.card

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mackseoul.vcardnative.R

/** Contemporary modular card: accent identity block, contact-action tiles, then the remaining details. */
@Composable
internal fun ModernCard(content: CardContent, modifier: Modifier = Modifier) {
    val profile = content.profile
    val fontScale = LocalDensity.current.fontScale
    val stacked = fontScale >= 1.5f
    CardSurface(modifier, shape = RoundedCornerShape(32.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (content.has(CardSection.CoverImage)) {
                CardCover(content.cover, Modifier.fillMaxWidth().aspectRatio(2.4f).clip(RoundedCornerShape(24.dp)))
            }
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val identity = @Composable {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CardName(profile.fullName, MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
                        if (content.has(CardSection.ProfessionalTitle)) {
                            OptionalText(profile.professionalTitle, MaterialTheme.typography.titleMedium)
                        }
                        if (content.has(CardSection.Organization)) {
                            OptionalText(profile.organization, MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                if (stacked) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CardPortrait(content.portrait, 80.dp, ring = MaterialTheme.colorScheme.primary)
                        identity()
                    }
                } else {
                    Row(
                        Modifier.padding(20.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CardPortrait(content.portrait, 80.dp, ring = MaterialTheme.colorScheme.primary)
                        Box(Modifier.weight(1f)) { identity() }
                    }
                }
            }
            val tiles = profile.contactActions
            if (tiles.isNotEmpty()) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val perRow = modernTilesPerRow(tiles.size, maxWidth.value, fontScale)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        tiles.chunked(perRow).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { action ->
                                    ActionTile(action, perRow == 1, Modifier.weight(1f)) {
                                        content.actions.onContactAction(action)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Column(
                Modifier.padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Each value appears once, below the tiles; phone and email keep their field labels.
                if (content.has(CardSection.Phone) || content.has(CardSection.Email) || content.has(CardSection.Location)) {
                    Column {
                        if (content.has(CardSection.Phone)) {
                            CardDetailRow(R.string.phone, R.drawable.ic_call, requireNotNull(profile.phone).value)
                        }
                        if (content.has(CardSection.Email)) {
                            CardDetailRow(R.string.email, R.drawable.ic_email, requireNotNull(profile.email).value)
                        }
                        if (content.has(CardSection.Location)) {
                            CardDetailRow(R.string.location, R.drawable.ic_location, requireNotNull(profile.location), showLabel = false)
                        }
                    }
                }
                if (content.has(CardSection.Biography)) {
                    OptionalText(profile.biography, MaterialTheme.typography.bodyLarge)
                }
                if (content.has(CardSection.ProfessionalLinks)) {
                    Column {
                        SectionLabel(R.string.professional_links)
                        ProfessionalLinkList(content)
                    }
                }
                if (content.has(CardSection.HostedProfile)) {
                    HostedProfileRow(content)
                }
                if (content.has(CardSection.PublicKey)) {
                    PublicKeySection(content)
                }
            }
        }
    }
}

/**
 * Tiles per row for a balanced grid: all in one row when each tile keeps room for its icon and label, two per row
 * only for an even count, otherwise one full-width tile per row. Never leaves a lone tile beside an empty cell.
 */
internal fun modernTilesPerRow(count: Int, widthDp: Float, fontScale: Float): Int {
    if (count <= 1) return 1
    val gap = 8f
    if (fontScale < 1.3f && (widthDp - gap * (count - 1)) / count >= 76f * fontScale) return count
    if (count % 2 == 0 && fontScale < 1.5f && (widthDp - gap) / 2 >= 120f * fontScale) return 2
    return 1
}

private class TileSpec(@StringRes val label: Int, @DrawableRes val icon: Int)

private fun CardContactKind.tile(): TileSpec = when (this) {
    CardContactKind.Call -> TileSpec(R.string.call, R.drawable.ic_call)
    CardContactKind.Message -> TileSpec(R.string.message, R.drawable.ic_message)
    CardContactKind.Email -> TileSpec(R.string.email, R.drawable.ic_email)
    CardContactKind.Website -> TileSpec(R.string.website, R.drawable.ic_external)
    CardContactKind.Social, CardContactKind.HostedProfile -> TileSpec(R.string.website, R.drawable.ic_external)
}

/** Vertical icon-over-label tile in a shared row; a horizontal full-width tile when stacked one per row. */
@Composable
private fun ActionTile(action: CardContactAction, fullWidth: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val spec = action.kind.tile()
    val label = stringResource(spec.label)
    val description = "$label: ${action.destination}"
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = if (fullWidth) 56.dp else 64.dp).semantics { contentDescription = description },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        if (fullWidth) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(spec.icon), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            }
        } else {
            Column(
                Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                Icon(painterResource(spec.icon), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            }
        }
    }
}