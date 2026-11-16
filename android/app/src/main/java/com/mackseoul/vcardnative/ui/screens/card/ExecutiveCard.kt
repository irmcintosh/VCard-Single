package com.mackseoul.vcardnative.ui.screens.card

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mackseoul.vcardnative.R
import java.util.Locale

/** Refined, restrained card: centered identity, hairline dividers and open spacing without panels. */
@Composable
internal fun ExecutiveCard(content: CardContent, modifier: Modifier = Modifier) {
    val profile = content.profile
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val divider = MaterialTheme.colorScheme.outlineVariant
    CardSurface(modifier, shape = RoundedCornerShape(8.dp)) {
        Column {
            if (content.has(CardSection.CoverImage)) {
                CardCover(content.cover, Modifier.fillMaxWidth().aspectRatio(3.2f))
            }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (content.has(CardSection.ProfilePhoto)) {
                        CardPortrait(content.portrait, 84.dp, ring = MaterialTheme.colorScheme.outline)
                    }
                    CardName(
                        profile.fullName,
                        MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Light, letterSpacing = 0.5.sp, textAlign = TextAlign.Center,
                        ),
                    )
                    Box(Modifier.width(40.dp).height(1.dp).background(MaterialTheme.colorScheme.primary))
                    listOfNotNull(
                        profile.professionalTitle.takeIf { content.has(CardSection.ProfessionalTitle) },
                        profile.organization.takeIf { content.has(CardSection.Organization) },
                    ).forEach {
                        Text(
                            it.uppercase(Locale.getDefault()),
                            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.5.sp),
                            color = muted, textAlign = TextAlign.Center,
                        )
                    }
                }
                if (content.has(CardSection.Biography)) {
                    Text(
                        requireNotNull(profile.biography), style = MaterialTheme.typography.bodyLarge,
                        color = muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (content.hasContact || content.has(CardSection.Location)) {
                    HorizontalDivider(color = divider)
                    Column {
                        if (content.has(CardSection.Location)) {
                            CardDetailRow(R.string.location, R.drawable.ic_location, requireNotNull(profile.location))
                        }
                        CardContactDetails(content)
                    }
                }
                if (content.has(CardSection.ProfessionalLinks)) {
                    HorizontalDivider(color = divider)
                    Column {
                        SectionLabel(R.string.professional_links, muted)
                        ProfessionalLinkList(content, compact = true)
                    }
                }
                if (content.has(CardSection.HostedProfile)) {
                    HorizontalDivider(color = divider)
                    HostedProfileRow(content, compact = true)
                }
                if (content.has(CardSection.PublicKey)) {
                    PublicKeySection(content)
                }
            }
        }
    }
}
