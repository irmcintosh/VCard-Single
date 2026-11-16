package com.mackseoul.vcardnative.ui.screens.card

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mackseoul.vcardnative.R

/** Structured business card: accent rule, identity header, then clearly labeled functional panels. */
@Composable
internal fun ProfessionalCard(content: CardContent, modifier: Modifier = Modifier) {
    val profile = content.profile
    val stacked = LocalDensity.current.fontScale >= 1.5f
    CardSurface(modifier, shape = RoundedCornerShape(16.dp)) {
        Column {
            Box(Modifier.fillMaxWidth().height(6.dp).background(MaterialTheme.colorScheme.primary))
            if (content.has(CardSection.CoverImage)) {
                CardCover(content.cover, Modifier.fillMaxWidth().aspectRatio(4f))
            }
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val identity = @Composable {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CardName(
                            profile.fullName,
                            MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                        )
                        if (content.has(CardSection.ProfessionalTitle)) {
                            OptionalText(profile.professionalTitle, MaterialTheme.typography.bodyLarge)
                        }
                        if (content.has(CardSection.Organization)) {
                            OptionalText(
                                profile.organization,
                                MaterialTheme.typography.titleSmall,
                                MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                if (stacked) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CardPortrait(content.portrait, 72.dp, RoundedCornerShape(12.dp))
                        identity()
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CardPortrait(content.portrait, 72.dp, RoundedCornerShape(12.dp))
                        Box(Modifier.weight(1f)) { identity() }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (content.has(CardSection.Biography)) {
                    OptionalText(profile.biography, MaterialTheme.typography.bodyLarge)
                }
                if (content.hasContact || content.has(CardSection.Location)) {
                    ProfessionalPanel(R.string.contact_information) {
                        if (content.has(CardSection.Location)) {
                            CardDetailRow(
                                R.string.location, R.drawable.ic_location, requireNotNull(profile.location),
                                MaterialTheme.colorScheme.primary,
                            )
                        }
                        CardContactDetails(content, MaterialTheme.colorScheme.primary, compact = true)
                    }
                }
                if (content.has(CardSection.ProfessionalLinks)) {
                    ProfessionalPanel(R.string.professional_links) { ProfessionalLinkList(content, divided = true, compact = true) }
                }
                if (content.has(CardSection.HostedProfile)) {
                    ProfessionalPanel(R.string.hosted_profile) { HostedProfileRow(content, compact = true) }
                }
                if (content.has(CardSection.PublicKey)) {
                    PublicKeySection(content)
                }
            }
        }
    }
}

@Composable
private fun ProfessionalPanel(label: Int, body: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SectionLabel(label)
            body()
        }
    }
}
