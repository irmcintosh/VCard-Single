package com.mackseoul.vcardnative.ui.screens.card

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mackseoul.vcardnative.R
import java.util.Locale

/** Magazine-inspired card: kicker, oversized serif name, rule, byline, pull-quote biography and an index of details. */
@Composable
internal fun EditorialCard(content: CardContent, modifier: Modifier = Modifier) {
    val profile = content.profile
    val accent = MaterialTheme.colorScheme.primary
    CardSurface(modifier, shape = RoundedCornerShape(4.dp)) {
        Column {
            if (content.has(CardSection.CoverImage)) {
                CardCover(content.cover, Modifier.fillMaxWidth().aspectRatio(1.9f))
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val fontScale = LocalDensity.current.fontScale
                val textWidth = (maxWidth - 48.dp).value
                val nameSize = editorialNameSizeSp(profile.fullName, textWidth, fontScale)
                val stackByline = editorialStacksByline(textWidth, fontScale)
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (content.has(CardSection.Organization)) {
                        Text(
                            requireNotNull(profile.organization).uppercase(Locale.getDefault()),
                            style = MaterialTheme.typography.labelLarge.copy(
                                letterSpacing = if (fontScale >= 1.3f) 1.sp else 2.sp, fontWeight = FontWeight.Bold,
                            ),
                            color = accent,
                        )
                    }
                    CardName(
                        profile.fullName,
                        MaterialTheme.typography.displaySmall.copy(
                            fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
                            fontSize = nameSize.sp, lineHeight = (nameSize * 1.15f).sp, lineBreak = LineBreak.Heading,
                        ),
                    )
                    Box(Modifier.fillMaxWidth().height(2.dp).background(accent))
                    if (content.has(CardSection.ProfilePhoto) || content.has(CardSection.ProfessionalTitle)) {
                        val title = @Composable { titleModifier: Modifier ->
                            Text(
                                requireNotNull(profile.professionalTitle),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, lineBreak = LineBreak.Paragraph,
                                ),
                                modifier = titleModifier,
                            )
                        }
                        if (stackByline) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (content.has(CardSection.ProfilePhoto)) {
                                    CardPortrait(content.portrait, 64.dp, RoundedCornerShape(4.dp))
                                }
                                if (content.has(CardSection.ProfessionalTitle)) title(Modifier)
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (content.has(CardSection.ProfilePhoto)) {
                                    CardPortrait(content.portrait, 64.dp, RoundedCornerShape(4.dp))
                                }
                                if (content.has(CardSection.ProfessionalTitle)) title(Modifier.weight(1f))
                            }
                        }
                    }
                    if (content.has(CardSection.Biography)) {
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
                            Text(
                                requireNotNull(profile.biography),
                                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    if (content.hasContact || content.has(CardSection.Location)) {
                        EditorialSection(R.string.contact_information) {
                            if (content.has(CardSection.Location)) {
                                CardDetailRow(R.string.location, R.drawable.ic_location, requireNotNull(profile.location), accent)
                            }
                            CardContactDetails(content, accent)
                        }
                    }
                    if (content.has(CardSection.ProfessionalLinks)) {
                        EditorialSection(R.string.professional_links) { ProfessionalLinkList(content, divided = true) }
                    }
                    if (content.has(CardSection.HostedProfile)) {
                        EditorialSection(R.string.hosted_profile) { HostedProfileRow(content) }
                    }
                    if (content.has(CardSection.PublicKey)) {
                        PublicKeySection(content)
                    }
                }
            }
        }
    }
}

private val EDITORIAL_NAME_SIZES = floatArrayOf(36f, 32f, 28f, 24f)

/**
 * Largest serif name size (sp) whose longest word fits on one line and whose whole name needs at most three lines,
 * estimated conservatively from glyph width. Falls back to the smallest size; text always wraps and is never clipped.
 */
internal fun editorialNameSizeSp(name: String, textWidthDp: Float, fontScale: Float): Float {
    val longestWord = name.split(Regex("\\s+")).maxOfOrNull { it.length } ?: 0
    return EDITORIAL_NAME_SIZES.firstOrNull { size ->
        val glyph = size * fontScale * 0.62f
        longestWord * glyph <= textWidthDp && kotlin.math.ceil(name.length * glyph / textWidthDp) <= 3
    } ?: EDITORIAL_NAME_SIZES.last()
}

/** Portrait above the title (instead of beside it) when width or font scale leaves too little room for the title. */
internal fun editorialStacksByline(textWidthDp: Float, fontScale: Float): Boolean =
    fontScale >= 1.5f || textWidthDp - 80f < 160f * fontScale

@Composable
private fun EditorialSection(label: Int, body: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface, thickness = 1.dp)
        SectionLabel(label, MaterialTheme.colorScheme.onSurface)
        body()
    }
}
