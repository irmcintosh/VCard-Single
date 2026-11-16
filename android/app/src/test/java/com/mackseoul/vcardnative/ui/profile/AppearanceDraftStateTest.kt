package com.mackseoul.vcardnative.ui.profile

import com.mackseoul.vcardnative.data.profile.RevisionToken
import com.mackseoul.vcardnative.data.profile.SavedProfile
import com.mackseoul.vcardnative.domain.profile.Accent
import com.mackseoul.vcardnative.domain.profile.AppData
import com.mackseoul.vcardnative.domain.profile.Appearance
import com.mackseoul.vcardnative.domain.profile.CardStyle
import com.mackseoul.vcardnative.domain.profile.MinimalItem
import com.mackseoul.vcardnative.domain.profile.Metadata
import com.mackseoul.vcardnative.domain.profile.Profile
import com.mackseoul.vcardnative.domain.profile.ProfileDraft
import com.mackseoul.vcardnative.domain.profile.ProfileField
import com.mackseoul.vcardnative.domain.profile.Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AppearanceDraftStateTest {
    private val saved = SavedProfile(
        AppData(
            profile = Profile(UUID.randomUUID(), mapOf(ProfileField.FullName to "Test fixture"), ProfileDraft.empty().visibility),
            metadata = Metadata("2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"),
        ),
        RevisionToken(UUID.randomUUID().toString(), 1),
    )
    private val state = ProfileScreenState(
        loading = false, destination = ProfileDestination.Appearance,
        saved = saved, appearanceDraft = saved.data.appearance,
    )

    @Test
    fun savedAppearanceStartsClean() {
        assertFalse(state.dirty)
    }

    @Test
    fun draftChoicesDoNotChangeSavedAppearance() {
        val changed = state.copy(appearanceDraft = Appearance(Theme.light, Accent.jade))
        assertTrue(changed.dirty)
        assertEquals(Appearance(), changed.saved?.data?.appearance)
        assertFalse(changed.copy(appearanceDraft = saved.data.appearance).dirty)
    }

    @Test
    fun appearanceReturnsToCleanWhenSelectionsMatchSavedValues() {
        val changed = state.copy(appearanceDraft = Appearance(Theme.light, Accent.jade))
        assertTrue(changed.dirty)
        assertFalse(changed.copy(appearanceDraft = Appearance()).dirty)
    }

    @Test
    fun imageSelectionAndProcessingPreventDuplicateOperations() {
        assertTrue(state.copy(processingImage = true).busy)
        assertTrue(state.copy(pickingImage = com.mackseoul.vcardnative.domain.profile.ImageRole.Portrait).busy)
        assertFalse(state.busy)
    }

    @Test
    fun everyCardStyleIsADraftChangeAndClassicIsTheDefault() {
        assertEquals(CardStyle.Classic, state.appearanceDraft?.cardStyle)
        CardStyle.entries.filter { it != CardStyle.Classic }.forEach { style ->
            val changed = state.copy(appearanceDraft = Appearance(cardStyle = style))
            assertTrue(style.name, changed.dirty)
            assertEquals(Appearance(), changed.saved?.data?.appearance)
        }
    }

    @Test
    fun minimalSelectionsAllowZeroToFourAndRefuseAFifth() {
        var draft = Appearance()
        assertTrue(draft.canAddMinimalItem)
        MinimalItem.entries.take(4).forEachIndexed { index, item ->
            draft = draft.toggleMinimalItem(item)
            assertEquals(index + 1, draft.minimalItems.size)
        }
        assertFalse(draft.canAddMinimalItem)
        val refused = draft.toggleMinimalItem(MinimalItem.HostedProfile)
        assertEquals(draft, refused)
        assertEquals(4, refused.minimalItems.size)
        val cleared = refused.toggleMinimalItem(MinimalItem.ProfilePhoto)
        assertEquals(3, cleared.minimalItems.size)
        assertTrue(cleared.toggleMinimalItem(MinimalItem.HostedProfile).minimalItems.contains(MinimalItem.HostedProfile))
    }

    @Test
    fun minimalSelectionsSurviveStyleChanges() {
        val selected = setOf(MinimalItem.Phone, MinimalItem.ProfessionalLinks)
        val minimal = Appearance(cardStyle = CardStyle.Minimal, minimalItems = selected)
        CardStyle.entries.forEach { style ->
            assertEquals(selected, minimal.copy(cardStyle = style).copy(cardStyle = CardStyle.Minimal).minimalItems)
        }
    }

    @Test
    fun selectedButEmptyMinimalItemsStaySelectedAndAreReported() {
        val minimal = Appearance(
            cardStyle = CardStyle.Minimal,
            minimalItems = setOf(MinimalItem.Email, MinimalItem.ProfilePhoto, MinimalItem.HostedProfile),
        )
        val editing = state.copy(appearanceDraft = minimal)
        assertEquals(minimal.minimalItems, editing.minimalItemsWithoutContent(minimal))
        assertEquals(3, editing.appearanceDraft?.minimalItems?.size)
        assertEquals(mapOf(ProfileField.FullName to "Test fixture"), editing.saved?.data?.profile?.values)
    }

    @Test
    fun minimalSummaryCountsNotShownSelectionsTowardTheLimit() {
        val minimal = Appearance(
            cardStyle = CardStyle.Minimal,
            minimalItems = setOf(MinimalItem.Email, MinimalItem.ProfilePhoto, MinimalItem.HostedProfile, MinimalItem.Phone),
        )
        val summary = minimalSelectionSummary(minimal, setOf(MinimalItem.Email, MinimalItem.HostedProfile, MinimalItem.Location))
        assertEquals(4, summary.selected)
        assertEquals(2, summary.notShown)
        assertEquals(2, summary.shown)
        assertEquals(Appearance.MAX_MINIMAL_ITEMS, summary.limit)
        assertFalse(minimal.canAddMinimalItem)
        assertEquals(MinimalSelectionSummary(0, 0), minimalSelectionSummary(Appearance(), emptySet()))
        val editing = state.copy(appearanceDraft = minimal)
        assertEquals(4, minimalSelectionSummary(minimal, editing.minimalItemsWithoutContent(minimal)).notShown)
    }

    @Test
    fun minimalSelectorExplainsNotShownItemsOnce() {
        val editor = java.io.File("src/main/java/com/mackseoul/vcardnative/ui/profile/AppearanceScreen.kt").readText()
        val selector = editor.substringAfter("private fun MinimalItemSelector(").substringBefore("private fun ChoiceRow(")
        assertTrue(selector.contains("if (summary.notShown > 0) Body(R.string.minimal_items_missing_note)"))
        assertTrue(selector.contains("R.string.minimal_items_count_with_hidden"))
        assertTrue(selector.contains("if (checked && item in missing)"))
        assertEquals(1, Regex("""minimal_items_missing_note""").findAll(selector).count())
        val strings = java.io.File("src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains("""<string name="minimal_item_missing">Not shown</string>"""))
        assertTrue(strings.contains("still count toward the four-item limit"))
        assertTrue(strings.contains("appear automatically once they have a visible value"))
    }
    @Test
    fun galleryLabelsGetFullWidthAndGrowWithFontScale() {
        assertEquals(120f, cardStyleGalleryItemWidthDp(1f), 0f)
        assertEquals(120f, cardStyleGalleryItemWidthDp(0.85f), 0f)
        assertEquals(180f, cardStyleGalleryItemWidthDp(1.5f), 0.001f)
        assertEquals(240f, cardStyleGalleryItemWidthDp(3f), 0f)
        val editor = java.io.File("src/main/java/com/mackseoul/vcardnative/ui/profile/AppearanceScreen.kt").readText()
        val gallery = editor.substringAfter("private fun CardStyleGallery(").substringBefore("private fun AppearancePreview(")
        assertTrue(gallery.contains("items(CardStyle.entries)"))
        assertTrue(gallery.contains("LazyRow("))
        assertTrue(gallery.contains("Modifier.width(itemWidth)"))
        assertTrue(gallery.contains(".selectable(selected, enabled, Role.RadioButton) { select(style) }"))
        assertTrue(gallery.contains("CardStyleThumbnail(style, Modifier.width(GALLERY_THUMBNAIL_WIDTH_DP.dp).height(140.dp))"))
        // Radio sits above the label (not beside it), and the label is centered across the full item width.
        assertFalse(Regex("""\bRow\(""").containsMatchIn(gallery))
        val label = gallery.substringAfter("RadioButton(selected = selected, onClick = null, enabled = enabled)")
        assertTrue(label.contains("stringResource(style.labelRes())"))
        assertTrue(label.contains("textAlign = TextAlign.Center"))
        assertTrue(label.contains("modifier = Modifier.fillMaxWidth()"))
        assertFalse(gallery.contains("maxLines"))
        assertFalse(gallery.contains("TextOverflow"))
        assertEquals(7, CardStyle.entries.size)
    }
    @Test
    fun restoreDefaultChangesOnlyTheDraft() {
        val customSaved = SavedProfile(
            saved.data.copy(appearance = Appearance(Theme.light, Accent.jade, CardStyle.Horizon, setOf(MinimalItem.Email))),
            saved.token,
        )
        val editing = state.copy(saved = customSaved, appearanceDraft = customSaved.data.appearance)
        assertFalse(editing.dirty)
        val restored = editing.restoreDefaultAppearanceDraft()
        assertEquals(Appearance(Theme.dark, Accent.sapphire, CardStyle.Classic, emptySet()), restored.appearanceDraft)
        assertTrue(restored.dirty)
        assertEquals(customSaved.data.appearance, restored.saved?.data?.appearance)
        assertEquals(ProfileDestination.Appearance, restored.destination)
        assertEquals(editing, editing.copy(processingImage = true).restoreDefaultAppearanceDraft().copy(processingImage = false))
    }

    @Test
    fun cancelDiscardsTheDraftAndLeavesSavedAppearance() {
        val editing = state.copy(appearanceDraft = Appearance(Theme.light, Accent.amethyst, CardStyle.Modern))
        assertTrue(editing.dirty)
        val cancelled = editing.cancelAppearanceDraft()
        assertEquals(ProfileDestination.Profile, cancelled.destination)
        assertEquals(saved.data.appearance, cancelled.appearanceDraft)
        assertFalse(cancelled.dirty)
        assertFalse(cancelled.confirmDiscard)
        assertEquals(saved, cancelled.saved)
        val busy = editing.copy(processingImage = true)
        assertEquals(busy, busy.cancelAppearanceDraft())
    }

    @Test
    fun dirtyAppearanceBackStillRequiresConfirmation() {
        val model = java.io.File("src/main/java/com/mackseoul/vcardnative/ui/profile/ProfileViewModel.kt").readText()
        assertTrue(model.contains("ProfileDestination.Appearance -> if (current.dirty) {"))
        val screen = java.io.File("src/main/java/com/mackseoul/vcardnative/ui/profile/AppearanceScreen.kt").readText()
        assertTrue(screen.contains("back = { model.requestBack(exit) }"))
        assertTrue(screen.contains("onClick = model::cancelAppearance"))
        assertTrue(screen.contains("onClick = model::restoreDefaultAppearance"))
        assertTrue(screen.contains("enabled = enabled && state.unavailable == null && state.dirty"))
    }
}
