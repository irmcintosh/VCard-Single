package com.mackseoul.vcardnative.domain.sharing

import com.mackseoul.vcardnative.domain.profile.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

class ContactExportTest {
    @Before fun configureHosts() { ProfileValidator.hostValidator = AsciiHostValidator }

    private fun profile(
        values: Map<ProfileField, String> = mapOf(ProfileField.FullName to "Example Person"),
        export: Set<ProfileField> = emptySet(),
        show: Boolean = true,
        phoneRegion: String = "US",
        includeCountryCode: Boolean = true,
    ): Profile {
        val visibility = ProfileDraft.empty().visibility.mapValues { (field, value) ->
            if (field == ProfileField.FullName || value.includeInContactExport == null) value
            else Visibility(if (field in structuredNameFields) null else show, field in export)
        }
        val draft = requireNotNull(ProfileValidator.validate(
            ProfileDraft(
                values, visibility,
                phoneRegion = phoneRegion,
                includeCountryCodeWhenSharing = includeCountryCode,
            ),
        ).normalized)
        return Profile(
            UUID.randomUUID(), draft.values, draft.visibility,
            phoneRegion = draft.phoneRegion,
            includeCountryCodeWhenSharing = draft.includeCountryCodeWhenSharing,
            phoneNumberE164 = draft.phoneNumberE164,
        )
    }

