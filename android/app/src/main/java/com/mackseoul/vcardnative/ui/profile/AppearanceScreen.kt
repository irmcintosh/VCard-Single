package com.mackseoul.vcardnative.ui.profile

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mackseoul.vcardnative.R
import com.mackseoul.vcardnative.domain.profile.Accent
import com.mackseoul.vcardnative.domain.profile.Appearance
import com.mackseoul.vcardnative.domain.profile.CardStyle
import com.mackseoul.vcardnative.domain.profile.ImageRole
import com.mackseoul.vcardnative.domain.profile.MinimalItem
import com.mackseoul.vcardnative.domain.profile.Theme
import com.mackseoul.vcardnative.ui.screens.card.CardStyleThumbnail
import com.mackseoul.vcardnative.ui.screens.card.ProfileCard
import com.mackseoul.vcardnative.ui.theme.VCardTheme
import com.mackseoul.vcardnative.ui.theme.appearanceColorScheme

@Composable
internal fun AppearanceScreen(state: ProfileScreenState, model: ProfileViewModel, exit: () -> Unit) {
    val draft = requireNotNull(state.appearanceDraft)
    val enabled = !state.busy
    ScreenFrame(
        title = R.string.appearance,
        back = { model.requestBack(exit) },
        backEnabled = !state.busy,
        bottom = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier.windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                    ).fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = model::cancelAppearance,
                        enabled = enabled,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.cancel), textAlign = TextAlign.Center)
                    }
                    Button(
                        onClick = model::saveAppearance,
                        enabled = enabled && state.unavailable == null && state.dirty,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Text(
                            stringResource(if (state.saving) R.string.saving_appearance else R.string.save_appearance),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        },
    ) {
        Body(R.string.appearance_guidance)
        state.saveError?.let { ErrorMessage(it) }
        Heading(R.string.card_style_choices)
        CardStyleGallery(draft, enabled, model::selectCardStyle)
        Heading(R.string.live_preview)
        AppearancePreview(state, draft)
        if (draft.cardStyle == CardStyle.Minimal) {
            MinimalItemSelector(state, draft, enabled, model::toggleMinimalItem)
        }
        Heading(R.string.theme_choices)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Theme.entries.forEach { theme ->
                ChoiceRow(
                    if (theme == Theme.dark) R.string.theme_dark else R.string.theme_light,
                    selected = draft.theme == theme, enabled = enabled,
                ) { model.appearance(draft.copy(theme = theme)) }
            }
        }
        Heading(R.string.accent_choices)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Accent.entries.forEach { accent ->
                val colors = appearanceColorScheme(Appearance(draft.theme, accent))
                ChoiceRow(
                    when (accent) {
                        Accent.sapphire -> R.string.accent_sapphire
                        Accent.jade -> R.string.accent_jade
                        Accent.amethyst -> R.string.accent_amethyst
                    },
                    selected = draft.accent == accent, enabled = enabled,
                    swatch = { Surface(color = colors.primary, shape = CircleShape, modifier = Modifier.size(24.dp)) {} },
                ) { model.appearance(draft.copy(accent = accent)) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Body(R.string.restore_default_appearance_help)
            OutlinedButton(
                onClick = model::restoreDefaultAppearance,
                enabled = enabled && draft != Appearance(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.restore_default_appearance)) }
        }
    }
}

@StringRes
internal fun CardStyle.labelRes(): Int = when (this) {
    CardStyle.Classic -> R.string.card_style_classic
    CardStyle.Minimal -> R.string.card_style_minimal
    CardStyle.Professional -> R.string.card_style_professional
    CardStyle.Modern -> R.string.card_style_modern
    CardStyle.Executive -> R.string.card_style_executive
    CardStyle.Editorial -> R.string.card_style_editorial
    CardStyle.Horizon -> R.string.card_style_horizon
}

@StringRes
internal fun MinimalItem.labelRes(): Int = when (this) {
    MinimalItem.ProfilePhoto -> R.string.profile_image
    MinimalItem.CoverImage -> R.string.cover_image
    MinimalItem.ProfessionalTitle -> R.string.professional_title
    MinimalItem.Organization -> R.string.organization
    MinimalItem.Biography -> R.string.biography
    MinimalItem.Phone -> R.string.phone
    MinimalItem.Email -> R.string.email
    MinimalItem.Location -> R.string.location
    MinimalItem.Website -> R.string.website
    MinimalItem.ProfessionalLinks -> R.string.professional_links
    MinimalItem.HostedProfile -> R.string.minimal_item_hosted_profile
}

private const val GALLERY_THUMBNAIL_WIDTH_DP = 104f

