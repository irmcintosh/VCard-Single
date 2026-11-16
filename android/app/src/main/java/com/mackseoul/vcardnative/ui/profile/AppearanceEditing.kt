package com.mackseoul.vcardnative.ui.profile

import androidx.compose.ui.graphics.ImageBitmap
import com.mackseoul.vcardnative.domain.profile.Appearance
import com.mackseoul.vcardnative.domain.profile.ImageRole
import com.mackseoul.vcardnative.domain.profile.MinimalItem
import com.mackseoul.vcardnative.domain.profile.ProfileField
import com.mackseoul.vcardnative.ui.screens.card.hasContent
import com.mackseoul.vcardnative.ui.screens.card.section

/** Adds or removes [item]; adding a fifth item is refused and returns this appearance unchanged. */
internal fun Appearance.toggleMinimalItem(item: MinimalItem): Appearance = when {
    item in minimalItems -> copy(minimalItems = minimalItems - item)
    minimalItems.size >= Appearance.MAX_MINIMAL_ITEMS -> this
    else -> copy(minimalItems = minimalItems + item)
}

internal val Appearance.canAddMinimalItem: Boolean
    get() = minimalItems.size < Appearance.MAX_MINIMAL_ITEMS

/** Saved images allowed on the card. My Card and the Appearance preview both use this filter. */
internal class CardImages(val cover: ImageBitmap?, val portrait: ImageBitmap?)

internal fun ProfileScreenState.visibleCardImages(): CardImages {
    val data = saved?.data
    return CardImages(
        cover = savedImages[ImageRole.Cover].takeIf { data?.visibility(ProfileField.CoverImage)?.showOnCard == true },
        portrait = savedImages[ImageRole.Portrait].takeIf { data?.visibility(ProfileField.ProfileImage)?.showOnCard == true },
    )
}

/** Selected Minimal items that currently render nothing (no saved value, or hidden on the card). They stay selected. */
internal fun ProfileScreenState.minimalItemsWithoutContent(appearance: Appearance): Set<MinimalItem> {
    val profile = saved?.data?.toMyCard(keyStatus)?.profile ?: return appearance.minimalItems
    val images = visibleCardImages()
    return appearance.minimalItems.filterTo(mutableSetOf()) {
        !profile.hasContent(it.section, images.cover != null, images.portrait != null)
    }
}

/** Counts for the Minimal selector: every selection counts toward the limit, whether or not it is currently shown. */
internal data class MinimalSelectionSummary(val selected: Int, val notShown: Int) {
    val shown: Int get() = selected - notShown
    val limit: Int get() = Appearance.MAX_MINIMAL_ITEMS
}

internal fun minimalSelectionSummary(appearance: Appearance, withoutContent: Set<MinimalItem>): MinimalSelectionSummary =
    MinimalSelectionSummary(appearance.minimalItems.size, appearance.minimalItems.count { it in withoutContent })

/** Restore Default changes only the editor draft; nothing is persisted until Save. */
internal fun ProfileScreenState.restoreDefaultAppearanceDraft(): ProfileScreenState =
    if (busy || destination != ProfileDestination.Appearance || appearanceDraft == null) this
    else copy(appearanceDraft = Appearance(), saveError = null)

/** Cancel discards the draft without confirmation and returns to Profile. */
internal fun ProfileScreenState.cancelAppearanceDraft(): ProfileScreenState =
    if (busy || destination != ProfileDestination.Appearance) this
    else copy(
        appearanceDraft = saved?.data?.appearance, confirmDiscard = false, pendingImageLink = false,
        saveError = null, destination = ProfileDestination.Profile,
    )