    private fun logicalLines(bytes: ByteArray): List<String> {
        assertFalse(bytes.take(3) == listOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()))
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        assertTrue(text.endsWith("\r\n"))
        text.split("\r\n").dropLast(1).forEach {
            assertTrue("Physical line exceeds 75 bytes", it.toByteArray(Charsets.UTF_8).size <= 75)
            assertFalse(it.contains('\r') || it.contains('\n'))
        }
        return text.replace(Regex("\r\n[ \\t]"), "").split("\r\n").dropLast(1)
    }

    private fun decodeText(text: String): String = buildString {
        var i = 0
        while (i < text.length) {
            val c = text[i++]
            if (c != '\\') append(c) else {
                require(i < text.length)
                append(when (val escaped = text[i++]) {
                    'n', 'N' -> '\n'
                    '\\', ',', ';' -> escaped
                    else -> error("Unexpected vCard TEXT escape")
                })
            }
        }
    }

    @Test fun nameOnlyExactDeterministicUtf8FramingAndEmptyStructuredName() {
        val p = profile()
        val expected = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Example Person\r\nN:;;;;\r\nEND:VCARD\r\n"
        val result = ContactExport.from(p)
        assertArrayEquals(expected.toByteArray(Charsets.UTF_8), result.bytes)
        assertArrayEquals(result.bytes, ContactExport.from(p.copy(profileId = UUID.randomUUID())).bytes)
        assertEquals(listOf(ProfileField.FullName), result.rows.single().fields)
        val exposed = result.bytes
        exposed.fill(0)
        assertArrayEquals(expected.toByteArray(Charsets.UTF_8), result.bytes)
    }

    @Test fun structuredNameUsesExactFamilyGivenAdditionalOrderBeforeUnchangedFullName() {
        val result = ContactExport.from(profile(mapOf(
            ProfileField.FullName to "VCARD TEST Standard",
            ProfileField.FirstName to "VCARD",
            ProfileField.MiddleName to "TEST",
            ProfileField.LastName to "Standard",
        ), structuredNameFields.toSet()))
        assertEquals(listOf("BEGIN:VCARD", "VERSION:3.0", "N:Standard;VCARD;TEST;;",
            "FN:VCARD TEST Standard", "END:VCARD"), logicalLines(result.bytes))
        assertEquals(listOf(ProfileField.FullName, ProfileField.FirstName, ProfileField.MiddleName, ProfileField.LastName),
            result.rows.map { it.fields.single() })
        assertEquals(listOf("VCARD TEST Standard", "VCARD", "TEST", "Standard"), result.rows.map { it.value })
    }

    @Test fun generatedFullNameAndStructuredNameExportAtApprovedCodePointLimit() {
        val first = "😀".repeat(200)
        val middle = "Zoë," + "M".repeat(196)
        val last = "O'".repeat(100)
        val generated = listOf(first, middle, last).joinToString(" ")
        assertEquals(602, generated.codePointCount(0, generated.length))
        val result = ContactExport.from(profile(mapOf(
            ProfileField.FullName to generated,
            ProfileField.FirstName to first,
            ProfileField.MiddleName to middle,
            ProfileField.LastName to last,
        ), structuredNameFields.toSet()))
        val lines = logicalLines(result.bytes)

        assertTrue(lines.contains("FN:${ContactExport.escapeText(generated)}"))
        assertTrue(lines.contains(
            "N:${ContactExport.escapeText(last)};${ContactExport.escapeText(first)};${ContactExport.escapeText(middle)};;",
        ))
    }

    @Test fun partialComponentsPreserveFiveSlotsAndExcludedNamesNeverLeak() {
        val values = mapOf(ProfileField.FullName to "Unsplit Full Name",
            ProfileField.FirstName to "Given", ProfileField.MiddleName to "Additional", ProfileField.LastName to "Family")
        for (mask in 0..7) {
            val selected = structuredNameFields.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
            val result = ContactExport.from(profile(values, selected))
            val components = listOf(ProfileField.LastName, ProfileField.FirstName, ProfileField.MiddleName)
                .map { if (it in selected) values.getValue(it) else "" } + listOf("", "")
            assertEquals("N:${components.joinToString(";")}", logicalLines(result.bytes).single { it.startsWith("N:") })
            assertTrue(logicalLines(result.bytes).contains("FN:Unsplit Full Name"))
            structuredNameFields.forEach { field ->
                assertEquals(field in selected, result.rows.any { field in it.fields })
                assertEquals(field in selected, String(result.bytes, Charsets.UTF_8).contains(values.getValue(field)))
            }
        }
        assertArrayEquals(ContactExport.from(profile()).bytes, ContactExport.from(profile(
            values + (ProfileField.FullName to "Example Person"))).bytes)
    }

    @Test fun structuredUnicodePunctuationNonWesternNamesAndFoldingRoundTripWithoutGuessing() {
        for (parts in listOf(
            listOf("名".repeat(160), "Zoë, A;B\\C".repeat(8), "مرحبا"),
            listOf("王", "小明", ""),
            listOf("", "Sukarno", ""),
        )) {
            val values = structuredNameFields.zip(parts).toMap() + (ProfileField.FullName to "Original Full Identity")
            val result = ContactExport.from(profile(values, structuredNameFields.toSet()))
            val n = logicalLines(result.bytes).single { it.startsWith("N:") }.substringAfter(':')
            val components = n.split(Regex("(?<!\\\\);"))
            assertEquals(5, components.size)
            assertEquals(listOf(parts[2], parts[0], parts[1], "", ""), components.map(::decodeText))
            assertTrue(logicalLines(result.bytes).contains("FN:Original Full Identity"))
        }
    }

    @Test fun exactAllowlistedOrderUnicodeEscapingPhoneEmailUrlsAndIndependentNotes() {
        val values = mapOf(
            ProfileField.FullName to "Zoë 中文 😀 مرحبا",
            ProfileField.Organization to "Research, Inc.; Unit\\One",
            ProfileField.ProfessionalTitle to "Engineer: Research",
            ProfileField.Phone to "+1 (202) 555-0123",
            ProfileField.Email to "Ada+tag@Example.INVALID",
            ProfileField.Website to "http://example.invalid/a,b;c?x=%2F",
            ProfileField.LinkedIn to "https://example.invalid/linked",
            ProfileField.GitHub to "https://example.invalid/git",
            ProfileField.OtherProfessional to "https://example.invalid/other",
            ProfileField.Location to "Example City",
            ProfileField.Biography to "Line, one;\nNext\\line\nEND:VCARD\nTEL:+999",
            ProfileField.HostedProfileUrl to "https://example.invalid/never-export",
        )
        val p = profile(values, textFields.toSet())
        val result = ContactExport.from(p)
        val lines = logicalLines(result.bytes)
        assertEquals(listOf("BEGIN:VCARD", "VERSION:3.0", "FN", "N", "ORG", "TITLE", "TEL",
            "EMAIL;TYPE=INTERNET", "URL", "URL", "URL", "URL", "NOTE", "END:VCARD"),
            lines.map { if (it in setOf("BEGIN:VCARD", "END:VCARD", "VERSION:3.0")) it else it.substringBefore(':') })
        assertEquals(p.values[ProfileField.FullName], decodeText(lines[2].substringAfter(':')))
        assertEquals(";;;;", lines[3].substringAfter(':'))
        assertEquals(p.values[ProfileField.Organization], decodeText(lines[4].substringAfter(':')))
        assertEquals("TEL:+1 202-555-0123", lines[6])
        assertEquals("EMAIL;TYPE=INTERNET:Ada+tag@example.invalid", lines[7])
        assertEquals("URL:http://example.invalid/a,b;c?x=%2F", lines[8])
        assertEquals("Location: Example City\nBiography: ${p.values[ProfileField.Biography]}",
            decodeText(lines[12].substringAfter(':')))
        assertEquals(1, lines.count { it == "END:VCARD" })
        assertFalse(String(result.bytes, Charsets.UTF_8).contains("never-export"))
    }

    @Test fun phoneFormattingPreferenceIsSharedByPreviewAndVCardWithoutChangingInclusion() {
        val values = mapOf(
            ProfileField.FullName to "Ada",
            ProfileField.Phone to "020 7031 3000",
        )
        val international = ContactExport.from(profile(
            values, setOf(ProfileField.Phone), show = false, phoneRegion = "GB",
        ))
        assertEquals("+44 20 7031 3000", international.rows.single { ProfileField.Phone in it.fields }.value)
        assertTrue(logicalLines(international.bytes).contains("TEL:+44 20 7031 3000"))

        val national = ContactExport.from(profile(
            values, setOf(ProfileField.Phone), show = true, phoneRegion = "GB", includeCountryCode = false,
        ))
        assertEquals("020 7031 3000", national.rows.single { ProfileField.Phone in it.fields }.value)
        assertTrue(logicalLines(national.bytes).contains("TEL:020 7031 3000"))
        assertEquals(international.rows.map { it.fields }, national.rows.map { it.fields })
    }

    @Test fun allFourCardAndExportFlagCombinationsPreserveIndependentPrivacy() {
        for (show in listOf(false, true)) for (include in listOf(false, true)) {
            val p = profile(mapOf(ProfileField.FullName to "Identity", ProfileField.Email to "only@example.invalid"),
                if (include) setOf(ProfileField.Email) else emptySet(), show)
            val result = ContactExport.from(p)
            assertEquals(include, logicalLines(result.bytes).any { it.startsWith("EMAIL") })
            assertEquals(include, result.rows.any { ProfileField.Email in it.fields })
        }
    }

    @Test fun eachOptionalSlotIsFilteredBeforeCompositePropertiesOrPreview() {
        val optional = mapOf(
            ProfileField.Organization to "Private Organization",
            ProfileField.ProfessionalTitle to "Private Title",
            ProfileField.Phone to "+12025550123",
            ProfileField.Email to "private@example.invalid",
            ProfileField.Website to "http://example.invalid/site",
            ProfileField.LinkedIn to "https://example.invalid/linkedin",
            ProfileField.GitHub to "https://example.invalid/github",
            ProfileField.OtherProfessional to "https://example.invalid/other",
            ProfileField.Location to "Private Location",
            ProfileField.Biography to "Private Biography",
        )
        for (excluded in optional.keys) {
            val result = ContactExport.from(profile(optional + (ProfileField.FullName to "Identity"), optional.keys - excluded))
            assertFalse(result.rows.any { excluded in it.fields })
            assertFalse(String(result.bytes, Charsets.UTF_8).contains(optional.getValue(excluded)))
        }
        val locationOnly = logicalLines(ContactExport.from(profile(
            mapOf(ProfileField.FullName to "Identity", ProfileField.Location to "City", ProfileField.Biography to "Private"),
            setOf(ProfileField.Location),
        )).bytes)
        assertEquals("NOTE:Location: City", locationOnly.single { it.startsWith("NOTE:") })
    }

    @Test fun socialUrlsRemainRepeatedAndOrderedAfterWebsiteFiltering() {
        val url = "https://example.invalid/same"
        val p = profile(mapOf(ProfileField.FullName to "Identity", ProfileField.Website to url,
            ProfileField.LinkedIn to url, ProfileField.GitHub to url, ProfileField.OtherProfessional to url),
            setOf(ProfileField.Website, ProfileField.GitHub, ProfileField.OtherProfessional))
        val result = ContactExport.from(p)
        assertEquals(3, logicalLines(result.bytes).count { it.startsWith("URL:") })
        assertEquals(
            listOf(ProfileField.Website, ProfileField.GitHub, ProfileField.OtherProfessional),
            result.rows.filter { it.value == url }.map { it.fields.single() },
        )
    }

    @Test fun multibyteFoldsRoundTripWithoutSplittingEscapesOrPercentTriplets() {
        val p = profile(mapOf(ProfileField.FullName to "😀".repeat(160),
            ProfileField.Biography to ("漢字,;\\\n".repeat(250)),
            ProfileField.Website to "https://example.invalid/" + "%E4%B8%AD".repeat(100)),
            setOf(ProfileField.Biography, ProfileField.Website))
        val result = ContactExport.from(p)
        val lines = logicalLines(result.bytes)
        assertEquals(p.values[ProfileField.FullName], decodeText(lines.single { it.startsWith("FN:") }.substringAfter(':')))
        assertEquals("Biography: ${p.values[ProfileField.Biography]}",
            decodeText(lines.single { it.startsWith("NOTE:") }.substringAfter(':')))
        assertEquals("URL:${p.values[ProfileField.Website]}", lines.single { it.startsWith("URL:") })
        val physical = String(result.bytes, Charsets.UTF_8).split("\r\n").dropLast(1)
        physical.forEach {
            val trailingSlashes = it.reversed().takeWhile { c -> c == '\\' }.length
            assertEquals(0, trailingSlashes % 2)
            assertFalse(it.endsWith('%'))
            assertFalse(it.matches(Regex(".*%[0-9A-Fa-f]$")))
        }
    }

    @Test fun missingFieldsAndUnsupportedAssetsNeverCreateProperties() {
        val p = profile().copy(profileImage = ImageAsset(UUID.randomUUID(), "image/png", 1, 1, 1, "0".repeat(64)),
            publicKey = PublicKeyAsset(UUID.randomUUID(), "openpgp", 6, 27, "application/pgp-keys", 1, "0".repeat(64), "PRIVATE"))
        val result = ContactExport.from(p)
        assertEquals(5, logicalLines(result.bytes).size)
        assertFalse(String(result.bytes, Charsets.UTF_8).contains("PRIVATE"))
        assertEquals(1, result.rows.size)
    }

    @Test fun invalidSavedTextVisibilityAndUnicodeFailWithoutEchoingPrivateData() {
        val p = profile()
        val bad = listOf(p.copy(values = emptyMap()),
            p.copy(values = p.values + (ProfileField.FullName to "Private\r\nTEL:+999")),
            p.copy(values = p.values + (ProfileField.FullName to "Private\uD800")),
            p.copy(values = p.values + (ProfileField.FullName to "A\u0301da")),
            p.copy(visibility = p.visibility - ProfileField.Email),
            p.copy(visibility = p.visibility + (ProfileField.FullName to Visibility(true, false))))
        bad.forEach {
            try { ContactExport.from(it); fail("Invalid profile exported") } catch (error: ContactExportException) {
                assertFalse(error.message!!.contains("Private"))
            }
        }
    }

    @Test fun exact64KiBCapIncludesDelimitersAndNeverTruncates() {
        val lines = List(32768) { "" }
        assertEquals(65_536, ContactExport.encodeLines(lines).size)
        try { ContactExport.encodeLines(lines + ""); fail("Accepted more than 64 KiB") }
        catch (_: ContactExportException) { }
        try { ContactExport.encodeLines(listOf("FN:" + "😀".repeat(17000))); fail("Accepted folded oversized output") }
        catch (_: ContactExportException) { }
    }
}