/**
 * Gallery item width: 120 dp at normal text, widening with font scale (thumbnail size unchanged) so style names such as
 * "Professional" stay on one line; capped so extreme scales wrap at word boundaries instead of overflowing.
 */
internal fun cardStyleGalleryItemWidthDp(fontScale: Float): Float = 120f * fontScale.coerceIn(1f, 2f)

/** Horizontally scrolling, single-choice gallery. Thumbnails use the draft colors but never real profile data. */
@Composable
private fun CardStyleGallery(draft: Appearance, enabled: Boolean, select: (CardStyle) -> Unit) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = draft.cardStyle.ordinal)
    val description = stringResource(R.string.card_style_gallery)
    val itemWidth = cardStyleGalleryItemWidthDp(LocalDensity.current.fontScale).dp
    LazyRow(
        Modifier.fillMaxWidth().selectableGroup().semantics { contentDescription = description },
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(CardStyle.entries) { style ->
            val selected = draft.cardStyle == style
            Column(
                Modifier.width(itemWidth).clip(RoundedCornerShape(16.dp))
                    .border(
                        if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
                        else BorderStroke(1.dp, Color.Transparent),
                        RoundedCornerShape(16.dp),
                    )
                    .selectable(selected, enabled, Role.RadioButton) { select(style) }
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                VCardTheme(appearance = draft) {
                    CardStyleThumbnail(style, Modifier.width(GALLERY_THUMBNAIL_WIDTH_DP.dp).height(140.dp))
                }
                // Radio above a centered label: the name gets the full item width, and controls align across items.
                RadioButton(selected = selected, onClick = null, enabled = enabled)
                Text(
                    stringResource(style.labelRes()),
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Large live preview drawn by the same [ProfileCard] entry point as My Card, in the draft theme. Callbacks are
 * inert and an overlay keeps touches away from the card's controls, so the preview can never open a real action.
 */
@Composable
private fun AppearancePreview(state: ProfileScreenState, draft: Appearance) {
    val profile = remember(state.saved, state.keyStatus) { state.saved?.data?.toMyCard(state.keyStatus)?.profile }
        ?: return
    val images = state.visibleCardImages()
    val description = stringResource(R.string.appearance_preview)
    VCardTheme(appearance = draft) {
        Box(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }) {
            ProfileCard(
                draft.cardStyle, draft.minimalItems, profile, images.cover, images.portrait,
                modifier = Modifier.fillMaxWidth(),
                keyBusy = false,
                onShowOnlineQr = {},
            )
            // Receives (without consuming) every pointer event, so parent scrolling still works.
            Box(
                Modifier.matchParentSize().pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent() }
                },
            )
        }
    }
}

@Composable
private fun MinimalItemSelector(
    state: ProfileScreenState,
    draft: Appearance,
    enabled: Boolean,
    toggle: (MinimalItem) -> Unit,
) {
    val missing = remember(state.saved, state.keyStatus, state.savedImages, draft) {
        state.minimalItemsWithoutContent(draft)
    }
    val summary = minimalSelectionSummary(draft, missing)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Heading(R.string.minimal_items_title)
        Body(R.string.minimal_items_help)
        Text(
            if (summary.notShown == 0) stringResource(R.string.minimal_items_count, summary.selected, summary.limit)
            else stringResource(R.string.minimal_items_count_with_hidden, summary.selected, summary.limit, summary.notShown),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (!draft.canAddMinimalItem) Body(R.string.minimal_items_limit)
        if (summary.notShown > 0) Body(R.string.minimal_items_missing_note)
        Column {
            MinimalItem.entries.forEach { item ->
                val checked = item in draft.minimalItems
                val itemEnabled = enabled && (checked || draft.canAddMinimalItem)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(checked, itemEnabled, Role.Checkbox) { toggle(item) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null, enabled = itemEnabled)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(item.labelRes()), style = MaterialTheme.typography.bodyLarge)
                        if (checked && item in missing) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            ) {
                                Text(
                                    stringResource(R.string.minimal_item_missing),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun ChoiceRow(
    @StringRes label: Int,
    selected: Boolean,
    enabled: Boolean,
    swatch: @Composable () -> Unit = {},
    select: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected, enabled, Role.RadioButton, onClick = select)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        swatch()
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun ProfileImagePreview(role: ImageRole, image: ImageBitmap?) {
    if (image == null) {
        Body(R.string.image_absent)
    } else {
        Image(
            bitmap = image,
            contentDescription = stringResource(
                if (role == ImageRole.Portrait) R.string.profile_image_preview else R.string.cover_image_preview,
            ),
            modifier = if (role == ImageRole.Portrait) Modifier.size(88.dp).clip(CircleShape)
            else Modifier.fillMaxWidth().aspectRatio(3f).clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop,
        )
    }
}
