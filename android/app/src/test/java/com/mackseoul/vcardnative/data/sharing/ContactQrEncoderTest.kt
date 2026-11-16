package com.mackseoul.vcardnative.data.sharing

import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.Decoder
import com.mackseoul.vcardnative.domain.profile.*
import com.mackseoul.vcardnative.domain.sharing.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

class ContactQrEncoderTest {
    @Test fun hostedProfileQrContainsOnlyExactUrlIncludingCaseAndPercentEncoding() {
        for (url in listOf("https://example.com/Card/%2f?Case=Exact", "HTTPS://EXAMPLE.COM/CARD", "https://example.com/Zoë")) {
            val result = decode(ContactQrEncoder.encodeHostedProfileUrl(url))
            assertEquals(url, result.text)
            assertFalse(result.text.contains("BEGIN:VCARD"))
            assertEquals("M", result.ecLevel)
        }
        assertEquals("https://example.com/replacement",
            decode(ContactQrEncoder.encodeHostedProfileUrl("https://example.com/replacement")).text)
    }

    @Test fun hostedProfileQrRejectsMissingAndUnsafeUrls() {
        for (url in listOf("", " ", "http://example.com", "example.com", "javascript:alert(1)", "https:///card")) {
            assertThrows(IllegalArgumentException::class.java) { ContactQrEncoder.encodeHostedProfileUrl(url) }
        }
    }

    @Before fun configureHosts() { ProfileValidator.hostValidator = AsciiHostValidator }

    private fun export(name: String, biography: String? = null, includeBio: Boolean = false): ContactExport {
        val values = mapOf(ProfileField.FullName to name) +
            (biography?.let { mapOf(ProfileField.Biography to it) }.orEmpty())
        val draft = ProfileDraft.empty().copy(values = values,
            visibility = ProfileDraft.empty().visibility + (ProfileField.Biography to Visibility(false, includeBio)))
        val valid = requireNotNull(ProfileValidator.validate(draft).normalized)
        return ContactExport.from(Profile(UUID.randomUUID(), valid.values, valid.visibility))
    }

    private fun decode(qr: ContactQr) = Decoder().decode(BitMatrix(qr.side).apply {
        for (y in 0 until qr.side) for (x in 0 until qr.side) if (qr.isBlack(x, y)) set(x, y)
    })

    @Test fun structuredNamesUseExactVcfBytesIncludingUnicodeFoldingAndPrivacy() {
        val draft = requireNotNull(ProfileValidator.validate(ProfileDraft.empty().copy(
            values = mapOf(ProfileField.FullName to "Original Identity", ProfileField.FirstName to "名".repeat(160),
                ProfileField.MiddleName to "Private Middle", ProfileField.LastName to "Family;Name"),
            visibility = ProfileDraft.empty().visibility +
                (ProfileField.FirstName to Visibility(null, true)) +
                (ProfileField.LastName to Visibility(null, true)),
        )).normalized)
        val export = ContactExport.from(Profile(UUID.randomUUID(), draft.values, draft.visibility))
        val decoded = decode(ContactQrEncoder.encode(export))
        assertArrayEquals(export.bytes, decoded.byteSegments.single())
        assertEquals(String(export.bytes, Charsets.UTF_8), decoded.text)
        assertTrue(decoded.text.contains("N:Family\\;Name;"))
        assertFalse(decoded.text.contains("Private Middle"))
        assertTrue(decoded.text.contains("FN:Original Identity"))
    }

    @Test fun structuredNamesConsumeExactCapacityAndExclusionRestoresVersionFortyBoundary() {
        fun contact(bioLength: Int, namesIncluded: Boolean): ContactExport {
            val draft = requireNotNull(ProfileValidator.validate(ProfileDraft.empty().copy(
                values = mapOf(ProfileField.FullName to "Name", ProfileField.Biography to "b".repeat(bioLength),
                    ProfileField.FirstName to "g".repeat(160), ProfileField.MiddleName to "m".repeat(160),
                    ProfileField.LastName to "f".repeat(160)),
                visibility = ProfileDraft.empty().visibility + (ProfileField.Biography to Visibility(false, true)) +
                    structuredNameFields.associateWith { Visibility(null, namesIncluded) },
            )).normalized)
            return ContactExport.from(Profile(UUID.randomUUID(), draft.values, draft.visibility))
        }
        val lastFit = (1..2048).last { contact(it, true).bytes.size <= 2331 }
        val fitting = contact(lastFit, true)
        assertEquals(40, ContactQrEncoder.encode(fitting).version)
        assertArrayEquals(fitting.bytes, decode(ContactQrEncoder.encode(fitting)).byteSegments.single())
        val tooLarge = contact(lastFit + 1, true)
        assertTrue(tooLarge.bytes.size > 2331)
        try { ContactQrEncoder.encode(tooLarge); fail("Expected exact capacity failure") }
        catch (error: ContactQrException) { assertEquals(QrFailure.EncodingCapacity, error.failure) }
        val excluded = contact(lastFit + 1, false)
        assertTrue(excluded.bytes.size <= 2331)
        ContactQrEncoder.encode(excluded)
    }

