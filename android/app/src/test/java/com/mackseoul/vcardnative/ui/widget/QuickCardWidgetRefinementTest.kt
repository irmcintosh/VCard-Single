package com.mackseoul.vcardnative.ui.widget

import com.mackseoul.vcardnative.data.profile.JavaTestStoreFiles
import com.mackseoul.vcardnative.data.profile.ProfileRead
import com.mackseoul.vcardnative.data.profile.ProfileRepository
import com.mackseoul.vcardnative.data.profile.RevisionToken
import com.mackseoul.vcardnative.data.profile.SavedProfile
import com.mackseoul.vcardnative.data.profile.ValidationException
import com.mackseoul.vcardnative.domain.profile.*
import com.mackseoul.vcardnative.ui.profile.ProfileDestination
import com.mackseoul.vcardnative.ui.profile.ProfileScreenState
import com.mackseoul.vcardnative.ui.profile.cancelAppearanceDraft
import com.mackseoul.vcardnative.ui.profile.restoreDefaultAppearanceDraft
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

class QuickCardWidgetRefinementTest {
    private val roots = mutableListOf<File>()
    @After fun cleanup() { roots.forEach { it.deleteRecursively() } }
    private fun files() = JavaTestStoreFiles(File("build\\widget-refinement-tests\\${UUID.randomUUID()}").also { roots += it })
    private fun draft(name: String = "Ada Lovelace") = ProfileDraft.empty().copy(values = mapOf(ProfileField.FullName to name))

    private fun saved(accent: Accent, name: String = "Saved Person") = SavedProfile(
        AppData(
            profile = Profile(UUID.randomUUID(), mapOf(ProfileField.FullName to name), ProfileDraft.empty().visibility),
            metadata = Metadata("2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"),
            appearance = Appearance(accent = accent),
        ),
        RevisionToken(UUID.randomUUID().toString(), 1),
    )

    private fun ready(saved: SavedProfile) =
        QuickCardWidgetProjection.from(ProfileRead.Ready(saved)) as QuickCardWidgetState.Ready

    private val namespace = "http://schemas.android.com/apk/res/android"
    private fun layout(variant: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File("src/main/res/layout/quick_card_widget_$variant.xml"))
    private fun org.w3c.dom.Document.byId(id: String): Element {
        val all = getElementsByTagName("*")
        for (i in 0 until all.length) {
            val element = all.item(i) as Element
            if (element.getAttributeNS(namespace, "id") == "@+id/$id") return element
        }
        throw AssertionError("Missing view id $id")
    }

    // 1. Committed accent synchronization
    @Test fun projectionCarriesTheCommittedSavedAccentForEveryImplementedAccent() {
        for (accent in Accent.entries) assertEquals(accent, ready(saved(accent)).accent)
        assertEquals(Accent.sapphire, Appearance().accent)
        assertEquals(setOf(Accent.sapphire, Accent.jade, Accent.amethyst), Accent.entries.toSet())
    }

    @Test fun projectionIgnoresSavedThemeAndCardStyleBecauseWidgetFollowsSystemMode() {
        val base = saved(Accent.jade)
        val styled = base.copy(data = base.data.copy(appearance = Appearance(Theme.light, Accent.jade, CardStyle.Horizon)))
        assertEquals(ready(base), ready(styled).copy(revision = ready(base).revision))
    }

    // 2. Light/dark accent values come from the app theme, not a duplicate palette
    @Test fun accentPairsEqualTheAppAppearanceThemePrimaryColors() {
        val expected = mapOf(
            Accent.sapphire to (0xFF285C9E.toInt() to 0xFFA9C7FF.toInt()),
            Accent.jade to (0xFF23684C.toInt() to 0xFF9DD9BD.toInt()),
            Accent.amethyst to (0xFF67449A.toInt() to 0xFFD2BCFF.toInt()),
        )
        for ((accent, pair) in expected) {
            assertEquals(QuickCardWidgetColorPair(pair.first, pair.second), QuickCardWidgetColors.accent(accent))
        }
        assertEquals(3, Accent.entries.map(QuickCardWidgetColors::accent).toSet().size)
    }

