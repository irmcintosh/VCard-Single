package com.mackseoul.vcardnative.ui.profile

import com.mackseoul.vcardnative.domain.profile.ProfileDraft
import com.mackseoul.vcardnative.domain.profile.ProfileField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileDraftStateTest {
    @Test fun menuTargetsResolveAfterInitializationAndGenerateQrOnlyForShare() {
        val saved = com.mackseoul.vcardnative.data.profile.SavedProfile(
            com.mackseoul.vcardnative.domain.profile.AppData(
                profile = com.mackseoul.vcardnative.domain.profile.Profile(java.util.UUID.randomUUID(),
                    mapOf(ProfileField.FullName to "Saved"), ProfileDraft.empty().visibility),
                metadata = com.mackseoul.vcardnative.domain.profile.Metadata("2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")),
            com.mackseoul.vcardnative.data.profile.RevisionToken(java.util.UUID.randomUUID().toString(), 1))
        for (choice in com.mackseoul.vcardnative.ui.widget.QuickCardWidgetMenuDestination.entries) {
            val cold = ProfileScreenState(loading = true, saved = saved, pendingWidgetLaunch = true,
                pendingWidgetDestination = choice)
            assertEquals(cold, cold.resolveWidgetLaunch())
            val ready = cold.copy(loading = false).resolveWidgetLaunch()
            assertEquals(choice.destination, ready.destination)
            assertEquals(choice.destination == ProfileDestination.Home, ready.shouldRefreshContact)
            val warm = cold.copy(loading = false, destination = ProfileDestination.MyCard).resolveWidgetLaunch()
            assertEquals(choice.destination, warm.destination)
            val empty = cold.copy(loading = false, saved = null).resolveWidgetLaunch()
            assertEquals(ProfileDestination.Welcome, empty.destination)
            val unavailable = cold.copy(loading = false, unavailable = "Corrupt", recoveryToken = "token").resolveWidgetLaunch()
            assertEquals(ProfileDestination.Profile, unavailable.destination)
            assertEquals("token", unavailable.recoveryToken)
            val original = ProfileDraft.empty()
            val dirty = cold.copy(loading = false, destination = ProfileDestination.Editor,
                initialDraft = original, draft = original.copy(values = mapOf(ProfileField.FullName to "Unsaved")))
            assertTrue(dirty.resolveWidgetLaunch().confirmDiscard)
            assertEquals(dirty.draft, dirty.resolveWidgetLaunch().draft)
        }
    }

    @Test fun widgetNavigationWaitsForLoadingAndKeepsQrShortcutSeparate() {
        assertFalse(com.mackseoul.vcardnative.MainActivity.ACTION_OPEN_MY_CARD_FROM_WIDGET ==
            com.mackseoul.vcardnative.MainActivity.ACTION_SHOW_MY_QR_CODE)
        val saved = com.mackseoul.vcardnative.data.profile.SavedProfile(
            com.mackseoul.vcardnative.domain.profile.AppData(
                profile = com.mackseoul.vcardnative.domain.profile.Profile(java.util.UUID.randomUUID(),
                    mapOf(ProfileField.FullName to "Saved"), ProfileDraft.empty().visibility),
                metadata = com.mackseoul.vcardnative.domain.profile.Metadata("2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")),
            com.mackseoul.vcardnative.data.profile.RevisionToken(java.util.UUID.randomUUID().toString(), 1))
        val pending = ProfileScreenState(loading = true, pendingWidgetLaunch = true, saved = saved,
            destination = ProfileDestination.Home)
        assertEquals(pending, pending.resolveWidgetLaunch())
        val opened = pending.copy(loading = false).resolveWidgetLaunch()
        assertEquals(ProfileDestination.Home, opened.destination)
        assertFalse(opened.pendingShortcutQr)
        assertFalse(opened.shortcutQrOrigin)
        assertFalse(opened.pendingWidgetLaunch)
        assertEquals(saved, opened.saved)
        assertEquals(opened, opened.resolveWidgetLaunch())
        assertTrue(opened.shouldRefreshContact)
        assertTrue(pending.copy(loading = false, pendingWidgetLaunch = false).shouldRefreshContact)
        assertTrue(pending.copy(loading = false, pendingWidgetLaunch = false, pendingShortcutQr = true)
            .resolveWidgetLaunch().shouldRefreshContact)
    }

    @Test fun widgetNavigationRetainsEmptySetupAndRecoveryDistinctions() {
        val empty = ProfileScreenState(loading = false, pendingWidgetLaunch = true,
            draft = ProfileDraft.empty(), initialDraft = ProfileDraft.empty(), firstRun = true)
        val resolved = empty.resolveWidgetLaunch()
        assertEquals(ProfileDestination.Welcome, resolved.destination)
        assertFalse(resolved.shouldRefreshContact)
        val unavailable = empty.copy(unavailable = "Corrupt", recoveryToken = "token", pendingOperation = "blocked")
        val recovered = unavailable.resolveWidgetLaunch()
        assertEquals(ProfileDestination.Profile, recovered.destination)
        assertFalse(recovered.shouldRefreshContact)
        assertEquals("Corrupt", recovered.unavailable)
        assertEquals("token", recovered.recoveryToken)
        assertEquals("blocked", recovered.pendingOperation)
    }

    @Test fun warmWidgetLaunchOpensShareAndNormalStartupAndShortcutRemainUnchanged() {
        val saved = com.mackseoul.vcardnative.data.profile.SavedProfile(
            com.mackseoul.vcardnative.domain.profile.AppData(
                profile = com.mackseoul.vcardnative.domain.profile.Profile(java.util.UUID.randomUUID(),
                    mapOf(ProfileField.FullName to "Saved"), ProfileDraft.empty().visibility),
                metadata = com.mackseoul.vcardnative.domain.profile.Metadata("2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")),
            com.mackseoul.vcardnative.data.profile.RevisionToken(java.util.UUID.randomUUID().toString(), 1))
        val warm = ProfileScreenState(loading = false, saved = saved, destination = ProfileDestination.MyCard,
            pendingWidgetLaunch = true, shortcutQrOrigin = true)
        val returned = warm.resolveWidgetLaunch()
        assertEquals(ProfileDestination.Home, returned.destination)
        assertFalse(returned.shortcutQrOrigin)
        assertTrue(returned.shouldRefreshContact)
        assertEquals(saved, returned.saved)
        val normal = warm.copy(destination = ProfileDestination.Home, pendingWidgetLaunch = false,
            shortcutQrOrigin = false)
        assertEquals(normal, normal.resolveWidgetLaunch())
        assertTrue(normal.shouldRefreshContact)
        val shortcut = normal.copy(destination = ProfileDestination.QrPresentation, pendingShortcutQr = true,
            shortcutQrOrigin = true)
        assertEquals(shortcut, shortcut.resolveWidgetLaunch())
        assertTrue(shortcut.shouldRefreshContact)
    }

    @Test fun widgetLaunchKeepsDirtyEditorDraftUntilDiscardConfirmation() {
        val original = ProfileDraft.empty()
        val edited = original.copy(values = mapOf(ProfileField.FullName to "Unsaved"))
        val state = ProfileScreenState(loading = false, pendingWidgetLaunch = true,
            destination = ProfileDestination.Editor, draft = edited, initialDraft = original)
        val guarded = state.resolveWidgetLaunch()
        assertEquals(ProfileDestination.Editor, guarded.destination)
        assertTrue(guarded.confirmDiscard)
        assertEquals(edited, guarded.draft)
        assertEquals(original, guarded.initialDraft)
    }

    @Test fun onlineCardQrUsesOnlySavedUrlAndSeparateNavigation() {
        val url = "HTTPS://EXAMPLE.COM/Saved/%2f"
        val saved = com.mackseoul.vcardnative.data.profile.SavedProfile(
            com.mackseoul.vcardnative.domain.profile.AppData(
                profile = com.mackseoul.vcardnative.domain.profile.Profile(java.util.UUID.randomUUID(),
                    mapOf(ProfileField.FullName to "Saved", ProfileField.HostedProfileUrl to url),
                    ProfileDraft.empty().visibility),
                metadata = com.mackseoul.vcardnative.domain.profile.Metadata("2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")),
            com.mackseoul.vcardnative.data.profile.RevisionToken(java.util.UUID.randomUUID().toString(), 1))
        val state = ProfileScreenState(loading = false, saved = saved, destination = ProfileDestination.MyCard,
            draft = ProfileDraft.empty().copy(values = mapOf(ProfileField.HostedProfileUrl to "https://example.com/Unsaved")))
        assertEquals(url, state.onlineCardUrl)
        val opened = state.openOnlineCardQr()
        assertEquals(ProfileDestination.OnlineCardQr, opened.destination)
        assertEquals(state.saved, opened.saved)
        assertEquals(state.contactQr, opened.contactQr)
        assertEquals(state.quickShareSession, opened.quickShareSession)
        assertEquals(state, state.copy(saved = null).openOnlineCardQr().copy(saved = saved))
        assertEquals(ProfileDestination.MyCard, state.copy(loading = true).openOnlineCardQr().destination)
        assertEquals(ProfileDestination.Editor, state.copy(destination = ProfileDestination.Editor).openOnlineCardQr().destination)
    }

    @Test fun knownSelectionFillsBlankOrPreviouslyAutomaticLabelsOnly() {
        val entry = com.mackseoul.vcardnative.domain.profile.SocialProfile(
            java.util.UUID.randomUUID(), "", "", "https://example.com/exact/%2f", false, true,
        )
        val blank = SocialPlatformSelection.fromSaved(entry.platform)
        val linkedin = blank.select(SocialPlatformOption.LinkedIn)
        val selected = linkedin.applyTo(entry, blank)
        assertEquals("LinkedIn", selected.label)
        assertEquals(entry.id, selected.id)
        assertEquals(entry.url, selected.url)
        assertEquals(entry.showOnCard, selected.showOnCard)
        assertEquals(entry.includeInContactExport, selected.includeInContactExport)
        val instagram = linkedin.select(SocialPlatformOption.Instagram)
        assertEquals("Instagram", instagram.applyTo(selected, linkedin).label)
        val manuallyLabeled = selected.copy(label = "My professional account")
        assertEquals("My professional account", instagram.applyTo(manuallyLabeled, linkedin).label)
        val twitter = instagram.select(SocialPlatformOption.XTwitter)
        assertEquals("X (Twitter)", twitter.applyTo(selected.copy(label = "Instagram"), instagram).label)
    }

    @Test fun otherPlatformLabelTracksCustomNameUntilManuallyOverridden() {
        val entry = com.mackseoul.vcardnative.domain.profile.SocialProfile(
            java.util.UUID.randomUUID(), "", "", "https://example.com/profile", true, false,
        )
        val blank = SocialPlatformSelection.fromSaved("")
        val custom = blank.copy(customName = "Custom Network")
        val generated = custom.applyTo(entry, blank)
        assertEquals("Custom Network", generated.platform)
        assertEquals("Custom Network", generated.label)
        val changed = custom.copy(customName = "New Network")
        assertEquals("New Network", changed.applyTo(generated, custom).label)
        assertEquals("Custom label", changed.applyTo(generated.copy(label = "Custom label"), custom).label)
        val known = custom.select(SocialPlatformOption.GitHub)
        val knownEntry = known.applyTo(generated, custom)
        assertEquals("GitHub", knownEntry.label)
        assertEquals("Custom Network", known.select(SocialPlatformOption.Other).applyTo(knownEntry, known).label)
    }

    @Test fun loadedCustomLabelsRemainIndependentOfAutomaticLabels() {
        val saved = com.mackseoul.vcardnative.domain.profile.SocialProfile(
            java.util.UUID.randomUUID(), "linkedin", "My customized label", "https://example.com/work", true, true,
        )
        val selection = SocialPlatformSelection.fromSaved(saved.platform)
        assertEquals(saved, selection.applyTo(saved, selection))
        assertEquals(saved.label, selection.select(SocialPlatformOption.GitHub).applyTo(saved, selection).label)
    }

    @Test fun knownPlatformsMatchCanonicalNamesAndDisplayNames() {
        SocialPlatformOption.entries.filter { it != SocialPlatformOption.Other }.forEach { option ->
            assertEquals(option, SocialPlatformSelection.fromSaved(requireNotNull(option.canonicalName)).option)
            assertEquals(option, SocialPlatformSelection.fromSaved(option.displayName).option)
            assertEquals(option.canonicalName,
                SocialPlatformSelection.fromSaved("Custom").select(option).platformName)
        }
        assertEquals(SocialPlatformOption.XTwitter, SocialPlatformSelection.fromSaved("Twitter").option)
    }

    @Test fun unknownPlatformsRemainExactCustomNames() {
        val saved = "My Custom Platform"
        val selection = SocialPlatformSelection.fromSaved(saved)
        assertEquals(SocialPlatformOption.Other, selection.option)
        assertEquals(saved, selection.customName)
        assertEquals(saved, selection.platformName)
    }

    @Test fun customNameSurvivesSwitchingToKnownAndBackToOther() {
        val initial = SocialPlatformSelection.fromSaved("Custom Case")
        val known = initial.select(SocialPlatformOption.LinkedIn)
        assertEquals("linkedin", known.platformName)
        assertEquals("Custom Case", known.customName)
        assertEquals("Custom Case", known.select(SocialPlatformOption.Other).platformName)
        assertEquals("Updated", initial.copy(customName = "Updated")
            .select(SocialPlatformOption.GitHub).select(SocialPlatformOption.Other).platformName)
    }

    private val initial = ProfileDraft.empty()
    private val state = ProfileScreenState(loading = false, draft = initial, initialDraft = initial)

    @Test
    fun unchangedDraftIsClean() {
        assertFalse(state.dirty)
    }

    @Test
    fun enteredTextChangesOnlyDraft() {
        val edited = initial.copy(values = initial.values + (ProfileField.FullName to "Draft fixture"))
        assertTrue(state.copy(draft = edited).dirty)
        assertEquals(initial, state.initialDraft)
        assertEquals("", initial.values[ProfileField.FullName].orEmpty())
    }

    @Test
    fun privacyChangeIsDirtyAndDoesNotAlterInitialPreferences() {
        val original = requireNotNull(initial.visibility[ProfileField.Email])
        val changed = initial.copy(
            visibility = initial.visibility + (ProfileField.Email to original.copy(includeInContactExport = true)),
        )
        assertTrue(state.copy(draft = changed).dirty)
        assertFalse(requireNotNull(original.includeInContactExport))
        assertEquals(original, initial.visibility[ProfileField.Email])
    }

    @Test
    fun revertingAllChangesReturnsToCleanState() {
        val changed = state.copy(
            draft = initial.copy(values = initial.values + (ProfileField.Organization to "Draft fixture")),
        )
        assertTrue(changed.dirty)
        assertFalse(changed.copy(draft = initial).dirty)
    }

    @Test fun hostedProfileEditsRemainDirtyAcrossReturnToAdvancedEditor() {
        val original = ProfileDraft.empty().copy(values = mapOf(
            ProfileField.FullName to "Saved identity",
            ProfileField.HostedProfileUrl to "https://example.com/original",
        ))
        val editing = ProfileScreenState(loading = false, draft = original, initialDraft = original,
            destination = ProfileDestination.HostedProfile, editorTab = ProfileEditorTab.Advanced)
        val entered = "HTTPS://EXAMPLE.COM/Replacement/%2f"
        val changed = editing.copy(draft = original.copy(values = original.values +
            (ProfileField.HostedProfileUrl to entered)))
        assertTrue(changed.dirty)
        val returned = changed.copy(destination = ProfileDestination.Editor)
        assertEquals(ProfileEditorTab.Advanced, returned.editorTab)
        assertEquals(entered, returned.draft!!.values[ProfileField.HostedProfileUrl])
        assertEquals("https://example.com/original", returned.initialDraft!!.values[ProfileField.HostedProfileUrl])
        assertTrue(returned.dirty)
        assertFalse(returned.copy(draft = original).dirty)
    }
}