    @Test fun exactAsciiPayloadByteModeNoEciCorrectionMAndDeterministicMatrix() {
        val export = export("Saved Example")
        val qr = ContactQrEncoder.encode(export)
        val decoded = decode(qr)
        assertEquals(String(export.bytes, Charsets.UTF_8), decoded.text)
        assertArrayEquals(export.bytes, decoded.byteSegments.single())
        assertEquals("M", decoded.ecLevel)
        assertEquals(4, (decoded.rawBytes[0].toInt() and 255) shr 4)
        val other = ContactQrEncoder.encode(export)
        assertEquals(qr.version, other.version)
        for (y in 0 until qr.side) for (x in 0 until qr.side) assertEquals(qr.isBlack(x, y), other.isBlack(x, y))
    }

    @Test fun unicodeUtf8Eci26ByteModeAndFullFoldedVcardRoundTrip() {
        val export = export("Zoë 中文 😀 مرحبا", "Line, one;\\\n" + "漢字😀".repeat(200), true)
        val decoded = decode(ContactQrEncoder.encode(export))
        assertEquals(String(export.bytes, Charsets.UTF_8), decoded.text)
        assertArrayEquals(export.bytes, decoded.byteSegments.single())
        val first = decoded.rawBytes[0].toInt() and 255
        val second = decoded.rawBytes[1].toInt() and 255
        assertEquals(7, first shr 4)
        assertEquals(26, ((first and 15) shl 4) or (second shr 4))
        assertEquals(4, second and 15)
    }

    @Test fun privacyFilteringUsesEntireUnchangedContactExportNotCardDisplayOrDraft() {
        val included = export("Saved Name", "Included independently", true)
        val excluded = export("Saved Name", "Private excluded biography", false)
        assertTrue(decode(ContactQrEncoder.encode(included)).text.contains("Included independently"))
        assertFalse(decode(ContactQrEncoder.encode(excluded)).text.contains("Private"))
        assertEquals(String(excluded.bytes, Charsets.UTF_8), decode(ContactQrEncoder.encode(excluded)).text)
    }

    @Test fun qrUsesTheSelectedInternationalOrNationalPhoneValue() {
        val visibility = ProfileDraft.empty().visibility +
            (ProfileField.Phone to Visibility(showOnCard = false, includeInContactExport = true))
        for ((includeCode, expected) in listOf(
            true to "TEL:+44 20 7031 3000",
            false to "TEL:020 7031 3000",
        )) {
            val draft = requireNotNull(ProfileValidator.validate(ProfileDraft.empty().copy(
                values = mapOf(
                    ProfileField.FullName to "Ada",
                    ProfileField.Phone to "020 7031 3000",
                ),
                visibility = visibility,
                phoneRegion = "GB",
                includeCountryCodeWhenSharing = includeCode,
            )).normalized)
            val profile = Profile(
                UUID.randomUUID(), draft.values, draft.visibility,
                phoneRegion = draft.phoneRegion,
                includeCountryCodeWhenSharing = draft.includeCountryCodeWhenSharing,
                phoneNumberE164 = draft.phoneNumberE164,
            )
            val export = ContactExport.from(profile)
            val qrPayload = decode(ContactQrEncoder.encode(export)).text
            assertTrue(qrPayload.contains(expected))
            assertEquals(String(export.bytes, Charsets.UTF_8), qrPayload)
        }
    }

    private fun rejects(bytes: ByteArray, expected: QrFailure) {
        try { ContactQrEncoder.encodePayload(bytes); fail("Expected bounded QR failure") }
        catch (error: ContactQrException) { assertEquals(expected, error.failure) }
    }

    @Test fun resourceGateUsesExactBytesAndIsDistinctFromEncodingCapacity() {
        rejects(ByteArray(4097) { 97 }, QrFailure.PayloadLimit)
        rejects(ByteArray(4096) { 97 }, QrFailure.EncodingCapacity)
        rejects(byteArrayOf(0xc3.toByte(), 0x28), QrFailure.Encoding)
        val huge = export("Name", "😀".repeat(1200), true)
        assertTrue(huge.bytes.size > 4096)
        try { ContactQrEncoder.encode(huge); fail() } catch (error: ContactQrException) {
            assertEquals(QrFailure.PayloadLimit, error.failure)
        }
    }

    @Test fun actualVersion40MBoundaryAndSmallestFittingVersionIncludeEciOverhead() {
        val maximum = ContactQrEncoder.encodePayload("a".repeat(2331).toByteArray(Charsets.UTF_8))
        assertEquals(40, maximum.version)
        rejects("a".repeat(2332).toByteArray(Charsets.UTF_8), QrFailure.EncodingCapacity)
        assertEquals(40, ContactQrEncoder.encodePayload(("é" + "a".repeat(2328)).toByteArray(Charsets.UTF_8)).version)
        rejects(("é" + "a".repeat(2329)).toByteArray(Charsets.UTF_8), QrFailure.EncodingCapacity)
        assertEquals(1, ContactQrEncoder.encodePayload("a".repeat(14).toByteArray()).version)
        assertEquals(2, ContactQrEncoder.encodePayload("a".repeat(15).toByteArray()).version)
    }

    @Test fun optionalExcludedLongFieldDoesNotConsumeQrCapacity() {
        val small = export("Saved Name", "😀".repeat(2048), false)
        val nameOnly = export("Saved Name")
        assertArrayEquals(nameOnly.bytes, small.bytes)
        assertEquals(ContactQrEncoder.encode(nameOnly).version, ContactQrEncoder.encode(small).version)
    }
}