    @Test fun widgetColorsSourceDerivesAccentFromThemeWithoutHardCodedPalette() {
        val source = File("src/main/java/com/mackseoul/vcardnative/ui/widget/QuickCardWidgetColors.kt").readText()
        assertTrue(source.contains("appearanceColorScheme("))
        assertFalse(Regex("0x[0-9A-Fa-f]{8}").containsMatchIn(source))
    }

    // 3, 4. Draft, Cancel and Restore Default isolation
    @Test fun unsavedDraftCancelAndRestoreDefaultNeverChangeTheWidgetProjection() {
        val committed = saved(Accent.jade)
        val editing = ProfileScreenState(loading = false, destination = ProfileDestination.Appearance,
            saved = committed, appearanceDraft = committed.data.appearance)
        val drafted = editing.copy(appearanceDraft = Appearance(accent = Accent.amethyst))
        val restored = drafted.restoreDefaultAppearanceDraft()
        val cancelled = drafted.cancelAppearanceDraft()
        assertEquals(Appearance(), restored.appearanceDraft)
        for (state in listOf(editing, drafted, restored, cancelled)) {
            assertEquals(Accent.jade, ready(state.saved!!).accent)
        }
    }

    // 5. Refresh only after successful Save, carrying the new accent
    @Test fun successfulAppearanceSaveNotifiesWidgetRefreshWithNewAccentAndFailuresDoNot() {
        lateinit var repository: ProfileRepository
        val projections = mutableListOf<QuickCardWidgetState>()
        repository = ProfileRepository(files()) { projections += QuickCardWidgetProjection.from(repository.load()) }
        val first = repository.save(draft(), null)
        assertEquals(Accent.sapphire, (projections.single() as QuickCardWidgetState.Ready).accent)
        assertThrows(ValidationException::class.java) {
            repository.save(first.data.toDraft().copy(values = emptyMap(), appearance = Appearance(accent = Accent.jade)),
                first.token)
        }
        assertEquals(1, projections.size)
        repository.save(first.data.toDraft().copy(appearance = Appearance(accent = Accent.amethyst)), first.token)
        assertEquals(2, projections.size)
        assertEquals(Accent.amethyst, (projections.last() as QuickCardWidgetState.Ready).accent)
    }

    @Test fun emptyAndUnavailableStatesKeepDefaultAccentPath() {
        val source = File("src/main/java/com/mackseoul/vcardnative/platform/widget/QuickCardWidgetProvider.kt").readText()
        assertTrue(source.contains("(state as? QuickCardWidgetState.Ready)?.accent ?: Appearance().accent"))
    }

    // 6. Four size classes; V1.2.1 adds height-tier entries that reuse the three layouts
    @Test fun fourSizeClassesAndBreakpointsAreUnchanged() {
        assertEquals(listOf("VeryNarrow", "Compact", "Standard", "Expanded"), QuickCardWidgetSize.entries.map { it.name })
        assertEquals(listOf(24, 40, 56, 80), QuickCardWidgetSize.entries.map { it.photoEdgeDp })
        val entries = QuickCardWidgetLayoutPolicy.breakpoints
        assertEquals(Triple(QuickCardWidgetSize.VeryNarrow, 120f, 48f), entries.first().let { Triple(it.size, it.widthDp, it.heightDp) })
        assertEquals(Triple(QuickCardWidgetSize.Compact, 180f, 48f), entries[1].let { Triple(it.size, it.widthDp, it.heightDp) })
        assertEquals(1, entries.count { it.size == QuickCardWidgetSize.VeryNarrow })
        assertEquals(listOf(180f, 240f), entries.filter { it.size == QuickCardWidgetSize.Compact }.map { it.widthDp })
        assertTrue(entries.filter { it.size == QuickCardWidgetSize.Expanded }.all { it.widthDp == 260f && it.heightDp >= 120f })
        assertTrue(entries.size <= 10)
    }

