package com.mackseoul.vcardnative.ui.screens.card

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import com.mackseoul.vcardnative.domain.profile.CardStyle
import com.mackseoul.vcardnative.domain.profile.MinimalItem

/** Profile content a card can show. Availability always follows the existing visibility and empty-value rules. */
internal enum class CardSection {
    CoverImage, ProfilePhoto, ProfessionalTitle, Organization, Biography, Location,
    Phone, Email, Website, ProfessionalLinks, HostedProfile, PublicKey,
}

internal val MinimalItem.section: CardSection
    get() = when (this) {
        MinimalItem.ProfilePhoto -> CardSection.ProfilePhoto
        MinimalItem.CoverImage -> CardSection.CoverImage
        MinimalItem.ProfessionalTitle -> CardSection.ProfessionalTitle
        MinimalItem.Organization -> CardSection.Organization
        MinimalItem.Biography -> CardSection.Biography
        MinimalItem.Phone -> CardSection.Phone
        MinimalItem.Email -> CardSection.Email
        MinimalItem.Location -> CardSection.Location
        MinimalItem.Website -> CardSection.Website
        MinimalItem.ProfessionalLinks -> CardSection.ProfessionalLinks
        MinimalItem.HostedProfile -> CardSection.HostedProfile
    }

/** Whether [section] has a saved value that is allowed on the card. Images must already be visibility-filtered. */
internal fun MyCardProfile.hasContent(section: CardSection, hasCover: Boolean, hasPortrait: Boolean): Boolean =
    when (section) {
        CardSection.CoverImage -> hasCover
        CardSection.ProfilePhoto -> hasPortrait
        CardSection.ProfessionalTitle -> !professionalTitle.isNullOrBlank()
        CardSection.Organization -> !organization.isNullOrBlank()
        CardSection.Biography -> !biography.isNullOrBlank()
        CardSection.Location -> !location.isNullOrBlank()
        CardSection.Phone -> phone?.canDisplay == true
        CardSection.Email -> email?.canDisplay == true
        CardSection.Website -> website?.canDisplay == true
        CardSection.ProfessionalLinks -> visibleProfessionalLinks.isNotEmpty()
        CardSection.HostedProfile -> hostedProfileUrl != null
        CardSection.PublicKey -> canDisplayPublicKey
    }

/**
 * Sections a style renders, in display order. Every style except Minimal keeps all eligible Classic content.
 * Minimal renders only selected items that have content; selected-but-empty items stay selected but add no row.
 */
internal fun cardSections(
    style: CardStyle,
    minimalItems: Set<MinimalItem>,
    profile: MyCardProfile,
    hasCover: Boolean,
    hasPortrait: Boolean,
): List<CardSection> {
    val candidates = if (style == CardStyle.Minimal) {
        MinimalItem.entries.filter { it in minimalItems }.map { it.section }
    } else {
        CardSection.entries
    }
    return candidates.filter { profile.hasContent(it, hasCover, hasPortrait) }
}

/** Card callbacks. Defaults are inert, and `keyBusy` defaults to true so a callback-less card offers no key actions. */
internal class CardActions(
    val onContactAction: (CardContactAction) -> Unit = {},
    val onShowOnlineQr: (() -> Unit)? = null,
    val onDownloadPublicKey: () -> Unit = {},
    val onSharePublicKey: () -> Unit = {},
    val keyBusy: Boolean = true,
)

/**
 * The single card rendering entry point shared by My Card and the Appearance Editor's live preview.
 * Classic delegates to the unchanged V1.1 [ClassicCard].
 */
@Composable
internal fun ProfileCard(
    style: CardStyle,
    minimalItems: Set<MinimalItem>,
    profile: MyCardProfile,
    cover: ImageBitmap?,
    portrait: ImageBitmap?,
    modifier: Modifier = Modifier,
    onDownloadPublicKey: () -> Unit = {},
    onSharePublicKey: () -> Unit = {},
    keyBusy: Boolean = true,
    onContactAction: (CardContactAction) -> Unit = {},
    onShowOnlineQr: (() -> Unit)? = null,
) {
    if (style == CardStyle.Classic) {
        ClassicCard(
            profile, cover, portrait, modifier,
            onDownloadPublicKey = onDownloadPublicKey,
            onSharePublicKey = onSharePublicKey,
            keyBusy = keyBusy,
            onContactAction = onContactAction,
            onShowOnlineQr = onShowOnlineQr,
        )
        return
    }
    val sections = remember(style, minimalItems, profile, cover != null, portrait != null) {
        cardSections(style, minimalItems, profile, cover != null, portrait != null).toSet()
    }
    val actions = CardActions(onContactAction, onShowOnlineQr, onDownloadPublicKey, onSharePublicKey, keyBusy)
    val content = CardContent(profile, sections, cover, portrait, actions)
    when (style) {
        CardStyle.Classic -> Unit
        CardStyle.Minimal -> MinimalCard(content, modifier)
        CardStyle.Professional -> ProfessionalCard(content, modifier)
        CardStyle.Modern -> ModernCard(content, modifier)
        CardStyle.Executive -> ExecutiveCard(content, modifier)
        CardStyle.Editorial -> EditorialCard(content, modifier)
        CardStyle.Horizon -> HorizonCard(content, modifier)
    }
}

/** Everything a non-Classic renderer needs; [sections] decides what is shown. */
internal class CardContent(
    val profile: MyCardProfile,
    val sections: Set<CardSection>,
    val cover: ImageBitmap?,
    val portrait: ImageBitmap?,
    val actions: CardActions,
) {
    fun has(section: CardSection) = section in sections
    val hasContact get() = has(CardSection.Phone) || has(CardSection.Email) || has(CardSection.Website)
}
