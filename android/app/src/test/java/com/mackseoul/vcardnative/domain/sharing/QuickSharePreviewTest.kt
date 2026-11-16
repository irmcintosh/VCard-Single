package com.mackseoul.vcardnative.domain.sharing

import com.mackseoul.vcardnative.data.sharing.QuickShareQrGenerator
import com.mackseoul.vcardnative.data.sharing.QuickShareQrResult
import com.mackseoul.vcardnative.domain.profile.AsciiHostValidator
import com.mackseoul.vcardnative.domain.profile.Profile
import com.mackseoul.vcardnative.domain.profile.ProfileDraft
import com.mackseoul.vcardnative.domain.profile.ProfileField
import com.mackseoul.vcardnative.domain.profile.ProfileValidator
import com.mackseoul.vcardnative.domain.profile.Visibility
import com.mackseoul.vcardnative.domain.profile.structuredNameFields
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class QuickSharePreviewTest {
    private val savedName = "Saved Person"
    private val fullValues = mapOf(
        ProfileField.FullName to savedName,
        ProfileField.FirstName to "PRIVATE_FIRST_NAME_MARKER",
        ProfileField.LastName to "PRIVATE_LAST_NAME_MARKER",
        ProfileField.ProfessionalTitle to "Engineer",
        ProfileField.Organization to "Example Studio",
        ProfileField.Phone to "+1 202 555 0142",
        ProfileField.Email to "person@example.invalid",
        ProfileField.Website to "https://example.invalid/PRIVATE_WEBSITE_MARKER",
        ProfileField.Location to "PRIVATE_LOCATION_MARKER",
        ProfileField.LinkedIn to "https://example.invalid/PRIVATE_SOCIAL_MARKER",
    )

    @Before
    fun configureHosts() {
        ProfileValidator.hostValidator = AsciiHostValidator
    }

    private fun profile(
        values: Map<ProfileField, String>,
        phoneRegion: String = "US",
        includeCountryCode: Boolean = true,
    ): Profile {
        val visibility = ProfileDraft.empty().visibility.mapValues { (field, current) ->
            if (field == ProfileField.FullName || field in structuredNameFields || current.includeInContactExport == null) current
            else Visibility(showOnCard = true, includeInContactExport = true)
        }
        val draft = ProfileDraft.empty().copy(
            values = values,
            visibility = visibility,
            phoneRegion = phoneRegion,
            includeCountryCodeWhenSharing = includeCountryCode,
        )
        val normalized = requireNotNull(ProfileValidator.validate(draft).normalized) { "Test profile must be valid." }
        return Profile(
            profileId = UUID.randomUUID(),
            values = normalized.values,
            visibility = normalized.visibility,
            phoneRegion = normalized.phoneRegion,
            includeCountryCodeWhenSharing = normalized.includeCountryCodeWhenSharing,
            phoneNumberE164 = normalized.phoneNumberE164,
        )
    }

    private fun rawProfile(values: Map<ProfileField, String>, phoneNumberE164: String? = null) = Profile(
        profileId = UUID.randomUUID(),
        values = values,
        visibility = ProfileDraft.empty().visibility,
        phoneRegion = "US",
        includeCountryCodeWhenSharing = true,
        phoneNumberE164 = phoneNumberE164,
    )

    private fun subsets(): List<Set<ProfileField>> {
        val fields = QuickShareSelection.selectableFields
        return (0 until (1 shl fields.size)).map { mask ->
            fields.filterIndexedTo(linkedSetOf()) { index, _ -> mask and (1 shl index) != 0 }
        }
    }

    private fun ready(preview: QuickSharePreview): QuickSharePreview.Ready {
        assertTrue("Expected Ready but was $preview", preview is QuickSharePreview.Ready)
        return preview as QuickSharePreview.Ready
    }

    private fun phoneRow(preview: QuickSharePreview) =
        ready(preview).rows.single { it.field == ProfileField.Phone }.value

    @Test
    fun allSixteenOptionalFieldCombinationsMatchTheActualExportRowsExactly() {
        val saved = profile(fullValues)
        val combinations = subsets()
        assertEquals(16, combinations.size)

        for (selection in combinations) {
            val preview = QuickSharePreview.from(saved, selection)
            if (selection.isEmpty()) {
                assertEquals(QuickSharePreview.NoSelection, preview)
                assertThrows(ContactExportException::class.java) { QuickShareContactExport.from(saved, selection) }
                continue
            }
            val export = QuickShareContactExport.from(saved, selection)
            val ready = ready(preview)
            val exportedOptional = export.rows.filter { it.fields != listOf(ProfileField.FullName) }

            assertEquals("Full Name for $selection", export.rows.first().value, ready.fullName)
            assertEquals(listOf(ProfileField.FullName), export.rows.first().fields)
            assertEquals(
                "Rows for $selection",
                exportedOptional.map { it.fields.single() to it.value },
                ready.rows.map { it.field to it.value },
            )
            assertEquals(selection, ready.rows.map { it.field }.toSet())
            assertEquals(selection.size, ready.rows.size)
        }
    }

    @Test
    fun previewUsesExportOrderWithOrganizationBeforeProfessionalTitle() {
        val preview = ready(QuickSharePreview.from(profile(fullValues), QuickShareSelection.selectableFields.toSet()))

        assertEquals(
            listOf(ProfileField.Organization, ProfileField.ProfessionalTitle, ProfileField.Phone, ProfileField.Email),
            preview.rows.map { it.field },
        )
    }

    @Test
    fun ukPhoneShowsTheExportedInternationalAndNationalFormatsNotRawText() {
        val values = mapOf(ProfileField.FullName to savedName, ProfileField.Phone to "020 7031 3000")
        val international = profile(values, phoneRegion = "GB", includeCountryCode = true)
        val national = profile(values, phoneRegion = "GB", includeCountryCode = false)
        val selection = setOf(ProfileField.Phone)

        assertEquals("+44 20 7031 3000", phoneRow(QuickSharePreview.from(international, selection)))
        assertNotEquals(international.values[ProfileField.Phone], phoneRow(QuickSharePreview.from(international, selection)))
        assertEquals("020 7031 3000", phoneRow(QuickSharePreview.from(national, selection)))
        for (saved in listOf(international, national)) {
            assertEquals(
                QuickShareContactExport.from(saved, selection).rows.single { ProfileField.Phone in it.fields }.value,
                phoneRow(QuickSharePreview.from(saved, selection)),
            )
        }
    }

    @Test
    fun usPhoneShowsTheExportedFormat() {
        val saved = profile(mapOf(ProfileField.FullName to savedName, ProfileField.Phone to "+1 202 555 0142"))
        val preview = QuickSharePreview.from(saved, setOf(ProfileField.Phone))

        assertEquals("+1 202-555-0142", phoneRow(preview))
        assertTrue(String(QuickShareContactExport.from(saved, setOf(ProfileField.Phone)).bytes).contains("TEL:+1 202-555-0142\r\n"))
    }

    @Test
    fun unparseableLegacyPhoneShowsTheExportedDigitsOnlyValue() {
        val saved = rawProfile(mapOf(ProfileField.FullName to savedName, ProfileField.Phone to "(555) 0100"))
        val export = QuickShareContactExport.from(saved, setOf(ProfileField.Phone))
        val preview = QuickSharePreview.from(saved, setOf(ProfileField.Phone))

        assertEquals("5550100", export.rows.single { ProfileField.Phone in it.fields }.value)
        assertEquals("5550100", phoneRow(preview))
        assertNotEquals(saved.values[ProfileField.Phone], phoneRow(preview))
    }

    @Test
    fun fullNameIsAlwaysIncludedAndNeverCountedAsAnOptionalRow() {
        for (selection in subsets().filter { it.isNotEmpty() }) {
            val preview = ready(QuickSharePreview.from(profile(fullValues), selection))
            assertEquals(savedName, preview.fullName)
            assertFalse(preview.rows.any { it.field == ProfileField.FullName })
        }
        val withFullName = ready(QuickSharePreview.from(profile(fullValues), setOf(ProfileField.FullName, ProfileField.Email)))
        assertEquals(listOf(ProfileField.Email), withFullName.rows.map { it.field })
    }

    @Test
    fun unselectedAndNonOptionalValuesNeverAppearInThePreview() {
        val saved = profile(fullValues)
        for (selection in subsets().filter { it.isNotEmpty() }) {
            val preview = ready(QuickSharePreview.from(saved, selection))
            val shown = preview.rows.joinToString("|") { it.value } + "|" + preview.fullName
            assertFalse(shown.contains("PRIVATE_"))
            QuickShareSelection.selectableFields.filter { it !in selection }.forEach { unselected ->
                assertFalse("$unselected leaked for $selection", preview.rows.any { it.field == unselected })
            }
        }
        val disallowed = QuickSharePreview.from(saved, setOf(ProfileField.Website, ProfileField.LinkedIn, ProfileField.Location))
        assertEquals(QuickSharePreview.NoSelection, disallowed)
    }

    @Test
    fun unavailableFieldsAreListedDisabledAndCannotBecomeSelected() {
        val values = mapOf(
            ProfileField.FullName to savedName,
            ProfileField.ProfessionalTitle to "Engineer",
            ProfileField.Organization to "   ",
            ProfileField.Email to "person@example.invalid",
        )
        val everything = QuickShareSelection.selectableFields.toSet()
        val choices = QuickShareFieldChoices.from(values, everything)

        assertEquals(QuickShareSelection.selectableFields, choices.map { it.field })
        assertEquals(listOf(true, false, false, true), choices.map { it.available })
        assertEquals(listOf(true, false, false, true), choices.map { it.selected })
        assertNull(choices.single { it.field == ProfileField.Organization }.value)
        assertNull(choices.single { it.field == ProfileField.Phone }.value)
        assertEquals(2, QuickShareFieldChoices.selectedCount(values, everything))
        assertEquals(4, QuickShareFieldChoices.optionalFieldCount)
        assertEquals(
            setOf(ProfileField.ProfessionalTitle, ProfileField.Email),
            QuickShareSelection.restoreSelection(values, everything.mapTo(linkedSetOf()) { it.key }),
        )
        assertEquals(QuickShareSelection.availableFields(values), choices.filter { it.available }.map { it.field })
    }

    @Test
    fun selectionCountReflectsSavedValuesForEveryCombination() {
        for (selection in subsets()) {
            assertEquals(selection.size, QuickShareFieldChoices.selectedCount(fullValues, selection))
        }
        assertEquals(0, QuickShareFieldChoices.selectedCount(mapOf(ProfileField.FullName to savedName), QuickShareSelection.selectableFields.toSet()))
    }

    @Test
    fun missingFullNameAndEmptySelectionsAreSafeStatesWithoutFabricatedRows() {
        assertEquals(
            QuickSharePreview.MissingFullName,
            QuickSharePreview.from(rawProfile(mapOf(ProfileField.Email to "person@example.invalid")), setOf(ProfileField.Email)),
        )
        assertEquals(
            QuickSharePreview.MissingFullName,
            QuickSharePreview.from(rawProfile(mapOf(ProfileField.FullName to "  ", ProfileField.Email to "a@example.invalid")), setOf(ProfileField.Email)),
        )
        val saved = profile(mapOf(ProfileField.FullName to savedName, ProfileField.Email to "person@example.invalid"))
        assertEquals(QuickSharePreview.NoSelection, QuickSharePreview.from(saved, emptySet()))
        assertEquals(QuickSharePreview.NoSelection, QuickSharePreview.from(saved, setOf(ProfileField.Phone)))
        assertEquals(listOf(ProfileField.Email), ready(QuickSharePreview.from(saved, setOf(ProfileField.Email, ProfileField.Phone))).rows.map { it.field })
    }

    @Test
    fun invalidSavedDataProducesAWarningStateThatKeepsTheSelectionAndMatchesExportFailure() {
        val invalid = rawProfile(
            mapOf(
                ProfileField.FullName to savedName,
                ProfileField.Organization to "Example Studio",
                ProfileField.Email to "not an email address",
            ),
        )
        val selection = setOf(ProfileField.Email, ProfileField.Organization)

        val preview = QuickSharePreview.from(invalid, selection)

        assertTrue(preview is QuickSharePreview.Unavailable)
        preview as QuickSharePreview.Unavailable
        assertEquals(listOf(ProfileField.Organization, ProfileField.Email), preview.selectedFields)
        val failure = assertThrows(ContactExportException::class.java) { QuickShareContactExport.from(invalid, selection) }
        assertEquals(failure.message, preview.reason)
        assertTrue(QuickShareQrGenerator.generate(invalid, selection) is QuickShareQrResult.ExportUnavailable)
    }

    @Test
    fun previewDoesNotMutateTheProfileOrChangeExportBytes() {
        val saved = profile(fullValues)
        val selection = setOf(ProfileField.Phone, ProfileField.Organization)
        val before = QuickShareContactExport.from(saved, selection).bytes
        val values = saved.values.toMap()
        val visibility = saved.visibility.toMap()

        repeat(3) { QuickSharePreview.from(saved, selection) }

        assertEquals(values, saved.values)
        assertEquals(visibility, saved.visibility)
        assertArrayEquals(before, QuickShareContactExport.from(saved, selection).bytes)
        assertEquals(QuickSharePreview.from(saved, selection), QuickSharePreview.from(saved, selection))
    }

    @Test
    fun previewMatchesTheQrAndSharesheetExportRows() {
        val saved = profile(fullValues)
        for (selection in subsets().filter { it.isNotEmpty() }) {
            val qr = QuickShareQrGenerator.generate(saved, selection)
            val export = when (qr) {
                is QuickShareQrResult.QrAvailable -> qr.export
                is QuickShareQrResult.QrUnavailable -> qr.export
                is QuickShareQrResult.ExportUnavailable -> throw AssertionError("Unexpected export failure for $selection")
            }
            val preview = ready(QuickSharePreview.from(saved, selection))
            assertEquals(
                export.rows.drop(1).map { it.fields.single() to it.value },
                preview.rows.map { it.field to it.value },
            )
            assertArrayEquals(QuickShareContactExport.from(saved, selection).bytes, export.bytes)
        }
    }
}