    @Test fun standardLayoutBodyHasAnIdForMenuRowPadding() {
        layout("standard").byId("quick_card_body")
        val source = File("src/main/java/com/mackseoul/vcardnative/platform/widget/QuickCardWidgetProvider.kt").readText()
        assertTrue(source.contains("if (breakpoint.reservesMenuRow)"))
        assertTrue(source.contains("views.setViewPadding(R.id.quick_card_body"))
    }

    @Test fun titleInitialsAreOnlyAppliedToReadyStatesInSingleRowEntriesNarrowerThanTheFullTitleEntry() {
        val source = File("src/main/java/com/mackseoul/vcardnative/platform/widget/QuickCardWidgetProvider.kt").readText()
        assertTrue(source.contains("breakpoint.showsTitleInitials && state is QuickCardWidgetState.Ready"))
        assertEquals(1, Regex("titleInitials\\(").findAll(source).count())
        assertTrue(source.contains("listOfNotNull(accessibleName, title)"))
    }

    // 7. VeryNarrow duplicate-initial removal
    @Test fun veryNarrowHidesNameTextThatWouldRepeatAvatarInitials() {
        val state = ready(saved(Accent.sapphire, "Ada Lovelace"))
        assertNull(state.visibleIdentityText(QuickCardWidgetSize.VeryNarrow, photoShown = false))
        assertEquals("AL", state.visibleIdentityText(QuickCardWidgetSize.VeryNarrow, photoShown = true))
        assertEquals("Ada", state.visibleIdentityText(QuickCardWidgetSize.Compact, photoShown = false))
        assertEquals("Ada Lovelace", state.visibleIdentityText(QuickCardWidgetSize.Standard, photoShown = false))
        assertEquals("Ada Lovelace", state.visibleIdentityText(QuickCardWidgetSize.Expanded, photoShown = true))
        assertEquals("Ada Lovelace", state.displayName)
    }

    // 8. Compact title minimum 12 sp and improved padding inside the 48 dp height
    @Test fun compactLayoutUses12SpTitleAndBalancedPadding() {
        val compact = layout("compact")
        assertEquals("12sp", compact.byId("quick_card_title").getAttributeNS(namespace, "textSize"))
        assertEquals("14sp", compact.byId("quick_card_name").getAttributeNS(namespace, "textSize"))
        val body = compact.getElementsByTagName("LinearLayout").item(0) as Element
        assertEquals("4dp", body.getAttributeNS(namespace, "paddingTop"))
        assertEquals("4dp", body.getAttributeNS(namespace, "paddingBottom"))
        assertEquals("center_vertical", body.getAttributeNS(namespace, "gravity"))
    }

    // 9. Standard menu positioning and centered content
    @Test fun standardMenuIsTopRightAndContentIsSymmetricallyPadded() {
        val standard = layout("standard")
        val menu = standard.byId("quick_card_menu")
        assertEquals("top|end", menu.getAttributeNS(namespace, "layout_gravity"))
        assertEquals("48dp", menu.getAttributeNS(namespace, "layout_width"))
        val body = standard.getElementsByTagName("LinearLayout").item(0) as Element
        assertEquals(body.getAttributeNS(namespace, "paddingStart"), body.getAttributeNS(namespace, "paddingEnd"))
        assertEquals("", body.getAttributeNS(namespace, "layout_marginEnd"))
        assertEquals("4dp", standard.byId("quick_card_title").getAttributeNS(namespace, "layout_marginTop"))
        val expanded = layout("expanded")
        assertEquals("end|center_vertical", expanded.byId("quick_card_menu").getAttributeNS(namespace, "layout_gravity"))
        assertEquals("4dp", expanded.byId("quick_card_title").getAttributeNS(namespace, "layout_marginTop"))
    }

