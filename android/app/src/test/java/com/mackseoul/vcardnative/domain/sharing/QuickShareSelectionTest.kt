package com.mackseoul.vcardnative.domain.sharing

import com.mackseoul.vcardnative.domain.profile.Profile
import com.mackseoul.vcardnative.domain.profile.ProfileDraft
import com.mackseoul.vcardnative.domain.profile.ProfileField
import com.mackseoul.vcardnative.domain.profile.Visibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class QuickShareSelectionTest {
    private val approvedFields = listOf(
        ProfileField.ProfessionalTitle,
        ProfileField.Organization,
        ProfileField.Phone,
        ProfileField.Email,
    )

    @Test
    fun exposesExactlyFourAdditionalFieldsInApprovedOrder() {
        assertEquals(approvedFields, QuickShareSelection.selectableFields)
        assertFalse(ProfileField.FullName in QuickShareSelection.selectableFields)
        assertEquals(4, QuickShareSelection.selectableFields.size)
    }

    @Test
    fun availableFieldsContainsOnlyPopulatedAdditionalValues() {
        val values = mapOf(
            ProfileField.FullName to "Saved Person",
            ProfileField.ProfessionalTitle to "Engineer",
            ProfileField.Organization to " \t ",
            ProfileField.Phone to "+1 202 555 0142",
            ProfileField.Website to "https://example.invalid/person",
            ProfileField.LinkedIn to "https://example.invalid/person",
            ProfileField.MiddleName to "Marie",
        )

        assertEquals(
            listOf(ProfileField.ProfessionalTitle, ProfileField.Phone),
            QuickShareSelection.availableFields(values),
        )
    }

    @Test
    fun restoreSelectionUsesStableKeysAndFiltersUnknownOrEmptyFields() {
        val values = mapOf(
            ProfileField.FullName to "Saved Person",
            ProfileField.ProfessionalTitle to "Engineer",
            ProfileField.Organization to "",
            ProfileField.Phone to "+1 202 555 0142",
            ProfileField.Website to "https://example.invalid/person",
            ProfileField.LinkedIn to "https://example.invalid/person",
        )
        val persistedKeys = setOf(
            ProfileField.FullName.key,
            ProfileField.ProfessionalTitle.key,
            ProfileField.Organization.key,
            ProfileField.Phone.key,
            ProfileField.Website.key,
            ProfileField.LinkedIn.key,
            "unknownField",
        )

        assertEquals(
            linkedSetOf(ProfileField.ProfessionalTitle, ProfileField.Phone),
            QuickShareSelection.restoreSelection(values, persistedKeys),
        )
    }

    @Test
    fun restoreSelectionDoesNotAutoSelectNewFields() {
        val values = mapOf(
            ProfileField.ProfessionalTitle to "Engineer",
            ProfileField.Organization to "Example Studio",
        )

        assertEquals(
            setOf(ProfileField.ProfessionalTitle),
            QuickShareSelection.restoreSelection(
                values,
                setOf(ProfileField.ProfessionalTitle.key),
            ),
        )
        assertTrue(QuickShareSelection.restoreSelection(values, emptySet()).isEmpty())
    }

    @Test
    fun selectAllSelectsOnlyPopulatedAdditionalFields() {
        val values = mapOf(
            ProfileField.FullName to "Saved Person",
            ProfileField.ProfessionalTitle to "Engineer",
            ProfileField.Organization to " ",
            ProfileField.Email to "person@example.invalid",
            ProfileField.Website to "https://example.invalid/person",
            ProfileField.GitHub to "https://example.invalid/person",
        )

        assertEquals(
            linkedSetOf(ProfileField.ProfessionalTitle, ProfileField.Email),
            QuickShareSelection.selectAll(values),
        )
    }

    @Test
    fun clearAllReturnsNoSelection() {
        assertTrue(QuickShareSelection.clearAll().isEmpty())
    }

    @Test
    fun selectedValuesContainsOnlySelectedPopulatedAdditionalFields() {
        val values = mapOf(
            ProfileField.FullName to "Saved Person",
            ProfileField.ProfessionalTitle to "Engineer",
            ProfileField.Organization to "Example Studio",
            ProfileField.Email to "person@example.invalid",
            ProfileField.Website to "https://example.invalid/person",
            ProfileField.LinkedIn to "https://example.invalid/social",
            ProfileField.FirstName to "Saved",
        )
        val selection = setOf(
            ProfileField.FullName,
            ProfileField.Organization,
            ProfileField.Email,
            ProfileField.Website,
            ProfileField.LinkedIn,
            ProfileField.FirstName,
        )

        assertEquals(
            mapOf(
                ProfileField.Organization to "Example Studio",
                ProfileField.Email to "person@example.invalid",
            ),
            QuickShareSelection.selectedValues(values, selection),
        )
    }

    @Test
    fun selectedValuesPreservesOriginalSavedStrings() {
        val original = "  Senior Engineer\t "
        val values = mapOf(ProfileField.ProfessionalTitle to original)

        assertEquals(
            original,
            QuickShareSelection.selectedValues(
                values,
                setOf(ProfileField.ProfessionalTitle),
            )[ProfileField.ProfessionalTitle],
        )
    }

    @Test
    fun selectedValuesHasDeterministicApprovedOrder() {
        val values = approvedFields.associateWith { field -> "Value for ${field.key}" }
        val selection = setOf(
            ProfileField.Website,
            ProfileField.Email,
            ProfileField.Phone,
            ProfileField.Organization,
            ProfileField.ProfessionalTitle,
        )

        assertEquals(approvedFields, QuickShareSelection.selectedValues(values, selection).keys.toList())
    }

    @Test
    fun canContinueRequiresAtLeastOnePopulatedAdditionalField() {
        val values = mapOf(
            ProfileField.FullName to "Saved Person",
            ProfileField.ProfessionalTitle to "Engineer",
            ProfileField.Organization to " ",
            ProfileField.Website to "https://example.invalid/person",
        )

        assertFalse(QuickShareSelection.canContinue(values, emptySet()))
        assertFalse(QuickShareSelection.canContinue(values, setOf(ProfileField.FullName)))
        assertFalse(QuickShareSelection.canContinue(values, setOf(ProfileField.Organization)))
        assertFalse(QuickShareSelection.canContinue(values, setOf(ProfileField.Website)))
        assertFalse(QuickShareSelection.canContinue(values, setOf(ProfileField.LinkedIn)))
        assertTrue(QuickShareSelection.canContinue(values, setOf(ProfileField.ProfessionalTitle)))
    }

    @Test
    fun fullNameCannotBeSelectedAsAdditionalInformation() {
        val values = mapOf(ProfileField.FullName to "Saved Person")

        assertFalse(ProfileField.FullName in QuickShareSelection.availableFields(values))
        assertFalse(ProfileField.FullName in QuickShareSelection.selectAll(values))
        assertFalse(ProfileField.FullName in QuickShareSelection.restoreSelection(
            values,
            setOf(ProfileField.FullName.key),
        ))
        assertTrue(
            QuickShareSelection.selectedValues(values, setOf(ProfileField.FullName)).isEmpty(),
        )
        assertFalse(QuickShareSelection.canContinue(values, setOf(ProfileField.FullName)))
    }

    @Test
    fun confirmedKeysUseExistingProfileFieldKeys() {
        assertEquals(
            listOf("professionalTitle", "organization", "phone", "email"),
            QuickShareSelection.selectableFields.map { it.key },
        )
        val selected = QuickShareSelection.selectAll(
            approvedFields.associateWith { "Value for ${it.key}" },
        )

        assertEquals(approvedFields.map { it.key }.toSet(), selected.map { it.key }.toSet())
    }

    @Test
    fun availabilityDoesNotDependOnOrdinaryExportVisibility() {
        val values = mapOf(
            ProfileField.ProfessionalTitle to "Engineer",
            ProfileField.Email to "person@example.invalid",
        )
        val defaultVisibility = ProfileDraft.empty().visibility
        val allOptionalExportsEnabled = defaultVisibility.mapValues { (field, visibility) ->
            if (field == ProfileField.FullName || visibility.includeInContactExport == null) visibility
            else visibility.copy(includeInContactExport = true)
        }
        val noOptionalExportsEnabled = defaultVisibility.mapValues { (field, visibility) ->
            if (field == ProfileField.FullName || visibility.includeInContactExport == null) visibility
            else visibility.copy(includeInContactExport = false)
        }
        val exportEnabledProfile = Profile(UUID.randomUUID(), values, allOptionalExportsEnabled)
        val exportDisabledProfile = Profile(UUID.randomUUID(), values, noOptionalExportsEnabled)

        assertEquals(
            QuickShareSelection.availableFields(exportEnabledProfile.values),
            QuickShareSelection.availableFields(exportDisabledProfile.values),
        )
    }
}