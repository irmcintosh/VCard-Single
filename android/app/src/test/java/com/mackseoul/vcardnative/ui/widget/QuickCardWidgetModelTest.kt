package com.mackseoul.vcardnative.ui.widget

import com.mackseoul.vcardnative.data.profile.ProfileRead
import com.mackseoul.vcardnative.data.profile.RevisionToken
import com.mackseoul.vcardnative.data.profile.SavedProfile
import com.mackseoul.vcardnative.domain.profile.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class QuickCardWidgetModelTest {
    private fun saved(name: String = "Saved Person", title: String? = "Engineer", photo: Boolean = true): SavedProfile {
        val values = mapOf(ProfileField.FullName to name) +
            (title?.let { mapOf(ProfileField.ProfessionalTitle to it) } ?: emptyMap())
        return SavedProfile(AppData(profile = Profile(UUID.randomUUID(), values, ProfileDraft.empty().visibility,
            profileImage = if (photo) ImageAsset(UUID.randomUUID(), "image/png", 10, 64, 64, "0".repeat(64)) else null),
            metadata = Metadata("2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")),
            RevisionToken(UUID.randomUUID().toString(), 1))
    }

    @Test fun committedProjectionContainsNameVisibleTitleAndPhotoDescriptorOnly() {
        val saved = saved()
        val projected = QuickCardWidgetProjection.from(ProfileRead.Ready(saved)) as QuickCardWidgetState.Ready
        assertEquals("Saved Person", projected.displayName)
        assertEquals("Engineer", projected.professionalTitle)
        assertEquals(saved.data.profile.profileImage, projected.photo)
        assertEquals("SP", projected.initials)
        assertEquals(saved.token, projected.revision)
    }

    @Test fun hiddenPhotoAndTitleRemainHiddenRegardlessOfExportSettings() {
        val saved = saved()
        val hidden = saved.copy(data = saved.data.copy(profile = saved.data.profile.copy(
            visibility = saved.data.profile.visibility + mapOf(
                ProfileField.ProfileImage to Visibility(false, null),
                ProfileField.ProfessionalTitle to Visibility(false, true)))))
        val projected = QuickCardWidgetProjection.from(ProfileRead.Ready(hidden)) as QuickCardWidgetState.Ready
        assertNull(projected.photo)
        assertNull(projected.professionalTitle)
        assertEquals("SP", projected.initials)
        assertFalse(projected.usesGenericPerson)
        for (size in QuickCardWidgetSize.entries) {
            assertNull(projected.photo)
            assertNull(projected.professionalTitle)
            assertTrue(projected.identityText(size).isNotBlank())
        }
    }

    @Test fun emptyAndRecoveryStatesAreDistinctAndRetainRecoveryInformation() {
        assertEquals(QuickCardWidgetState.Empty, QuickCardWidgetProjection.from(ProfileRead.Empty))
        val unavailable = QuickCardWidgetProjection.from(ProfileRead.Unavailable("Corrupt", "recovery", "blocked"))
        assertEquals(QuickCardWidgetState.Unavailable("Corrupt", "recovery", "blocked"), unavailable)
    }

    @Test fun unicodeInitialsPreserveStoredNameAndSupplementaryCodePoints() {
        assertEquals("AA", QuickCardWidgetProjection.initials("Ada Adams"))
        assertEquals("SP", QuickCardWidgetProjection.initials("  Saved\u00A0Middle\u2003Person "))
        assertEquals("王小", QuickCardWidgetProjection.initials("王 小明"))
        assertEquals("😀𐐀", QuickCardWidgetProjection.initials("😀 Person 𐐀name"))
        assertEquals("é", QuickCardWidgetProjection.initials("éclair"))
        assertNull(QuickCardWidgetProjection.initials(" \u00A0\t"))
        assertNull(QuickCardWidgetProjection.initials("\uD800"))
        val name = "Zoë Person"
        val projected = QuickCardWidgetProjection.from(ProfileRead.Ready(saved(name))) as QuickCardWidgetState.Ready
        assertEquals(name, projected.displayName)
    }

    @Test fun missingNameTitleAndPhotoUseGenericPersonWithoutInventingIdentity() {
        val projected = QuickCardWidgetProjection.from(ProfileRead.Ready(saved("", null, false))) as QuickCardWidgetState.Ready
        assertEquals("", projected.displayName)
        assertNull(projected.initials)
        assertNull(projected.professionalTitle)
        assertTrue(projected.usesGenericPerson)
        QuickCardWidgetSize.entries.forEach { assertEquals("", projected.identityText(it)) }
    }

    @Test fun contentPoliciesUseCellTargetsAndLineLimitsNotUniversalPixelDimensions() {
        assertEquals(2, QuickCardWidgetSize.Compact.targetColumns)
        assertEquals(1, QuickCardWidgetSize.Compact.targetRows)
        assertEquals(1, QuickCardWidgetSize.Compact.maxNameLines)
        assertEquals(1, QuickCardWidgetSize.Compact.maxTitleLines)
        assertEquals(2, QuickCardWidgetSize.Standard.targetColumns)
        assertEquals(2, QuickCardWidgetSize.Standard.targetRows)
        assertEquals(4, QuickCardWidgetSize.Expanded.targetColumns)
        assertEquals(2, QuickCardWidgetSize.Expanded.targetRows)
        for (size in listOf(QuickCardWidgetSize.Standard, QuickCardWidgetSize.Expanded)) {
            assertEquals(2, size.maxNameLines)
            assertEquals(2, size.maxTitleLines)
        }
        assertTrue(QuickCardWidgetSize.entries.all { it.photoEdgeDp <= 80 })
    }

    @Test fun identityHierarchyUsesSavedFirstNameAndFullNameAtAppropriateSizes() {
        val saved = saved("Jean Luc Picard").let {
            it.copy(data = it.data.copy(profile = it.data.profile.copy(values = it.data.profile.values +
                (ProfileField.FirstName to "Jean Luc"))))
        }
        val state = QuickCardWidgetProjection.from(ProfileRead.Ready(saved)) as QuickCardWidgetState.Ready
        assertEquals("JP", state.identityText(QuickCardWidgetSize.VeryNarrow))
        assertEquals("Jean Luc", state.identityText(QuickCardWidgetSize.Compact))
        assertEquals("Jean Luc Picard", state.identityText(QuickCardWidgetSize.Standard))
        assertEquals("Jean Luc Picard", state.identityText(QuickCardWidgetSize.Expanded))
        assertEquals(saved.data.profile.profileImage, state.photo)
        assertEquals("Engineer", state.professionalTitle)
    }

    @Test fun firstNameFallbackIsUnicodeSafeAndDoesNotChangeStoredIdentity() {
        assertEquals("😀", QuickCardWidgetProjection.firstName("  😀\u2003Person"))
        assertEquals("王小明", QuickCardWidgetProjection.firstName("王小明"))
        assertEquals("Zoë", QuickCardWidgetProjection.firstName("Zoë Long Name", " "))
        assertNull(QuickCardWidgetProjection.firstName(" \u00A0"))
        assertNull(QuickCardWidgetProjection.firstName("\uD800"))
        val longName = "𐐀".repeat(200) + " " + "Family".repeat(30)
        val state = QuickCardWidgetProjection.from(ProfileRead.Ready(saved(longName))) as QuickCardWidgetState.Ready
        assertEquals("𐐀".repeat(200), state.identityText(QuickCardWidgetSize.Compact))
        assertEquals(longName, state.displayName)
        assertEquals("𐐀F", state.identityText(QuickCardWidgetSize.VeryNarrow))
    }

    private fun titleInitials(title: String) = QuickCardWidgetProjection.titleInitials(title)

    @Test fun titleInitialsIncludeConnectingWordsAndHyphenatedParts() {
        assertEquals("SE", titleInitials("Solution Engineer"))
        assertEquals("SSE", titleInitials("Senior Solution Engineer"))
        assertEquals("SGSE", titleInitials("Senior Geospatial Solution Engineer"))
        assertEquals("CTO", titleInitials("Chief Technology Officer"))
        assertEquals("DOE", titleInitials("Director of Engineering"))
        assertEquals("VP", titleInitials("Vice-President"))
        assertEquals("VP", titleInitials("Vice\u2011President"))
        assertEquals("DE", titleInitials("Director/Engineering"))
        assertEquals("UUD", titleInitials("UX/UI Designer"))
        assertEquals("L2E", titleInitials("Level 2 Engineer"))
        assertEquals("SE", titleInitials("  solution \u00A0 engineer  "))
    }

    @Test fun titleInitialsKeepAcronymsAndSkipParentheticalDuplicates() {
        assertEquals("CTO", titleInitials("CTO"))
        assertEquals("CTO", titleInitials("Chief Technology Officer (CTO)"))
        assertEquals("SE", titleInitials("Software Engineer (SWE)"))
        assertEquals("CFC", titleInitials("Co-Founder & CEO"))
        assertEquals("C", titleInitials("Cto"))
        assertEquals("D", titleInitials("DEVOPS"))
    }

    @Test fun titleInitialsHandleBlankSingleWordAndPunctuationOnlyTitles() {
        assertNull(titleInitials(""))
        assertNull(titleInitials(" \u00A0\u2003 "))
        assertNull(titleInitials("-"))
        assertNull(titleInitials("&/ - ... ()"))
        assertNull(titleInitials("😀"))
        assertEquals("E", titleInitials("Engineer"))
        assertEquals("E", titleInitials("engineer"))
        assertEquals("E", titleInitials("😀 Engineer"))
        assertEquals("E", titleInitials("\uD800 Engineer"))
    }

    @Test fun titleInitialsAreUnicodeSafeAndFallBackForUnspacedScripts() {
        assertEquals("\uD801\uDC00E", titleInitials("\uD801\uDC28 Engineer"))
        assertEquals("E\u0301D", titleInitials("e\u0301cole director"))
        assertEquals("\u0421\u0418", titleInitials("\u0421\u0442\u0430\u0440\u0448\u0438\u0439 \u0438\u043d\u0436\u0435\u043d\u0435\u0440"))
        assertEquals("소엔", titleInitials("소프트웨어 엔지니어"))
        assertNull(titleInitials("工程师"))
        assertNull(titleInitials("ソフトウェア エンジニア"))
        assertNull(titleInitials("วิศวกร ซอฟต์แวร์"))
        assertNull(titleInitials("Senior 工程师"))
    }

    @Test fun titleInitialsAreBoundedWithoutBreakingCodePoints() {
        assertEquals("SPSE", titleInitials("Senior Principal Staff Software Engineer"))
        assertEquals("ABE", QuickCardWidgetProjection.titleInitials("A B C D E", maxLength = 3))
        assertThrows(IllegalArgumentException::class.java) { QuickCardWidgetProjection.titleInitials("A B", maxLength = 1) }
        val long = "\uD801\uDC28".repeat(2) + " " + "word ".repeat(500)
        val result = requireNotNull(titleInitials(long))
        assertEquals(QuickCardWidgetProjection.TITLE_INITIALS_MAX_LENGTH, result.codePointCount(0, result.length))
        assertEquals("\uD801\uDC00WWW", result)
    }

    @Test fun titleInitialsNeverChangeTheStoredTitle() {
        val title = "Director of Engineering"
        val state = QuickCardWidgetProjection.from(ProfileRead.Ready(saved(title = title))) as QuickCardWidgetState.Ready
        assertEquals("DOE", titleInitials(requireNotNull(state.professionalTitle)))
        assertEquals(title, state.professionalTitle)
    }
}