    // 10. Adaptive text limits across font scales
    @Test fun adaptiveTextLimitsReduceLinesAndHideCompactTitleAtLargeFontScales() {
        for (scale in listOf(1.0f, 1.15f, 1.29f)) {
            assertEquals(QuickCardWidgetTextLimits(2, 2, true), QuickCardWidgetTextPolicy.limits(QuickCardWidgetSize.Standard, scale))
            assertEquals(QuickCardWidgetTextLimits(2, 2, true), QuickCardWidgetTextPolicy.limits(QuickCardWidgetSize.Expanded, scale))
        }
        for (scale in listOf(1.3f, 1.5f, 2.0f)) {
            assertEquals(QuickCardWidgetTextLimits(1, 1, true), QuickCardWidgetTextPolicy.limits(QuickCardWidgetSize.Standard, scale))
            assertEquals(QuickCardWidgetTextLimits(1, 1, true), QuickCardWidgetTextPolicy.limits(QuickCardWidgetSize.Expanded, scale))
        }
        for (size in listOf(QuickCardWidgetSize.VeryNarrow, QuickCardWidgetSize.Compact)) {
            assertTrue(QuickCardWidgetTextPolicy.limits(size, 1.0f).showTitle)
            assertTrue(QuickCardWidgetTextPolicy.limits(size, 1.3f).showTitle)
            assertFalse(QuickCardWidgetTextPolicy.limits(size, 1.5f).showTitle)
            assertFalse(QuickCardWidgetTextPolicy.limits(size, 2.0f).showTitle)
        }
        for (size in QuickCardWidgetSize.entries) for (scale in listOf(0.85f, 1.0f, 1.3f, 1.5f, 2.0f)) {
            assertTrue(QuickCardWidgetTextPolicy.limits(size, scale).nameLines >= 1)
        }
    }

    // 11, 12. Preserved view IDs, PendingIntent identities and navigation
    @Test fun allLayoutsKeepEveryExistingViewId() {
        val ids = listOf("quick_card_root", "quick_card_avatar", "quick_card_photo", "quick_card_initials",
            "quick_card_person", "quick_card_name", "quick_card_title", "quick_card_menu")
        for (variant in listOf("compact", "standard", "expanded")) {
            val document = layout(variant)
            ids.forEach { document.byId(it) }
            assertEquals("48dp", document.byId("quick_card_menu").getAttributeNS(namespace, "layout_height"))
        }
    }

    @Test fun providerUsesLightDarkPairsAndKeepsPendingIntentContracts() {
        val source = File("src/main/java/com/mackseoul/vcardnative/platform/widget/QuickCardWidgetProvider.kt").readText()
        assertFalse(source.contains("setTextColor(R.id"))
        assertFalse(source.contains("getColor(R.color.quick_card_accent)"))
        for (id in listOf("quick_card_name", "quick_card_title", "quick_card_initials")) {
            assertTrue(source.contains("setColorInt(R.id.$id, \"setTextColor\""))
        }
        for (id in listOf("quick_card_person", "quick_card_menu")) {
            assertTrue(source.contains("setColorInt(R.id.$id, \"setColorFilter\", accent.light, accent.dark)"))
        }
        assertTrue(source.contains("action = MainActivity.ACTION_OPEN_MY_CARD_FROM_WIDGET"))
        assertTrue(source.contains("Uri.parse(QuickCardWidgetClickIdentity.body(widgetId))"))
        assertTrue(source.contains("Uri.parse(QuickCardWidgetClickIdentity.menu(widgetId))"))
        assertTrue(source.contains("Intent(context, QuickCardWidgetMenuActivity::class.java)"))
        assertEquals(2, Regex("PendingIntent\\.FLAG_UPDATE_CURRENT or PendingIntent\\.FLAG_IMMUTABLE").findAll(source).count())
        assertTrue(source.contains("views.setOnClickPendingIntent(R.id.quick_card_root"))
        assertTrue(source.contains("views.setOnClickPendingIntent(R.id.quick_card_menu"))
        assertTrue(source.contains("class QuickCardWidgetProvider : AppWidgetProvider()"))
        assertTrue(source.contains("setContentDescription(R.id.quick_card_root"))
    }
}
