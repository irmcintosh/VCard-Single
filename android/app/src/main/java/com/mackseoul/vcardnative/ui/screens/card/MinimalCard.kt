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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mackseoul.vcardnative.R

/** Quiet, content-first card: Full Name plus only the selected items that have saved, card-visible values. */
@Composable
internal fun MinimalCard(content: CardContent, modifier: Modifier = Modifier) {
    val profile = content.profile
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    CardSurface(modifier) {
        Column {
            if (content.has(CardSection.CoverImage)) {
                CardCover(content.cover, Modifier.fillMaxWidth().aspectRatio(3f))
            }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (content.has(CardSection.ProfilePhoto)) {
                    CardPortrait(content.portrait, 72.dp)
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CardName(profile.fullName, MaterialTheme.typography.headlineMedium)
                    if (content.has(CardSection.ProfessionalTitle)) {
                        OptionalText(profile.professionalTitle, MaterialTheme.typography.bodyLarge, muted)
                    }
                    if (content.has(CardSection.Organization)) {
                        OptionalText(profile.organization, MaterialTheme.typography.bodyLarge, muted)
                    }
                }
                Box(Modifier.width(32.dp).height(2.dp).background(MaterialTheme.colorScheme.primary))
                if (content.has(CardSection.Biography)) {
                    OptionalText(profile.biography, MaterialTheme.typography.bodyLarge)
                }
                if (content.has(CardSection.Phone)) {
                    CardDetailRow(R.string.phone, R.drawable.ic_call, requireNotNull(profile.phone).value, showLabel = false)
                }
                if (content.has(CardSection.Email)) {
                    CardDetailRow(R.string.email, R.drawable.ic_email, requireNotNull(profile.email).value, showLabel = false)
                }
                if (content.has(CardSection.Location)) {
                    CardDetailRow(R.string.location, R.drawable.ic_location, requireNotNull(profile.location), showLabel = false)
                }
                if (content.has(CardSection.Website)) {
                    WebsiteLink(content)
                }
                if (content.has(CardSection.ProfessionalLinks)) {
                    ProfessionalLinkList(content)
                }
                if (content.has(CardSection.HostedProfile)) {
                    HostedProfileRow(content)
                }
            }
        }
    }
}
