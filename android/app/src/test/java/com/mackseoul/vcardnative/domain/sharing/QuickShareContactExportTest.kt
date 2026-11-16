package com.mackseoul.vcardnative.domain.sharing

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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class QuickShareContactExportTest {
    private val savedName = "Saved Person"

    @Before
    fun configureHosts() {
        ProfileValidator.hostValidator = AsciiHostValidator
    }

    private fun profile(
        values: Map<ProfileField, String>,
        exportFields: Set<ProfileField> = emptySet(),
        showFields: Set<ProfileField> = emptySet(),
        phoneRegion: String = "US",
        includeCountryCode: Boolean = true,
    ): Profile {
        val initialVisibility = ProfileDraft.empty().visibility
        val visibility = initialVisibility.mapValues { (field, current) ->
            if (field == ProfileField.FullName || field in structuredNameFields || current.includeInContactExport == null) current
            else Visibility(field in showFields, field in exportFields)
        }
        val draft = ProfileDraft.empty().copy(
            values = values,
            visibility = visibility,
            phoneRegion = phoneRegion,
            includeCountryCodeWhenSharing = includeCountryCode,
        )
        val normalized = requireNotNull(ProfileValidator.validate(draft).normalized) {
            "Test profile must be valid under the current profile validator."
        }
        return Profile(
            profileId = UUID.randomUUID(),
            values = normalized.values,
            visibility = normalized.visibility,
            phoneRegion = normalized.phoneRegion,
            includeCountryCodeWhenSharing = normalized.includeCountryCodeWhenSharing,
            phoneNumberE164 = normalized.phoneNumberE164,
        )
    }

    private fun rawProfile(
        values: Map<ProfileField, String>,
        phoneRegion: String = "US",
        includeCountryCode: Boolean = true,
        phoneNumberE164: String? = null,
    ) = Profile(
        profileId = UUID.randomUUID(),
        values = values,
        visibility = ProfileDraft.empty().visibility,
        phoneRegion = phoneRegion,
        includeCountryCodeWhenSharing = includeCountryCode,
        phoneNumberE164 = phoneNumberE164,
    )

    private fun content(export: ContactExport) = String(export.bytes, Charsets.UTF_8)

    @Test
    fun fullNameIsAlwaysIncludedAndUsesExistingEmptyStructuredNameProperties() {
        val export = QuickShareContactExport.from(
            profile(mapOf(ProfileField.FullName to savedName, ProfileField.Email to "person@example.invalid")),
            setOf(ProfileField.Email),
        )

        assertTrue(content(export).contains("FN:$savedName\r\n"))
        assertTrue(content(export).contains("N:;;;;\r\n"))
        assertEquals(listOf(ProfileField.FullName, ProfileField.Email), export.rows.map { it.fields.single() })
    }

    @Test
    fun fullNameIsNotSelectableAndFullNameOnlySelectionIsRejected() {
        assertFalse(ProfileField.FullName in QuickShareSelection.selectableFields)
        assertThrows(ContactExportException::class.java) {
            QuickShareContactExport.from(
                profile(mapOf(ProfileField.FullName to savedName)),
                setOf(ProfileField.FullName),
            )
        }
    }

    @Test
    fun includesEachSelectedAdditionalField() {
        val values = mapOf(
            ProfileField.FullName to savedName,
            ProfileField.ProfessionalTitle to "Engineer",
            ProfileField.Organization to "Example Studio",
            ProfileField.Phone to "+1 202 555 0142",
            ProfileField.Email to "person@example.invalid",
            ProfileField.Website to "https://example.invalid/person",
        )
        val expectedLines = mapOf(
            ProfileField.ProfessionalTitle to "TITLE:Engineer\r\n",
            ProfileField.Organization to "ORG:Example Studio\r\n",
            ProfileField.Phone to "TEL:+1 202-555-0142\r\n",
            ProfileField.Email to "EMAIL;TYPE=INTERNET:person@example.invalid\r\n",
        )

        for (field in QuickShareSelection.selectableFields) {
            val export = QuickShareContactExport.from(profile(values), setOf(field))
            assertTrue("Missing output for $field", content(export).contains(expectedLines.getValue(field)))
            assertEquals(setOf(ProfileField.FullName, field), export.rows.flatMap { it.fields }.toSet())
        }
    }

    @Test
    fun unselectedValuesSocialProfilesAndStructuredNamesAreAbsent() {
        val values = mapOf(
            ProfileField.FullName to savedName,
            ProfileField.ProfessionalTitle to "Selected title",
            ProfileField.Organization to "PRIVATE_ORGANIZATION_MARKER",
            ProfileField.Phone to "+1 202 555 0142",
            ProfileField.Email to "PRIVATE_EMAIL_MARKER@example.invalid",
            ProfileField.Website to "https://example.invalid/PRIVATE_WEBSITE_MARKER",
            ProfileField.FirstName to "PRIVATE_FIRST_NAME_MARKER",
            ProfileField.MiddleName to "PRIVATE_MIDDLE_NAME_MARKER",
            ProfileField.LastName to "PRIVATE_LAST_NAME_MARKER",
            ProfileField.LinkedIn to "https://example.invalid/PRIVATE_LINKEDIN_MARKER",
            ProfileField.GitHub to "https://example.invalid/PRIVATE_GITHUB_MARKER",
            ProfileField.OtherProfessional to "https://example.invalid/PRIVATE_OTHER_LINK_MARKER",
            ProfileField.Biography to "PRIVATE_BIOGRAPHY_MARKER",
            ProfileField.Location to "PRIVATE_LOCATION_MARKER",
            ProfileField.HostedProfileUrl to "https://example.invalid/PRIVATE_HOSTED_MARKER",
        )
        val export = QuickShareContactExport.from(
            profile(values, exportFields = values.keys, showFields = values.keys),
            setOf(ProfileField.ProfessionalTitle),
        )
        val text = content(export)

        assertTrue(text.contains("TITLE:Selected title\r\n"))
        listOf(
            "PRIVATE_ORGANIZATION_MARKER",
            "PRIVATE_EMAIL_MARKER",
            "PRIVATE_WEBSITE_MARKER",
            "PRIVATE_FIRST_NAME_MARKER",
            "PRIVATE_MIDDLE_NAME_MARKER",
            "PRIVATE_LAST_NAME_MARKER",
            "PRIVATE_LINKEDIN_MARKER",
            "PRIVATE_GITHUB_MARKER",
            "PRIVATE_OTHER_LINK_MARKER",
            "PRIVATE_BIOGRAPHY_MARKER",
            "PRIVATE_LOCATION_MARKER",
            "PRIVATE_HOSTED_MARKER",
        ).forEach { marker -> assertFalse("Leaked $marker", text.contains(marker)) }
        assertEquals(setOf(ProfileField.FullName, ProfileField.ProfessionalTitle), export.rows.flatMap { it.fields }.toSet())
    }

    @Test
    fun collectionSocialEntriesRemainOutsideQuickShare() {
        val social = com.mackseoul.vcardnative.domain.profile.SocialProfile(
            UUID.randomUUID(), "linkedin", "LinkedIn", "https://example.invalid/social", true, true,
        )
        val base = profile(mapOf(
            ProfileField.FullName to savedName,
            ProfileField.Email to "person@example.invalid",
        ))
        val export = QuickShareContactExport.from(
            base.copy(socialProfiles = listOf(social)), setOf(ProfileField.Email),
        )

        assertTrue(content(export).contains("EMAIL;TYPE=INTERNET:person@example.invalid"))
        assertFalse(content(export).contains("https://example.invalid/social"))
        assertFalse(export.rows.any { it.value == social.url })
    }

    @Test
    fun ordinaryExportVisibilityDoesNotControlQuickShareOutput() {
        val values = mapOf(
            ProfileField.FullName to savedName,
            ProfileField.ProfessionalTitle to "Export hidden title",
            ProfileField.Organization to "Export hidden organization",
        )
        val allHidden = profile(values, exportFields = emptySet(), showFields = emptySet())
        val allVisible = profile(values, exportFields = values.keys, showFields = values.keys)

        val hiddenExport = QuickShareContactExport.from(
            allHidden,
            setOf(ProfileField.ProfessionalTitle, ProfileField.Organization),
        )
        val visibleExport = QuickShareContactExport.from(
            allVisible,
            setOf(ProfileField.ProfessionalTitle, ProfileField.Organization),
        )

        assertArrayEquals(hiddenExport.bytes, visibleExport.bytes)
        assertTrue(content(hiddenExport).contains("TITLE:Export hidden title\r\n"))
        assertTrue(content(hiddenExport).contains("ORG:Export hidden organization\r\n"))
    }

    @Test
    fun websiteIsExcludedEvenWhenPassedInSelection() {
        val website = "https://example.invalid/PRIVATE_WEBSITE_MARKER"
        val export = QuickShareContactExport.from(
            profile(
                mapOf(
                    ProfileField.FullName to savedName,
                    ProfileField.Email to "person@example.invalid",
                    ProfileField.Website to website,
                ),
                exportFields = setOf(ProfileField.Website),
            ),
            setOf(ProfileField.Website, ProfileField.Email),
        )
        val text = content(export)

        assertTrue(text.contains("FN:$savedName\r\n"))
        assertTrue(text.contains("EMAIL;TYPE=INTERNET:person@example.invalid\r\n"))
        assertFalse(text.contains("URL:"))
        assertFalse(text.contains("PRIVATE_WEBSITE_MARKER"))
        assertFalse(export.rows.any { ProfileField.Website in it.fields })
    }

    @Test
    fun missingOrBlankFullNameFailsWithoutFabricatingIdentity() {
        for (values in listOf(
            mapOf(ProfileField.Email to "person@example.invalid"),
            mapOf(ProfileField.FullName to " ", ProfileField.Email to "person@example.invalid"),
        )) {
            val error = assertThrows(ContactExportException::class.java) {
                QuickShareContactExport.from(rawProfile(values), setOf(ProfileField.Email))
            }
            assertTrue(error.message!!.contains("full name"))
        }
    }

    @Test
    fun emptyUnsupportedAndUnpopulatedSelectionsFailSafely() {
        val valid = profile(
            mapOf(ProfileField.FullName to savedName, ProfileField.Email to "person@example.invalid"),
        )
        for (selection in listOf(
            emptySet(),
            setOf(ProfileField.LinkedIn),
            setOf(ProfileField.Website),
            setOf(ProfileField.Phone),
        )) {
            assertThrows(ContactExportException::class.java) {
                QuickShareContactExport.from(valid, selection)
            }
        }
    }

    @Test
    fun originalProfileRemainsUnchanged() {
        val original = profile(
            mapOf(
                ProfileField.FullName to savedName,
                ProfileField.ProfessionalTitle to "Engineer",
                ProfileField.Email to "person@example.invalid",
                ProfileField.LinkedIn to "https://example.invalid/social",
            ),
            exportFields = setOf(ProfileField.LinkedIn),
        )
        val originalValues = original.values.toMap()
        val originalVisibility = original.visibility.toMap()
        val originalPhone = original.phoneNumberE164

        QuickShareContactExport.from(original, setOf(ProfileField.ProfessionalTitle, ProfileField.Email))

        assertEquals(originalValues, original.values)
        assertEquals(originalVisibility, original.visibility)
        assertEquals(originalPhone, original.phoneNumberE164)
    }

    @Test
    fun equivalentInputsProduceDeterministicVCardBytes() {
        val values = mapOf(
            ProfileField.FullName to savedName,
            ProfileField.Organization to "Example Studio",
            ProfileField.Email to "person@example.invalid",
        )
        val selection = setOf(ProfileField.Email, ProfileField.Organization)

        assertArrayEquals(
            QuickShareContactExport.from(profile(values), selection).bytes,
            QuickShareContactExport.from(profile(values), selection).bytes,
        )
    }

    @Test
    fun unicodeAndTextEscapingFollowExistingVCardConventions() {
        val export = QuickShareContactExport.from(
            profile(
                mapOf(
                    ProfileField.FullName to "Zoë 中文 😀",
                    ProfileField.Organization to "Research, Inc.; Unit\\One",
                ),
            ),
            setOf(ProfileField.Organization),
        )
        val text = content(export)

        assertTrue(text.contains("FN:Zoë 中文 😀\r\n"))
        assertTrue(text.contains("ORG:Research\\, Inc.\\; Unit\\\\One\r\n"))
        assertTrue(text.startsWith("BEGIN:VCARD\r\nVERSION:3.0\r\n"))
        assertTrue(text.endsWith("END:VCARD\r\n"))
    }

    @Test
    fun selectedPhonePreservesSavedInternationalAndNationalPreferences() {
        val values = mapOf(
            ProfileField.FullName to savedName,
            ProfileField.Phone to "020 7031 3000",
        )
        val international = QuickShareContactExport.from(
            profile(values, phoneRegion = "GB", includeCountryCode = true),
            setOf(ProfileField.Phone),
        )
        val national = QuickShareContactExport.from(
            profile(values, phoneRegion = "GB", includeCountryCode = false),
            setOf(ProfileField.Phone),
        )

        assertTrue(content(international).contains("TEL:+44 20 7031 3000\r\n"))
        assertTrue(content(national).contains("TEL:020 7031 3000\r\n"))
        assertEquals(
            "+44 20 7031 3000",
            international.rows.single { ProfileField.Phone in it.fields }.value,
        )
        assertEquals(
            "020 7031 3000",
            national.rows.single { ProfileField.Phone in it.fields }.value,
        )
    }

}