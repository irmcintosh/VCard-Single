package com.mackseoul.vcardnative.domain.sharing

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.DecoderResult
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.decoder.Decoder
import com.google.zxing.qrcode.detector.Detector
import com.mackseoul.vcardnative.data.sharing.ContactQrEncoder
import com.mackseoul.vcardnative.data.sharing.QuickShareQrGenerator
import com.mackseoul.vcardnative.data.sharing.QuickShareQrResult
import com.mackseoul.vcardnative.domain.profile.AsciiHostValidator
import com.mackseoul.vcardnative.domain.profile.Profile
import com.mackseoul.vcardnative.domain.profile.ProfileDraft
import com.mackseoul.vcardnative.domain.profile.ProfileField
import com.mackseoul.vcardnative.domain.profile.ProfileValidator
import com.mackseoul.vcardnative.domain.profile.Visibility
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.time.LocalDateTime
import java.util.Random
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream

class QrPngTest {
    private class Decoded(val side: Int, val bitDepth: Int, val colorType: Int, val white: BooleanArray, val chunks: List<String>)

    @Before fun configureHosts() { ProfileValidator.hostValidator = AsciiHostValidator }

    private fun read(bytes: ByteArray): Decoded {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        assertArrayEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10), ByteArray(8).also(input::readFully))
        var side = 0
        var depth = -1
        var color = -1
        val chunks = mutableListOf<String>()
        val compressed = ByteArrayOutputStream()
        while (chunks.lastOrNull() != "IEND") {
            val length = input.readInt()
            val name = ByteArray(4).also(input::readFully)
            val content = ByteArray(length).also(input::readFully)
            assertEquals("CRC", CRC32().apply { update(name); update(content) }.value.toInt(), input.readInt())
            val type = String(name, Charsets.US_ASCII)
            chunks += type
            when (type) {
                "IHDR" -> DataInputStream(ByteArrayInputStream(content)).apply {
                    side = readInt()
                    assertEquals(side, readInt())
                    depth = readUnsignedByte()
                    color = readUnsignedByte()
                    assertEquals(0, readUnsignedByte())
                    assertEquals(0, readUnsignedByte())
                    assertEquals(0, readUnsignedByte())
                }
                "IDAT" -> compressed.write(content)
            }
        }
        assertEquals("No trailing bytes", 0, input.available())
        val rowBytes = (side + 7) / 8
        val white = BooleanArray(side * side)
        DataInputStream(InflaterInputStream(ByteArrayInputStream(compressed.toByteArray()))).use { raw ->
            for (y in 0 until side) {
                assertEquals("Filter", 0, raw.readUnsignedByte())
                val row = ByteArray(rowBytes).also(raw::readFully)
                for (x in 0 until side) white[y * side + x] = row[x / 8].toInt() and (0x80 ushr (x % 8)) != 0
            }
            assertEquals(-1, raw.read())
        }
        return Decoded(side, depth, color, white, chunks)
    }

    private fun scan(png: ByteArray): DecoderResult {
        val image = read(png)
        val pixels = IntArray(image.white.size) { if (image.white[it]) 0xffffff else 0 }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.side, image.side, pixels)))
        return Decoder().decode(Detector(bitmap.blackMatrix).detect().bits)
    }

    private fun randomQr(version: Int, seed: Long): ContactQr {
        val side = 17 + 4 * version
        val random = Random(seed)
        return ContactQr(version, BooleanArray(side * side) { random.nextBoolean() })
    }

    private fun profile(values: Map<ProfileField, String>, exported: Set<ProfileField> = emptySet()): Profile {
        val draft = ProfileDraft.empty().let { empty ->
            empty.copy(values = values, visibility = empty.visibility + exported.associateWith { Visibility(true, true) })
        }
        val valid = requireNotNull(ProfileValidator.validate(draft).normalized)
        return Profile(
            profileId = UUID.randomUUID(),
            values = valid.values,
            visibility = valid.visibility,
            phoneRegion = valid.phoneRegion,
            includeCountryCodeWhenSharing = valid.includeCountryCodeWhenSharing,
            phoneNumberE164 = valid.phoneNumberE164,
        )
    }

    @Test fun pngIsValidOneBitGrayscaleWithExactModuleMapAndFourModuleWhiteQuietZone() {
        for (version in listOf(1, 10, 40)) {
            val qr = randomQr(version, version.toLong())
            val module = QrPng.modulePixels(qr.side)
            val image = read(QrPng.encode(qr))
            assertEquals(listOf("IHDR", "IDAT", "IEND"), image.chunks)
            assertEquals(1, image.bitDepth)
            assertEquals(0, image.colorType)
            assertEquals((qr.side + 8) * module, image.side)
            val quiet = 4 * module
            for (y in 0 until image.side) for (x in 0 until image.side) {
                val inSymbol = x >= quiet && y >= quiet && x < quiet + qr.side * module && y < quiet + qr.side * module
                val black = inSymbol && qr.isBlack((x - quiet) / module, (y - quiet) / module)
                assertEquals("v$version pixel $x,$y", !black, image.white[y * image.side + x])
            }
        }
    }

    @Test fun quietZoneIsNotDoubledForRealEncoderOutput() {
        val qr = ContactQrEncoder.encodeHostedProfileUrl("https://example.com/card")
        val module = QrPng.modulePixels(qr.side)
        val image = read(QrPng.encode(qr))
        // The encoder emits the bare symbol: its top-left finder corner is black and sits exactly 4 modules in.
        assertTrue(qr.isBlack(0, 0))
        assertFalse(image.white[(4 * module) * image.side + 4 * module])
        assertTrue(image.white[(4 * module - 1) * image.side + 4 * module - 1])
        assertTrue((0 until 4 * module).all { image.white[it * image.side + it] })
    }

    @Test fun everyVersionUsesIntegerModulesNearTwelveHundredPixelsWithinTheBoundedSize() {
        var largest = 0
        for (version in 1..40) {
            val qr = randomQr(version, 99L + version)
            val module = QrPng.modulePixels(qr.side)
            assertTrue(module >= QrPng.MIN_MODULE_PIXELS)
            assertEquals((qr.side + 8) * module, QrPng.imagePixels(qr))
            // Integer modules: the largest whole-module size not exceeding 1200 px (1038–1199 px across v1–40).
            assertTrue("v$version ${QrPng.imagePixels(qr)}",
                QrPng.imagePixels(qr) in 1200 - (qr.side + 8) + 1..1200 && QrPng.imagePixels(qr) >= 1038)
            val bytes = QrPng.encode(qr)
            assertTrue(QrPng.isEncodedPng(bytes))
            largest = maxOf(largest, bytes.size)
        }
        // Random matrices are a worst case for compression; real codes are far smaller.
        assertTrue("largest PNG $largest bytes", largest <= QrPng.MAX_BYTES)
        val uncompressedBound = 1200 * (1200 / 8 + 1) + 1200 / 16_000 * 5 + 64
        assertTrue(uncompressedBound < QrPng.MAX_BYTES)
        println("QrPng largest random-matrix PNG: $largest bytes (limit ${QrPng.MAX_BYTES})")
    }

    @Test fun encodingIsDeterministicAndDoesNotAlterTheQr() {
        val qr = randomQr(7, 7)
        val before = BooleanArray(qr.side * qr.side) { qr.isBlack(it % qr.side, it / qr.side) }
        assertArrayEquals(QrPng.encode(qr), QrPng.encode(qr))
        assertArrayEquals(before, BooleanArray(qr.side * qr.side) { qr.isBlack(it % qr.side, it / qr.side) })
    }

    @Test fun primaryContactPngScansToExactUnicodeVcardBytes() {
        val export = ContactExport.from(profile(
            mapOf(ProfileField.FullName to "Zoë 名前 😀 Example", ProfileField.Organization to "Café; Ünïcode, Ltd",
                ProfileField.Email to "zoe@example.com", ProfileField.Phone to "+1 202 555 0142"),
            exported = setOf(ProfileField.Organization, ProfileField.Email, ProfileField.Phone),
        ))
        val qr = ContactQrEncoder.encode(export)
        val decoded = scan(QrPng.encode(qr))
        assertArrayEquals(export.bytes, decoded.byteSegments.single())
        assertEquals(String(export.bytes, Charsets.UTF_8), decoded.text)
    }

    @Test fun highestDensityQrPngScansToExactPayload() {
        val line = "NOTE:Ünïcode høst ✓ " + "x".repeat(40) + "\r\n"
        val payload = buildString {
            append("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Dense Example\r\n")
            while (toString().toByteArray().size + line.toByteArray().size + 11 < 2_300) append(line)
            append("END:VCARD\r\n")
        }.toByteArray(Charsets.UTF_8)
        val qr = ContactQrEncoder.encodePayload(payload)
        assertTrue("version ${qr.version}", qr.version >= 39)
        val decoded = scan(QrPng.encode(qr))
        assertArrayEquals(payload, decoded.byteSegments.single())
    }

    @Test fun customSharePngScansToExactlyTheSelectedFields() {
        val profile = profile(mapOf(ProfileField.FullName to "Selected Person", ProfileField.Email to "only@example.com",
            ProfileField.Phone to "+1 202 555 0142", ProfileField.Organization to "Hidden Org"))
        val result = QuickShareQrGenerator.generate(profile, setOf(ProfileField.Email)) as QuickShareQrResult.QrAvailable
        val decoded = scan(QrPng.encode(result.qr))
        assertArrayEquals(result.export.bytes, decoded.byteSegments.single())
        assertTrue(decoded.text.contains("only@example.com"))
        assertFalse(decoded.text.contains("555"))
        assertFalse(decoded.text.contains("Hidden Org"))
    }

    @Test fun hostedProfilePngScansToTheExactHttpsUrl() {
        for (url in listOf("https://example.com/Card/%2f?Case=Exact", "https://example.com/Zoë")) {
            val decoded = scan(QrPng.encode(ContactQrEncoder.encodeHostedProfileUrl(url)))
            assertEquals(url, decoded.text)
            assertFalse(decoded.text.contains("BEGIN:VCARD"))
        }
    }

    @Test fun fileNamesCarryContextTimestampAndRandomSuffixWithSafeCharacters() {
        val time = LocalDateTime.of(2026, 11, 4, 18, 18, 39, 123_000_000)
        assertEquals("VCard-Native-Contact-QR-20261104-181839-123-a1b2c3d4.png",
            QrImageNames.fileName(QrImageContext.PrimaryContact, time, "a1b2c3d4"))
        assertEquals("VCard-Native-Custom-Share-QR-20261104-181839-123-a1b2c3d4.png",
            QrImageNames.fileName(QrImageContext.CustomShare, time, "a1b2c3d4"))
        assertEquals("VCard-Native-Hosted-Profile-QR-20261104-181839-123-a1b2c3d4.png",
            QrImageNames.fileName(QrImageContext.HostedProfile, time, "a1b2c3d4"))
        val names = (1..2_000).map { QrImageNames.fileName(QrImageContext.PrimaryContact, time) }
        assertEquals("Same-millisecond saves must not collide", names.size, names.toSet().size)
        names.forEach {
            assertTrue(it, QrImageNames.isValid(it))
            assertTrue(it.matches(Regex("[A-Za-z0-9.-]+")))
        }
        for (bad in listOf("A1B2C3D4", "a1b2c3", "../x/../y", "a1b2c3d4/")) {
            try { QrImageNames.fileName(QrImageContext.PrimaryContact, time, bad); throw AssertionError(bad) }
            catch (_: IllegalArgumentException) { }
        }
        assertFalse(QrImageNames.isValid("VCard-Native-Contact-QR-20261104-181839-123-a1b2c3d4.png/../x.png"))
        assertNotEquals(QrImageNames.randomSuffix(), QrImageNames.randomSuffix())
    }

    @Test fun onlyBoundedPngSignedBytesAreAccepted() {
        val png = QrPng.encode(randomQr(1, 1))
        assertTrue(QrPng.isEncodedPng(png))
        assertFalse(QrPng.isEncodedPng(ByteArray(0)))
        assertFalse(QrPng.isEncodedPng("BEGIN:VCARD".toByteArray()))
        assertFalse(QrPng.isEncodedPng(png.copyOf(8)))
        assertFalse(QrPng.isEncodedPng(png.copyOf(QrPng.MAX_BYTES + 1)))
    }
}
