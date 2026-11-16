package com.mackseoul.vcardnative.domain.profile

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ProfileValidatorTest {
    @Test fun incompleteSocialEntriesHaveIndependentFieldErrors() {
        val entry = SocialProfile(java.util.UUID.randomUUID(), "", " ", " ", true, false)
        val errors = ProfileValidator.socialProfileFieldErrors(entry)
        assertEquals(setOf(SocialEntryField.Platform, SocialEntryField.Label, SocialEntryField.Url), errors.keys)
        assertEquals("Enter a platform name.", errors[SocialEntryField.Platform])
        assertEquals("Enter a label.", errors[SocialEntryField.Label])
        assertEquals("Enter an HTTPS profile URL.", errors[SocialEntryField.Url])
        val valid = entry.copy(platform = "github", label = "GitHub", url = "https://example.com/%2f")
        assertTrue(ProfileValidator.socialProfileFieldErrors(valid).isEmpty())
        assertEquals(setOf(SocialEntryField.Url),
            ProfileValidator.socialProfileFieldErrors(valid.copy(url = "http://example.com")).keys)
        assertEquals(setOf(SocialEntryField.Platform),
            ProfileValidator.socialProfileFieldErrors(valid.copy(platform = " ")).keys)
    }

    @Test fun browserCandidatesRespectSchemePolicyWithoutMutatingRawValues() {
        val raw = "HTTP://EXAMPLE.COM/Case/%2f"
        assertNotNull(ProfileValidator.browserUrlCandidate(raw, false))
        assertNull(ProfileValidator.browserUrlCandidate(raw, true))
        assertNotNull(ProfileValidator.browserUrlCandidate("HTTPS://EXAMPLE.COM/Card/", true))
        for (invalid in listOf("", " ", "example.com", "https:///card", "javascript:alert(1)",
            "file:///card", "content://card", "intent://card", "https://example.com/a b")) {
            assertNull(ProfileValidator.browserUrlCandidate(invalid, false))
        }
        assertEquals("HTTP://EXAMPLE.COM/Case/%2f", raw)
    }

    @Before fun asciiDefault() { ProfileValidator.hostValidator = AsciiHostValidator }

    private fun draft(field: ProfileField, value: String) = ProfileDraft.empty().copy(
        values = mapOf(ProfileField.FullName to "Ada", field to value),
        phoneRegion = if (field == ProfileField.Phone) "US" else PhoneNumbers.defaultRegion(),
    )

    private fun valid(field: ProfileField, input: String, expected: String = input) {
        val result = ProfileValidator.validate(draft(field, input))
        assertEquals("Errors for $field", emptyMap<ProfileField, String>(), result.errors)
        assertNotNull("Validation returned no draft for $field input '$input': ${result.errors}", result.normalized)
        assertEquals(expected, requireNotNull(result.normalized).values[field])
    }

    private fun invalid(field: ProfileField, vararg values: String) {
        values.forEach { value ->
            val result = ProfileValidator.validate(draft(field, value))
            assertNull("Unexpected accepted input for $field", result.normalized)
            assertTrue(result.errors.containsKey(field))
        }
    }

    @Test fun inventoryDefaultsAndIndependentPreferences() {
        assertEquals(18, ProfileField.entries.size)
        assertEquals(15, textFields.size)
        val initial = ProfileDraft.empty()
        assertEquals(Visibility(true, true), initial.visibility[ProfileField.FullName])
        assertEquals(Visibility(null, null), initial.visibility[ProfileField.HostedProfileUrl])
        assertEquals(Visibility(false, null), initial.visibility[ProfileField.PublicKey])
        assertEquals(Visibility(true, null), initial.visibility[ProfileField.ProfileImage])
        for (field in textFields.drop(1).dropLast(1).filter { it !in structuredNameFields }) {
            for (show in listOf(false, true)) for (export in listOf(false, true)) {
                val preference = Visibility(show, export)
                val empty = draft(field, " ").copy(visibility = initial.visibility + (field to preference))
                val validation = ProfileValidator.validate(empty)
                assertNotNull("No valid draft for $field: ${validation.errors}", validation.normalized)
                val result = requireNotNull(validation.normalized)
                assertFalse(result.values.containsKey(field))
                assertEquals(preference, result.visibility[field])
            }
        }
    }

    @Test fun fullNameOnlyRequiredNoSilentTruncation() {
        assertTrue(ProfileValidator.validate(ProfileDraft.empty()).errors.containsKey(ProfileField.FullName))
        invalid(ProfileField.FullName, "", " \u00A0", "a".repeat(603))
        valid(ProfileField.FullName, "a".repeat(602))
        valid(ProfileField.FullName, "  A\u0301da  ", "Áda")
        valid(ProfileField.FullName, "😀".repeat(602))
        invalid(ProfileField.FullName, "😀".repeat(603))
        for (field in structuredNameFields) {
            valid(field, "😀".repeat(200))
            invalid(field, "😀".repeat(201))
        }
        for (field in listOf(ProfileField.ProfessionalTitle, ProfileField.Organization, ProfileField.Location)) {
            valid(field, "x".repeat(160))
            invalid(field, "x".repeat(161))
        }
    }

    @Test fun unicodeControlsAndBiographyRules() {
        for (control in listOf('\u0000', '\u0001', '\t', '\r', '\n', '\u007F', '\u202A', '\u202E', '\u2066', '\u2069')) {
            invalid(ProfileField.FullName, "A${control}B")
        }
        invalid(ProfileField.FullName, "\uD800", "\uDC00", "A\uD800B")
        valid(ProfileField.FullName, "ليلى\u200Dعلي")
        valid(ProfileField.Biography, " A\r\nB\rC\tD ", "A\nB\nC D")
        valid(ProfileField.Biography, "x".repeat(2048))
        invalid(ProfileField.Biography, "x".repeat(2049), "A\u0000B", "A\u007FB", "A\u2067B")
    }

    @Test fun phonesAreAsciiAndBounded() {
        valid(ProfileField.Phone, "+1 (202) 555-0123")
        assertEquals("+12125550123", ProfileValidator.dialValue("+1 (212) 555-0123"))
        invalid(ProfileField.Phone, "12", "123", "123456789012345", "1234567890123456",
            "1" + " ".repeat(30) + "23", "123 ext 4", "123;4", "123,4",
            "1+23", "++123", "١٢٣", "１２３")
    }

    @Test fun regionalPhoneValidationPreservesDisplayAndStoresInternationalNumber() {
        val regional = ProfileDraft.empty().copy(
            values = mapOf(ProfileField.FullName to "Ada", ProfileField.Phone to "020 7031 3000"),
            phoneRegion = "GB",
        )
        val normalized = ProfileValidator.validate(regional).normalized!!
        assertEquals("020 7031 3000", normalized.values[ProfileField.Phone])
        assertEquals("+442070313000", normalized.phoneNumberE164)
        assertTrue(normalized.includeCountryCodeWhenSharing)

        val legacy = ProfileDraft.empty().copy(
            values = mapOf(ProfileField.FullName to "Ada", ProfileField.Phone to "123"),
            allowUnparsedLegacyPhone = true,
        )
        assertNull(ProfileValidator.validate(legacy).errors[ProfileField.Phone])
        assertNull(ProfileValidator.validate(legacy).normalized!!.phoneNumberE164)
        assertTrue(ProfileValidator.validate(legacy.copy(allowUnparsedLegacyPhone = false))
            .errors.containsKey(ProfileField.Phone))
    }

    @Test fun emailDotAtomLengthAndCanonicalDomain() {
        valid(ProfileField.Email, "O'Neil+Tag@Example.COM", "O'Neil+Tag@example.com")
        valid(ProfileField.Email, "!#$%&'*+-/=?^_`{|}~@example.com")
        valid(ProfileField.Email, "a".repeat(64) + "@example.com")
        invalid(ProfileField.Email, "a".repeat(65) + "@example.com", ".a@example.com", "a.@example.com",
            "a..b@example.com", "\"a\"@example.com", "Ada <a@example.com>", "a b@example.com",
            "a@@example.com", "é@example.com", "a@-example.com", "a@example-.com", "a@a..com",
            "a@example.com.", "a@127.0.0.1", "a@[::1]", "a@faß.de", "a@xn--fa-hia.de")
        val host = List(3) { "a".repeat(63) }.joinToString(".")
        valid(ProfileField.Email, "a".repeat(62) + "@$host")
        invalid(ProfileField.Email, "a".repeat(63) + "@$host")
    }

    @Test fun idnaHostSeamPreservesSharpSAndDoesNotSilentlyMap() {
        val hosts = HostValidator { host ->
            when (host) {
                "faß.de" -> "xn--fa-hia.de"
                "bücher.example" -> "xn--bcher-kva.example"
                else -> AsciiHostValidator.normalize(host)
            }
        }
        val email = ProfileValidator.validate(draft(ProfileField.Email, "Case@faß.de"), hosts)
        assertEquals("Case@xn--fa-hia.de", email.normalized!!.values[ProfileField.Email])
        val url = ProfileValidator.validate(draft(ProfileField.Website, "https://bücher.example/é"), hosts)
        assertEquals("https://bücher.example/é", url.normalized!!.values[ProfileField.Website])
        assertNull(ProfileValidator.validate(draft(ProfileField.Email, "a@ＦＯＯ.example"), hosts).normalized)
    }

    @Test fun explicitUrlsPreserveMeaningAndPercentTriplets() {
        valid(ProfileField.Website, "HTTP://EXAMPLE.COM:8080/Case/é?q=漢字&x=%2f#Frag")
        valid(ProfileField.Website, "https://example.com?x=1#F")
        valid(ProfileField.Website, "https://127.0.0.1:65535/a")
        valid(ProfileField.Website, "https://[2001:DB8::1]:1/a")
        valid(ProfileField.Website, "https://[::ffff:192.0.2.1]/a")
        invalid(ProfileField.Website, "example.com", "//example.com", "https:///a", "https://",
            "https://a@b.com", "https://a:b@b.com", "https://ex ample.com", "https://example.com/a b",
            "https://example.com\\a", "https://example.com/%", "https://example.com/%2",
            "https://example.com/%GG", "https://example.com:0", "https://example.com:65536",
            "https://example.com:", "https://example.com:abc", "https://example.com:99999999999999999",
            "https://[:::1]", "https://[::1", "https://[::1]garbage", "https://[fe80::1%25eth0]",
            "https://256.0.0.1", "https://01.2.3.4", "https://a..com",
            "https://example.com.\u200B", "file://example.com/a", "content://example.com",
            "javascript://example.com", "data://example.com", "intent://example.com")
        for (field in listOf(ProfileField.LinkedIn, ProfileField.GitHub, ProfileField.OtherProfessional, ProfileField.HostedProfileUrl)) {
            valid(field, "https://unrelated.example/a")
            invalid(field, "http://example.com")
        }
    }

    @Test fun urlLimitIsSerializedByteLimit() {
        val prefix = "https://example.com/"
        valid(ProfileField.Website, prefix + "a".repeat(2048 - prefix.length))
        invalid(ProfileField.Website, prefix + "a".repeat(2049 - prefix.length), prefix + "😀".repeat(200))
    }

    @Test fun exactWebsiteSocialAndHostedUrlsAreValidatedWithoutRewriting() {
        val website = "HTTP://EXAMPLE.COM/Path/%2f?Query=Case"
        val social = "HTTPS://EXAMPLE.COM/LinkedIn/%2f"
        val hosted = "https://EXAMPLE.COM/MyCard/"
        valid(ProfileField.Website, website)
        valid(ProfileField.HostedProfileUrl, hosted)
        val entry = SocialProfile(
            java.util.UUID.fromString("00000000-0000-4000-8000-000000000001"),
            "linkedin", "LinkedIn", social, true, true,
        )
        val normalized = ProfileValidator.validate(ProfileDraft.empty().copy(
            values = mapOf(ProfileField.FullName to "Ada", ProfileField.Website to website,
                ProfileField.HostedProfileUrl to hosted),
            socialProfiles = listOf(entry, entry.copy(
                id = java.util.UUID.fromString("00000000-0000-4000-8000-000000000002"),
                label = "LinkedIn duplicate",
            )),
        )).normalized!!

        assertEquals(website, normalized.values[ProfileField.Website])
        assertEquals(hosted, normalized.values[ProfileField.HostedProfileUrl])
        assertEquals(listOf(social, social), normalized.socialProfiles.map { it.url })
        invalid(ProfileField.Website, "javascript://example.com", "file://example.com/card", "example.com")
        invalid(ProfileField.HostedProfileUrl, "http://example.com/card", "content://example.com/card")
    }

    @Test fun socialCollectionRejectsInvalidEntriesIndependentlyAndAllowsDuplicateUrls() {
        val first = SocialProfile(
            java.util.UUID.fromString("00000000-0000-4000-8000-000000000011"),
            "linkedin", "Work", "https://example.com/same", true, false,
        )
        val second = first.copy(
            id = java.util.UUID.fromString("00000000-0000-4000-8000-000000000012"),
            platform = "Custom platform", label = "Personal", includeInContactExport = true,
        )
        val draft = ProfileDraft.empty().copy(
            values = mapOf(ProfileField.FullName to "Ada"),
            socialProfiles = listOf(first, second),
        )
        val valid = ProfileValidator.validate(draft).normalized!!
        assertEquals(listOf(first, second), valid.socialProfiles)
        assertNull(ProfileValidator.validate(draft.copy(socialProfiles = listOf(
            first.copy(url = "http://example.com/same"), second,
        ))).normalized)
        assertNull(ProfileValidator.validate(draft.copy(socialProfiles = listOf(first, first))).normalized)
    }

    @Test fun unsupportedVisibilityAndAssetsFailClosedEvenEmptyAssetInput() {
        val initial = draft(ProfileField.FullName, "Ada")
        for ((field, setting) in listOf(
            ProfileField.FullName to Visibility(false, true),
            ProfileField.HostedProfileUrl to Visibility(true, null),
            ProfileField.ProfileImage to Visibility(true, false),
            ProfileField.PublicKey to Visibility(false, true),
            ProfileField.Phone to Visibility(null, false)
        )) {
            val result = ProfileValidator.validate(initial.copy(visibility = initial.visibility + (field to setting)))
            assertTrue(result.errors.containsKey(field))
        }
        for (field in ProfileField.entries.filter { it !in textFields }) invalid(field, "", "file", "content://asset")
    }

    @Test fun structuredNamesAreOptionalIndependentlyPrivateAndNeverDerived() {
        val plain = requireNotNull(ProfileValidator.validate(draft(ProfileField.FullName, "Family Given Middle")).normalized)
        structuredNameFields.forEach { field ->
            assertFalse(plain.values.containsKey(field))
            assertEquals(Visibility(null, false), plain.visibility[field])
            for (included in listOf(false, true)) {
                val normalized = requireNotNull(ProfileValidator.validate(draft(field, "  A\u0301  ").copy(
                    visibility = plain.visibility + (field to Visibility(null, included)),
                )).normalized)
                assertEquals("Á", normalized.values[field])
                assertEquals("Ada", normalized.values[ProfileField.FullName])
                assertEquals(Visibility(null, included), normalized.visibility[field])
            }
            valid(field, "名".repeat(200))
            invalid(field, "名".repeat(201), "A\nB", "\uD800")
        }
    }
}
