package com.mackseoul.vcardnative.data.sharing

import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.Decoder
import com.mackseoul.vcardnative.domain.profile.AsciiHostValidator
import com.mackseoul.vcardnative.domain.profile.Profile
import com.mackseoul.vcardnative.domain.profile.ProfileDraft
import com.mackseoul.vcardnative.domain.profile.ProfileField
import com.mackseoul.vcardnative.domain.profile.ProfileValidator
import com.mackseoul.vcardnative.domain.profile.Visibility
import com.mackseoul.vcardnative.domain.profile.structuredNameFields
import com.mackseoul.vcardnative.domain.sharing.ContactExport
import com.mackseoul.vcardnative.domain.sharing.ContactQr
import com.mackseoul.vcardnative.domain.sharing.ContactQrException
import com.mackseoul.vcardnative.domain.sharing.QrFailure
import com.mackseoul.vcardnative.domain.sharing.QuickShareSelection
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class QuickShareQrGeneratorTest {
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
        val visibility = initialVisibility.mapValues { (field, preference) ->
            if (field == ProfileField.FullName || field in structuredNameFields ||
                preference.includeInContactExport == null
            ) preference
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

    private fun rawProfile(values: Map<ProfileField, String>) = Profile(
        profileId = UUID.randomUUID(),
        values = values,
        visibility = ProfileDraft.empty().visibility,
    )

    private fun content(export: ContactExport) = String(export.bytes, Charsets.UTF_8)

    private fun decode(qr: ContactQr) = Decoder().decode(BitMatrix(qr.side).apply {
        for (y in 0 until qr.side) for (x in 0 until qr.side) if (qr.isBlack(x, y)) set(x, y)
    })

    private fun filledQuickShareProfile(): Profile = profile(
        mapOf(
            ProfileField.FullName to savedName,
            ProfileField.ProfessionalTitle to "T".repeat(160),
            ProfileField.Organization to "O".repeat(160),
            ProfileField.Phone to "+1 202 555 0142",
            ProfileField.Email to "a".repeat(60) + "@example.invalid",
            ProfileField.Website to "https://example.invalid/PRIVATE_WEBSITE_MARKER/" + "w".repeat(1800),
        ),
    )

    @Test
    fun validSelectionProducesQuickShareQrAndRetainsFilteredExport() {
        val profile = profile(
            mapOf(
                ProfileField.FullName to savedName,
                ProfileField.Email to "person@example.invalid",
            ),
        )
        var encodeCalls = 0
        var encodedExport: ContactExport? = null
        val fakeQr = ContactQr(1, BooleanArray(21 * 21))

        val result = QuickShareQrGenerator.generate(profile, setOf(ProfileField.Email)) { export ->
            encodeCalls++
            encodedExport = export
            fakeQr
        }

        assertTrue(result is QuickShareQrResult.QrAvailable)
        result as QuickShareQrResult.QrAvailable
        assertSame(encodedExport, result.export)
        assertSame(fakeQr, result.qr)
        assertEquals(1, encodeCalls)
    }

    @Test
    fun generatedQrDecodesToExactFilteredVCardUtf8Bytes() {
        val profile = profile(
            mapOf(
                ProfileField.FullName to "Zoë 中文 😀",
                ProfileField.Organization to "Research, Inc.; Unit\\One",
                ProfileField.Email to "person@example.invalid",
                ProfileField.Biography to "PRIVATE_BIOGRAPHY_MARKER",
            ),
            exportFields = setOf(ProfileField.Biography),
        )
        val result = QuickShareQrGenerator.generate(
            profile,
            setOf(ProfileField.Organization, ProfileField.Email),
        ) as QuickShareQrResult.QrAvailable
        val decoded = decode(result.qr)

        assertArrayEquals(result.export.bytes, decoded.byteSegments.single())
        assertEquals(String(result.export.bytes, Charsets.UTF_8), decoded.text)
        assertTrue(decoded.text.contains("FN:Zoë 中文 😀"))
        assertTrue(decoded.text.contains("ORG:Research\\, Inc.\\; Unit\\\\One"))
        assertFalse(decoded.text.contains("PRIVATE_BIOGRAPHY_MARKER"))
    }

    @Test
    fun fullNameIsAutomaticAndOnlySelectedAdditionalFieldsAreEncoded() {
        val profile = profile(
            mapOf(
                ProfileField.FullName to savedName,
                ProfileField.ProfessionalTitle to "Engineer",
                ProfileField.Organization to "PRIVATE_ORGANIZATION_MARKER",
                ProfileField.Email to "person@example.invalid",
            ),
        )
        val result = QuickShareQrGenerator.generate(
            profile,
            setOf(ProfileField.ProfessionalTitle),
        ) as QuickShareQrResult.QrAvailable
        val text = decode(result.qr).text

        assertTrue(text.contains("FN:$savedName"))
        assertTrue(text.contains("TITLE:Engineer"))
        assertFalse(text.contains("PRIVATE_ORGANIZATION_MARKER"))
        assertFalse(text.contains("person@example.invalid"))
    }

    @Test
    fun socialAndStructuredNamesNeverAppearEvenWhenOrdinaryExportAllowsThem() {
        val profile = profile(
            mapOf(
                ProfileField.FullName to savedName,
                ProfileField.Phone to "+1 202 555 0142",
                ProfileField.FirstName to "PRIVATE_FIRST_MARKER",
                ProfileField.MiddleName to "PRIVATE_MIDDLE_MARKER",
                ProfileField.LastName to "PRIVATE_LAST_MARKER",
                ProfileField.LinkedIn to "https://example.invalid/PRIVATE_LINKEDIN_MARKER",
                ProfileField.GitHub to "https://example.invalid/PRIVATE_GITHUB_MARKER",
                ProfileField.OtherProfessional to "https://example.invalid/PRIVATE_OTHER_LINK_MARKER",
            ),
            exportFields = setOf(
                ProfileField.FirstName,
                ProfileField.MiddleName,
                ProfileField.LastName,
                ProfileField.LinkedIn,
                ProfileField.GitHub,
                ProfileField.OtherProfessional,
            ),
            showFields = setOf(ProfileField.Phone),
        )
        val result = QuickShareQrGenerator.generate(
            profile,
            setOf(ProfileField.Phone),
        ) as QuickShareQrResult.QrAvailable
        val text = decode(result.qr).text

        assertTrue(text.contains("TEL:+1 202-555-0142"))
        listOf("PRIVATE_FIRST_MARKER", "PRIVATE_MIDDLE_MARKER", "PRIVATE_LAST_MARKER", "PRIVATE_LINKEDIN_MARKER",
            "PRIVATE_GITHUB_MARKER", "PRIVATE_OTHER_LINK_MARKER").forEach { assertFalse(text.contains(it)) }
    }

    @Test
    fun ordinaryExportVisibilityDoesNotAffectQuickSharePayload() {
        val values = mapOf(
            ProfileField.FullName to savedName,
            ProfileField.ProfessionalTitle to "Private-by-default title",
            ProfileField.Email to "person@example.invalid",
        )
        val exportHidden = profile(values)
        val exportVisible = profile(values, exportFields = values.keys)

        val hiddenResult = QuickShareQrGenerator.generate(
            exportHidden,
            setOf(ProfileField.ProfessionalTitle, ProfileField.Email),
        ) as QuickShareQrResult.QrAvailable
        val visibleResult = QuickShareQrGenerator.generate(
            exportVisible,
            setOf(ProfileField.ProfessionalTitle, ProfileField.Email),
        ) as QuickShareQrResult.QrAvailable

        assertArrayEquals(hiddenResult.export.bytes, visibleResult.export.bytes)
        assertArrayEquals(decode(hiddenResult.qr).rawBytes, decode(visibleResult.qr).rawBytes)
    }

    @Test
    fun invalidOrMissingFullNameProducesExportUnavailableWithoutEncoding() {
        val profiles = listOf(
            rawProfile(mapOf(ProfileField.Email to "person@example.invalid")),
            rawProfile(mapOf(ProfileField.FullName to " ", ProfileField.Email to "person@example.invalid")),
        )
        var encodeCalls = 0

        profiles.forEach { profile ->
            val result = QuickShareQrGenerator.generate(profile, setOf(ProfileField.Email)) {
                encodeCalls++
                error("Encoder must not run when export construction fails")
            }
            assertTrue(result is QuickShareQrResult.ExportUnavailable)
            result as QuickShareQrResult.ExportUnavailable
            assertTrue(result.error.message!!.contains("full name"))
        }
        assertEquals(0, encodeCalls)
    }

    @Test
    fun emptySelectionProducesExportUnavailableWithoutOrdinaryExportFallback() {
        val profile = profile(
            mapOf(
                ProfileField.FullName to savedName,
                ProfileField.Email to "PRIVATE_EMAIL_MARKER@example.invalid",
            ),
            exportFields = setOf(ProfileField.Email),
        )
        var encodeCalls = 0

        val result = QuickShareQrGenerator.generate(profile, emptySet()) {
            encodeCalls++
            error("Ordinary profile export must not be used as a fallback")
        }

        assertTrue(result is QuickShareQrResult.ExportUnavailable)
        assertEquals(0, encodeCalls)
        assertFalse(result is QuickShareQrResult.QrUnavailable)
    }

    @Test
    fun unsupportedOnlySelectionProducesExportUnavailable() {
        val profile = profile(
            mapOf(
                ProfileField.FullName to savedName,
                ProfileField.LinkedIn to "https://example.invalid/person",
            ),
        )
        var encodeCalls = 0

        val result = QuickShareQrGenerator.generate(profile, setOf(ProfileField.LinkedIn)) {
            encodeCalls++
            error("Encoder must not run for unsupported-only selection")
        }

        assertTrue(result is QuickShareQrResult.ExportUnavailable)
        assertEquals(0, encodeCalls)
    }

    @Test
    fun validFilteredVCardRemainsAvailableWhenEncoderReportsPayloadLimit() {
        val profile = filledQuickShareProfile()
        val selection = QuickShareSelection.selectAll(profile.values)

        val result = QuickShareQrGenerator.generate(profile, selection) {
            throw ContactQrException(QrFailure.PayloadLimit)
        }

        assertTrue(result is QuickShareQrResult.QrUnavailable)
        result as QuickShareQrResult.QrUnavailable
        assertEquals(QrFailure.PayloadLimit, result.failure)
        assertTrue(result.export.bytes.size <= ContactExport.MAX_BYTES)
        assertTrue(content(result.export).contains("FN:$savedName"))
        assertFalse(content(result.export).contains("PRIVATE_WEBSITE_MARKER"))
    }

    @Test
    fun qrEncodingFailureRetainsTheValidFilteredVCard() {
        val profile = profile(
            mapOf(ProfileField.FullName to savedName, ProfileField.Email to "person@example.invalid"),
        )
        val result = QuickShareQrGenerator.generate(profile, setOf(ProfileField.Email)) {
            throw ContactQrException(QrFailure.EncodingCapacity)
        }

        assertTrue(result is QuickShareQrResult.QrUnavailable)
        result as QuickShareQrResult.QrUnavailable
        assertEquals(QrFailure.EncodingCapacity, result.failure)
        assertTrue(content(result.export).contains("EMAIL;TYPE=INTERNET:person@example.invalid"))
    }

    @Test
    fun qrFailureClassificationIsPreserved() {
        val profile = profile(
            mapOf(ProfileField.FullName to savedName, ProfileField.Email to "person@example.invalid"),
        )

        for (failure in listOf(QrFailure.PayloadLimit, QrFailure.EncodingCapacity, QrFailure.Encoding)) {
            val result = QuickShareQrGenerator.generate(profile, setOf(ProfileField.Email)) {
                throw ContactQrException(failure)
            }

            assertTrue(result is QuickShareQrResult.QrUnavailable)
            result as QuickShareQrResult.QrUnavailable
            assertEquals(failure, result.failure)
            assertNotNull(result.export)
        }
    }

    @Test
    fun originalProfileRemainsUnchangedAfterGeneration() {
        val profile = profile(
            mapOf(
                ProfileField.FullName to savedName,
                ProfileField.ProfessionalTitle to "Engineer",
                ProfileField.Email to "person@example.invalid",
                ProfileField.LinkedIn to "https://example.invalid/private",
            ),
            exportFields = setOf(ProfileField.LinkedIn),
        )
        val originalValues = profile.values.toMap()
        val originalVisibility = profile.visibility.toMap()
        val originalProfileId = profile.profileId

        QuickShareQrGenerator.generate(profile, setOf(ProfileField.ProfessionalTitle, ProfileField.Email))

        assertEquals(originalValues, profile.values)
        assertEquals(originalVisibility, profile.visibility)
        assertEquals(originalProfileId, profile.profileId)
    }

    @Test
    fun equivalentInputsProduceDeterministicFilteredPayloads() {
        val values = mapOf(
            ProfileField.FullName to savedName,
            ProfileField.Organization to "Example Studio",
            ProfileField.Email to "person@example.invalid",
        )
        val selection = setOf(ProfileField.Email, ProfileField.Organization)
        val first = QuickShareQrGenerator.generate(profile(values), selection) as QuickShareQrResult.QrAvailable
        val second = QuickShareQrGenerator.generate(profile(values), selection) as QuickShareQrResult.QrAvailable

        assertArrayEquals(first.export.bytes, second.export.bytes)
        for (y in 0 until first.qr.side) for (x in 0 until first.qr.side) {
            assertEquals(first.qr.isBlack(x, y), second.qr.isBlack(x, y))
        }
    }

    @Test
    fun websiteIsExcludedFromQuickShareQrEvenWhenPassedInSelection() {
        val websiteCases = listOf(
            "https://example.invalid/PRIVATE_WEBSITE_MARKER" to profile(
                mapOf(
                    ProfileField.FullName to savedName,
                    ProfileField.Email to "person@example.invalid",
                    ProfileField.Website to "https://example.invalid/PRIVATE_WEBSITE_MARKER",
                ),
            ),
            "example.invalid/PRIVATE_WEBSITE_MARKER" to rawProfile(
                mapOf(
                    ProfileField.FullName to savedName,
                    ProfileField.Email to "person@example.invalid",
                    ProfileField.Website to "example.invalid/PRIVATE_WEBSITE_MARKER",
                ),
            ),
            "https://example.invalid/東京/PRIVATE_WEBSITE_MARKER" to rawProfile(
                mapOf(
                    ProfileField.FullName to savedName,
                    ProfileField.Email to "person@example.invalid",
                    ProfileField.Website to "https://example.invalid/東京/PRIVATE_WEBSITE_MARKER",
                ),
            ),
        )

        for ((website, profile) in websiteCases) {
            val result = QuickShareQrGenerator.generate(
                profile,
                setOf(ProfileField.Website, ProfileField.Email),
            ) as QuickShareQrResult.QrAvailable
            val text = decode(result.qr).text

            assertTrue(text.contains("FN:$savedName"))
            assertTrue(text.contains("EMAIL;TYPE=INTERNET:person@example.invalid"))
            assertFalse(text.contains("URL:"))
            assertFalse(text.contains("PRIVATE_WEBSITE_MARKER"))
            assertEquals(website, profile.values[ProfileField.Website])
            assertFalse(result.export.rows.any { ProfileField.Website in it.fields })
        }
    }
}