package com.mackseoul.vcardnative.ui.screens.card

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.mackseoul.vcardnative.R

/**
 * Immersive landscape card: a wide cover (or accent gradient when none is saved) with a content panel layered
 * over its lower edge. The panel is opaque, so layering never places text on top of the image.
 */
@Composable
internal fun HorizonCard(content: CardContent, modifier: Modifier = Modifier) {
    val profile = content.profile
    val fontScale = LocalDensity.current.fontScale
    val largeText = fontScale >= 1.3f
    CardSurface(modifier, shape = RoundedCornerShape(28.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val hasCover = content.has(CardSection.CoverImage)
            val coverHeight = maxWidth / if (hasCover) 1.7f else 2.6f
            val overlap = 36.dp
            val panelWidth = (maxWidth - 40.dp).value
            if (hasCover) {
                CardCover(content.cover, Modifier.fillMaxWidth().height(coverHeight))
            } else {
                Box(
                    Modifier.fillMaxWidth().height(coverHeight).background(
                        Brush.linearGradient(
                            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer),
                        ),
                    ),
                )
            }
            Column {
                Spacer(Modifier.height(coverHeight - overlap))
                Surface(
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        HorizonIdentity(content, largeText)
                        if (content.has(CardSection.Biography)) {
                            OptionalText(profile.biography, MaterialTheme.typography.bodyLarge)
                        }
                        HorizonDetails(content, panelWidth, fontScale)
                        if (content.has(CardSection.Website)) {
                            WebsiteLink(content)
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
    }
}

@Composable
private fun HorizonIdentity(content: CardContent, stacked: Boolean) {
    val profile = content.profile
    val text = @Composable {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CardName(profile.fullName, MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold))
            if (content.has(CardSection.ProfessionalTitle)) {
                OptionalText(profile.professionalTitle, MaterialTheme.typography.bodyLarge)
            }
            if (content.has(CardSection.Organization)) {
                OptionalText(profile.organization, MaterialTheme.typography.bodyLarge, MaterialTheme.colorScheme.primary)
            }
        }
    }
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardPortrait(content.portrait, 72.dp, ring = MaterialTheme.colorScheme.surface)
            text()
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CardPortrait(content.portrait, 72.dp, ring = MaterialTheme.colorScheme.surface)
            Box(Modifier.weight(1f)) { text() }
        }
    }
}

private class HorizonDetail(val label: Int, val icon: Int, val value: String)

/** Narrowest content-panel width (dp) that may use two columns. Phone-sized cards, including large phones, stay below it. */
internal const val HORIZON_TWO_COLUMN_MIN_PANEL_DP = 480f

/**
 * Two columns only on genuinely wide cards and only when every value's measured single-line width fits its column
 * (column minus the 20 dp icon and 12 dp gap of CardDetailRow). Otherwise one full-width column, so values such as
 * email addresses are not split by a narrow column. Nothing is truncated either way; text still wraps naturally if a
 * value is wider than the full card.
 */
internal fun horizonUsesTwoColumns(panelWidthDp: Float, fontScale: Float, valueWidthsDp: List<Float>): Boolean {
    if (valueWidthsDp.size < 2 || fontScale >= 1.3f || panelWidthDp < HORIZON_TWO_COLUMN_MIN_PANEL_DP) return false
    val textWidth = (panelWidthDp - 12f) / 2f - 32f
    return valueWidthsDp.max() <= textWidth
}

@Composable
private fun HorizonDetails(content: CardContent, panelWidthDp: Float, fontScale: Float) {
    val profile = content.profile
    val details = buildList {
        if (content.has(CardSection.Location)) add(HorizonDetail(R.string.location, R.drawable.ic_location, requireNotNull(profile.location)))
        if (content.has(CardSection.Phone)) add(HorizonDetail(R.string.phone, R.drawable.ic_call, requireNotNull(profile.phone).value))
        if (content.has(CardSection.Email)) add(HorizonDetail(R.string.email, R.drawable.ic_email, requireNotNull(profile.email).value))
    }
    if (details.isEmpty()) return
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodyLarge
    val density = LocalDensity.current
    val twoColumns = panelWidthDp >= HORIZON_TWO_COLUMN_MIN_PANEL_DP && fontScale < 1.3f && details.size >= 2 &&
        horizonUsesTwoColumns(
            panelWidthDp, fontScale,
            details.map { detail ->
                with(density) { measurer.measure(detail.value, style, softWrap = false, maxLines = 1).size.width.toDp().value }
            },
        )
    val perRow = if (twoColumns) 2 else 1
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        details.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach {
                    Box(Modifier.weight(1f)) {
                        CardDetailRow(it.label, it.icon, it.value, MaterialTheme.colorScheme.primary)
                    }
                }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
