package com.mackseoul.vcardnative.data.sharing

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.decoder.Decoder
import com.google.zxing.qrcode.detector.Detector
import com.mackseoul.vcardnative.domain.profile.*
import com.mackseoul.vcardnative.domain.sharing.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

class ContactCompatibilityPackageTest {
    private data class Fixture(
        val id: String,
        val values: Map<ProfileField, String>,
        val included: Set<ProfileField>,
        val expectedRows: List<ContactExportRow>,
        val properties: List<String>,
        val region: String = "US",
        val countryCode: Boolean = true,
        val hidden: Set<ProfileField> = emptySet(),
        val failure: QrFailure? = null,
        val geometryFailure: Boolean = false,
    )

    private fun row(field: ProfileField, value: String) = ContactExportRow(listOf(field), value)
    private fun fixture(
        id: String, name: String, extras: Map<ProfileField, String> = emptyMap(),
        rows: List<ContactExportRow> = emptyList(), properties: List<String> = emptyList(),
        included: Set<ProfileField> = extras.keys, region: String = "US", countryCode: Boolean = true,
        hidden: Set<ProfileField> = emptySet(), failure: QrFailure? = null, geometryFailure: Boolean = false,
        escapedName: String = name,
    ) = Fixture(
        id, mapOf(ProfileField.FullName to name) + extras, included,
        listOf(row(ProfileField.FullName, name)) + rows,
        listOf("BEGIN:VCARD", "VERSION:3.0", "FN:$escapedName", "N:;;;;") + properties + "END:VCARD",
        region, countryCode, hidden, failure, geometryFailure,
    )

    private fun fixtures(): List<Fixture> {
        val phone = "020 7946 0123"
        val unicodeName = "VCARD TEST Zoë 中文 😀 مرحبا"
        val unicodeBio = "Line, one; back\\slash\nSecond line: café."
        val large = "L".repeat(1100)
        return listOf(
            fixture("01-standard", "VCARD TEST Standard", linkedMapOf(
                ProfileField.Organization to "Example Research",
                ProfileField.ProfessionalTitle to "Test Engineer",
                ProfileField.Phone to "+1 (202) 555-0123",
                ProfileField.Email to "fixture@Example.INVALID",
                ProfileField.Website to "https://example.invalid/work",
                ProfileField.LinkedIn to "https://example.invalid/work",
                ProfileField.GitHub to "https://example.invalid/code",
                ProfileField.OtherProfessional to "https://example.invalid/other",
                ProfileField.Location to "Example City",
                ProfileField.Biography to "Synthetic contact. Do not call.",
            ), listOf(
                row(ProfileField.Organization, "Example Research"),
                row(ProfileField.ProfessionalTitle, "Test Engineer"),
                row(ProfileField.Phone, "+1 202-555-0123"),
                row(ProfileField.Email, "fixture@example.invalid"),
                row(ProfileField.Website, "https://example.invalid/work"),
                row(ProfileField.LinkedIn, "https://example.invalid/work"),
                row(ProfileField.GitHub, "https://example.invalid/code"),
                row(ProfileField.OtherProfessional, "https://example.invalid/other"),
                row(ProfileField.Location, "Example City"),
                row(ProfileField.Biography, "Synthetic contact. Do not call."),
            ), listOf(
                "ORG:Example Research", "TITLE:Test Engineer", "TEL:+1 202-555-0123",
                "EMAIL;TYPE=INTERNET:fixture@example.invalid", "URL:https://example.invalid/work",
                "URL:https://example.invalid/work",
                "URL:https://example.invalid/code", "URL:https://example.invalid/other",
                "NOTE:Location: Example City\\nBiography: Synthetic contact. Do not call.",
            )),
            fixture("02-international", "VCARD TEST International",
                mapOf(ProfileField.Phone to phone), listOf(row(ProfileField.Phone, "+44 20 7946 0123")),
                listOf("TEL:+44 20 7946 0123"), region = "GB"),
            fixture("03-national", "VCARD TEST National",
                mapOf(ProfileField.Phone to phone), listOf(row(ProfileField.Phone, phone)),
                listOf("TEL:$phone"), region = "GB", countryCode = false),
            fixture("04-unicode", unicodeName, linkedMapOf(
                ProfileField.Organization to "Research, Inc.; Unit\\One",
                ProfileField.ProfessionalTitle to "Engineer: Research",
                ProfileField.Website to "https://example.invalid/caf%C3%A9?x=%2F",
                ProfileField.Biography to unicodeBio,
            ), listOf(
                row(ProfileField.Organization, "Research, Inc.; Unit\\One"),
                row(ProfileField.ProfessionalTitle, "Engineer: Research"),
                row(ProfileField.Website, "https://example.invalid/caf%C3%A9?x=%2F"),
                row(ProfileField.Biography, unicodeBio),
            ), listOf(
                "ORG:Research\\, Inc.\\; Unit\\\\One", "TITLE:Engineer: Research",
                "URL:https://example.invalid/caf%C3%A9?x=%2F",
                "NOTE:Biography: Line\\, one\\; back\\\\slash\\nSecond line: café.",
            )),
            fixture("05-privacy", "VCARD TEST Privacy", linkedMapOf(
                ProfileField.Organization to "SYNTHETIC_PRIVATE_ORG",
                ProfileField.ProfessionalTitle to "Public Test Title",
                ProfileField.Email to "export-only@example.invalid",
                ProfileField.Biography to "SYNTHETIC_PRIVATE_BIO",
                ProfileField.Location to "SYNTHETIC_PRIVATE_LOCATION",
                ProfileField.Website to "https://example.invalid/private",
                ProfileField.HostedProfileUrl to "https://example.invalid/hosted-never-contact",
            ), listOf(
                row(ProfileField.ProfessionalTitle, "Public Test Title"),
                row(ProfileField.Email, "export-only@example.invalid"),
            ), listOf("TITLE:Public Test Title", "EMAIL;TYPE=INTERNET:export-only@example.invalid"),
                included = setOf(ProfileField.ProfessionalTitle, ProfileField.Email),
                hidden = setOf(ProfileField.Email, ProfileField.Biography)),
            fixture("06-large", "VCARD TEST Large", mapOf(ProfileField.Biography to large),
                listOf(row(ProfileField.Biography, large)), listOf("NOTE:Biography: $large")),
            fixture("07-encoding-oversize", "VCARD TEST Capacity", linkedMapOf(
                ProfileField.Organization to "O".repeat(160),
                ProfileField.ProfessionalTitle to "T".repeat(160),
                ProfileField.Biography to "B".repeat(2048),
            ), listOf(
                row(ProfileField.Organization, "O".repeat(160)),
                row(ProfileField.ProfessionalTitle, "T".repeat(160)),
                row(ProfileField.Biography, "B".repeat(2048)),
            ), listOf("ORG:${"O".repeat(160)}", "TITLE:${"T".repeat(160)}", "NOTE:Biography: ${"B".repeat(2048)}"),
                failure = QrFailure.EncodingCapacity),
            fixture("08-resource-oversize", "VCARD TEST Resource", mapOf(ProfileField.Biography to "😀".repeat(1200)),
                listOf(row(ProfileField.Biography, "😀".repeat(1200))),
                listOf("NOTE:Biography: ${"😀".repeat(1200)}"), failure = QrFailure.PayloadLimit),
            fixture("09-mononym", "VCARDTESTMononym"),
            fixture("10-geometry", "VCARD TEST Geometry", mapOf(ProfileField.Biography to "G".repeat(1850)),
                listOf(row(ProfileField.Biography, "G".repeat(1850))),
                listOf("NOTE:Biography: ${"G".repeat(1850)}"), geometryFailure = true),
        )
    }

    private fun profile(f: Fixture): Profile {
        val visibility = ProfileDraft.empty().visibility.mapValues { (field, default) ->
            if (default.includeInContactExport == null || field == ProfileField.FullName) default
            else Visibility(if (field in structuredNameFields) null else field !in f.hidden, field in f.included)
        }
        val result = ProfileValidator.validate(ProfileDraft(
            f.values, visibility, phoneRegion = f.region, includeCountryCodeWhenSharing = f.countryCode,
        ), AsciiHostValidator)
        assertTrue("${f.id}: ${result.errors}", result.errors.isEmpty())
        val valid = requireNotNull(result.normalized)
        return Profile(
            UUID.nameUUIDFromBytes(f.id.toByteArray(Charsets.UTF_8)), valid.values, valid.visibility,
            phoneRegion = valid.phoneRegion, includeCountryCodeWhenSharing = valid.includeCountryCodeWhenSharing,
            phoneNumberE164 = valid.phoneNumberE164,
        )
    }

    @Test fun approvedStructuredNamesMatchCandidate01AndPreserveOriginalNotesBytes() {
        val fixture = fixtures().first()
        val original = profile(fixture)
        val structured = original.copy(
            values = original.values + mapOf(ProfileField.FirstName to "VCARD",
                ProfileField.MiddleName to "TEST", ProfileField.LastName to "Standard"),
            visibility = original.visibility + structuredNameFields.associateWith { Visibility(null, true) },
        )
        val expected = (fixture.properties.joinToString("\r\n") + "\r\n")
            .replace("FN:VCARD TEST Standard\r\nN:;;;;", "N:Standard;VCARD;TEST;;\r\nFN:VCARD TEST Standard")
            .toByteArray(Charsets.UTF_8)
        val export = ContactExport.from(structured)
        assertArrayEquals(expected, export.bytes)
        assertEquals("c015206350c7b2f155011beffaa20bc513a500cdb128fbb7a1eb4e56d887f099", sha(export.bytes))
        assertTrue(String(export.bytes, Charsets.UTF_8).contains(
            "NOTE:Location: Example City\\nBiography: Synthetic contact. Do not call.\r\n",
        ))
        val qr = ContactQrEncoder.encode(export)
        val decoded = Decoder().decode(com.google.zxing.common.BitMatrix(qr.side).apply {
            for (y in 0 until qr.side) for (x in 0 until qr.side) if (qr.isBlack(x, y)) set(x, y)
        })
        assertArrayEquals(expected, decoded.byteSegments.single())
    }

    @Test fun validatesFixturesAndOptionallyGeneratesPhysicalDevicePackage() {
        val output = System.getenv("VCARD_COMPAT_OUTPUT")?.let(::File)
        if (output != null) assertTrue(output.isDirectory || output.mkdirs())
        val matrix = mutableListOf(
            "# Generated expected-results matrix", "",
            "All Android/iOS outcomes are HYPOTHESES / NOT RUN. Exact expectations are in each expected.json.",
            "QR payload bytes equal vcard-native-contact.vcf, including CRLF/folds. payload.txt preserves those bytes.", "",
            "| Fixture | Bytes | QR version | App panel at density 3, max 320dp | Android/iOS hypothesis | Exact expectations |",
            "|---|---:|---:|---|---|---|",
        )
        val trials = mutableListOf(listOf(
            "run_id", "fixture", "platform", "path", "device_manufacturer_model", "os_version", "camera_scanner_app_version",
            "contacts_app_version", "contact_account", "sender_app_version", "display_source_size_brightness_distance",
            "file_sha256", "qr_recognition", "import_save", "name_display_sorting", "classification",
            "evidence_refs", "notes",
        ))
        val comparisons = mutableListOf(listOf(
            "run_id", "fixture", "platform", "path", "source_field", "expected_value", "actual_value",
            "classification", "evidence_ref", "notes",
        ))
        val stats = mutableListOf<String>()
        fixtures().forEach { f ->
            val p = profile(f)
            val export = ContactExport.from(p)
            assertEquals(f.id, f.expectedRows, export.rows)
            val bytes = export.bytes
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            assertFalse(text.startsWith("\uFEFF"))
            assertTrue(text.endsWith("\r\n"))
            val physical = text.split("\r\n").dropLast(1)
            physical.forEach { line ->
                assertTrue(line.toByteArray(Charsets.UTF_8).size <= 75)
                assertFalse(line.contains('\r') || line.contains('\n'))
            }
            assertEquals(f.id, f.properties, text.replace(Regex("\r\n[ \\t]"), "").split("\r\n").dropLast(1))
            assertTrue(bytes.size <= ContactExport.MAX_BYTES)
            assertArrayEquals(bytes, ContactExport.from(p.copy(profileId = UUID.randomUUID())).bytes)
            if (f.id == "05-privacy") {
                listOf("SYNTHETIC_PRIVATE", "/private", "hosted-never-contact").forEach {
                    assertFalse(text.contains(it))
                }
                val descriptor = ImageAsset(UUID(0, 1), "image/png", 1, 1, 1, "0".repeat(64))
                assertArrayEquals(bytes, ContactExport.from(p.copy(profileImage = descriptor, coverImage = descriptor)).bytes)
            }
            var qr: ContactQr? = null
            try {
                qr = ContactQrEncoder.encode(export)
                assertNull("${f.id} should fail", f.failure)
            } catch (error: ContactQrException) {
                assertEquals(f.id, f.failure, error.failure)
            }
            if (f.failure == QrFailure.EncodingCapacity) assertTrue(bytes.size <= 4096)
            if (f.failure == QrFailure.PayloadLimit) assertTrue(bytes.size > 4096)
            val encodedQr = qr
            var panel: QrGeometry? = null
            if (encodedQr != null) {
                val image = image(encodedQr, 8, 64)
                val imageBytes = png(image)
                val reread = readPng(imageBytes)
                assertArrayEquals(image.pixels, reread.pixels)
                val decoded = decodeImage(reread)
                assertArrayEquals(bytes, decoded.byteSegments.single())
                assertEquals(text, decoded.text)
                assertEquals("M", decoded.ecLevel)
                val first = decoded.rawBytes[0].toInt() and 255
                if (bytes.any { it.toInt() and 128 != 0 }) {
                    assertEquals(7, first shr 4)
                    assertEquals(26, ((first and 15) shl 4) or ((decoded.rawBytes[1].toInt() and 255) shr 4))
                } else assertEquals(4, first shr 4)
                try {
                    panel = encodedQr.geometry(960, 3.0)
                    assertFalse("${f.id}: geometry must fail", f.geometryFailure)
                } catch (error: ContactQrException) {
                    assertTrue(f.geometryFailure)
                    assertEquals(QrFailure.Geometry, error.failure)
                }
                if (f.id == "06-large") {
                    assertTrue(bytes.size >= 1100)
                    assertTrue(encodedQr.version >= 20)
                    assertNotNull(panel)
                }
                output?.resolve(f.id)?.let { dir ->
                    assertTrue(dir.isDirectory || dir.mkdirs())
                    dir.resolve("qr-laboratory.png").writeBytes(imageBytes)
                    panel?.let {
                        val appPanel = image(encodedQr, it.modulePixels,
                            it.symbolOrigin - 4 * it.modulePixels, it.panelPixels)
                        val panelBytes = png(appPanel)
                        assertArrayEquals(bytes, decodeImage(readPng(panelBytes)).byteSegments.single())
                        dir.resolve("qr-panel-density3.png").writeBytes(panelBytes)
                    }
                }
            }
            val qrStatus = f.failure?.name ?: "ENCODED"
            val geometry = when {
                encodedQr == null -> "Not attempted"
                panel == null -> "Geometry rejection"
                else -> "${panel.panelPixels}px, ${panel.modulePixels}px/module"
            }
            stats += "${f.id}: ${bytes.size} bytes, $qrStatus, version ${encodedQr?.version ?: "-"}, $geometry"
            matrix += "| ${f.id} | ${bytes.size} | ${encodedQr?.version ?: "-"} | $geometry | VCF: hypothesized import; QR: " +
                (if (f.failure != null) "no QR expected" else if (f.geometryFailure) "app panel rejects; lab scan unverified"
                else "recognition/save unverified") + " | [expected.json](${f.id}/expected.json) |"
            for (platform in listOf("Android", "iOS")) {
                for (path in listOf("native-camera-qr", "direct-vcf", "native-sharesheet-vcf")) {
                    val run = "${f.id}-$platform-$path"
                    trials += listOf(run, f.id, platform, path) + List(11) { "" } + listOf("NOT_RUN", "", "")
                    val fieldExpectations = f.expectedRows.map { it.fields.joinToString("+") { field -> field.key } to it.value } +
                        p.values.filterKeys { field -> f.expectedRows.none { field in it.fields } }
                            .map { (field, _) -> field.key to "ABSENT FROM CONTACT EXPORT" } +
                        listOf("structured-name N" to ";;;; (empty; FN must supply display identity)",
                            "PHOTO/LOGO/KEY/UID/ADR/GEO" to "ABSENT FROM CONTACT EXPORT")
                    fieldExpectations.forEach { (field, value) ->
                        comparisons += listOf(run, f.id, platform, path, field, value, "", "NOT_RUN", "", "")
                    }
                }
            }
            output?.resolve(f.id)?.let { dir ->
                assertTrue(dir.isDirectory || dir.mkdirs())
                dir.resolve(ContactExport.FILE_NAME).writeBytes(bytes)
                dir.resolve("payload.txt").writeBytes(bytes)
                dir.resolve("input.json").writeText(inputJson(f, p), Charsets.UTF_8)
                dir.resolve("expected.json").writeText(
                    """{"fixture":${json(f.id)},"previewRows":${rowsJson(f.expectedRows)},"unfoldedProperties":${strings(f.properties)},"vcfBytes":${bytes.size},"sha256":${json(sha(bytes))},"qrStatus":${json(qrStatus)},"qrVersion":${encodedQr?.version ?: "null"},"qrPayloadFile":"payload.txt","appPanelDensity3":${json(geometry)},"recipientStatus":"HYPOTHESIS_NOT_TESTED"}""" + "\n",
                    Charsets.UTF_8,
                )
            }
        }
        output?.let {
            it.resolve("EXPECTED_RESULTS.md").writeText(matrix.joinToString("\n") + "\n", Charsets.UTF_8)
            it.resolve("results.csv").writeText(csv(trials), Charsets.UTF_8)
            it.resolve("field-comparison.csv").writeText(csv(comparisons), Charsets.UTF_8)
            it.resolve("AUTOMATED_RESULTS.txt").writeText(
                "10 valid synthetic profiles; exact preview/property assertions, UTF-8/CRLF/folds, deterministic export, privacy/photo exclusion,\n" +
                    "PNG QR decode to exact bytes, BYTE/ECI/M, capacity/resource/geometry separation. No physical-device tests.\n" +
                    stats.joinToString("\n") + "\n", Charsets.UTF_8,
            )
        }
    }

    private data class Raster(val side: Int, val pixels: IntArray)

    @Test fun offlineQrExperimentsPreserveExactPayloadBytes() {
        val output = System.getenv("VCARD_OFFLINE_COMPARISON_OUTPUT")?.let(::File)
        val base = String(ContactExport.from(profile(fixtures().first())).bytes, Charsets.UTF_8)
            .replace("\r\nN:;;;;\r\n", "\r\nN:Standard;VCARD;TEST;;\r\n")
        val reordered = base.replace(
            "FN:VCARD TEST Standard\r\nN:Standard;VCARD;TEST;;",
            "N:Standard;VCARD;TEST;;\r\nFN:VCARD TEST Standard",
        )
        val four = base.replace("VERSION:3.0", "VERSION:4.0")
            .replace("EMAIL;TYPE=INTERNET:", "EMAIL:")
        val fnOnly = four.replace("N:Standard;VCARD;TEST;;\r\n", "")
        val mecard = "MECARD:N:VCARD TEST Standard;TEL:+1 202-555-0123;EMAIL:fixture@example.invalid;" +
            "URL:https://example.invalid/work;URL:https://example.invalid/code;URL:https://example.invalid/other;" +
            "NOTE:Organization\\: Example Research\\nTitle\\: Test Engineer\\n" +
            "Location\\: Example City\\nBiography\\: Synthetic contact. Do not call.;;"
        data class Experiment(val id: String, val text: String, val difference: String, val eci: Boolean = false)
        val experiments = listOf(
            Experiment("01-V3-N-BEFORE-FN", reordered, "Compare with 00: only N and FN ordering changes."),
            Experiment("00-V3-STRUCTURED-CONTROL", base, "Step 4C Variant C byte-identical control."),
            Experiment("02-V3-ASCII-ECI26", base, "Compare with 00: identical payload; explicit UTF-8 ECI26 in QR."),
            Experiment("03-V4-STRUCTURED", four, "Compare with 00: version 4.0 plus required email parameter mapping; same text TEL and known N."),
            Experiment("04-V4-FN-ONLY", fnOnly, "Compare with 03: omit optional N; no synthetic or guessed name parts."),
            Experiment("05-MECARD-DIAGNOSTIC", mecard, "Legacy offline representation; ORG/TITLE preserved only inside NOTE, no middle-name component."),
        ).map { if (it.id == "02-V3-ASCII-ECI26") it.copy(eci = true) else it }
        output?.let {
            assertTrue(it.isDirectory || it.mkdirs())
            assertFalse("Existing worksheets must not be overwritten.", it.resolve("results.csv").exists())
        }
        val hashes = mutableListOf<String>()
        val summary = mutableListOf(
            "PASS: 6 controlled offline QR fixtures, PNG round-trip equals exact payload; no physical trials.",
            "All physical-device results NOT_RUN. No production or old-fixture changes.",
        )
        val results = mutableListOf(listOf(
            "fixture", "platform", "device_model", "os_version", "scanner_app_version", "contacts_app_version",
            "recognized", "create_new_offered", "first_name", "middle_name", "last_name", "display_name",
            "organization", "title", "phone", "email", "urls", "notes", "saved_reopened_preserved",
            "new_contact_not_merged", "offline_confirmed", "evidence", "classification", "comments",
        ))
        experiments.forEach { f ->
            val bytes = f.text.toByteArray(Charsets.UTF_8)
            assertTrue(bytes.size <= ContactQr.MAX_PAYLOAD_BYTES)
            val qr = if (!f.eci) ContactQrEncoder.encodePayload(bytes) else {
                val code = com.google.zxing.qrcode.encoder.Encoder.encode(
                    f.text, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
                    mapOf(com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8"),
                )
                assertEquals(com.google.zxing.qrcode.decoder.Mode.BYTE, code.mode)
                val matrix = requireNotNull(code.matrix)
                ContactQr(code.version.versionNumber, BooleanArray(matrix.width * matrix.height) {
                    matrix.get(it % matrix.width, it / matrix.width).toInt() == 1
                })
            }
            val raster = image(qr, 10, 40)
            val png = png(raster)
            val decoded = decodeImage(readPng(png))
            assertArrayEquals(bytes, decoded.byteSegments.single())
            assertEquals(f.text, decoded.text)
            assertEquals("M", decoded.ecLevel)
            val first = decoded.rawBytes[0].toInt() and 255
            if (f.eci) {
                assertEquals(7, first shr 4)
                assertEquals(26, ((first and 15) shl 4) or ((decoded.rawBytes[1].toInt() and 255) shr 4))
            } else assertEquals(4, first shr 4)
            val vcard = !f.id.startsWith("05")
            if (vcard) {
                assertTrue(f.text.startsWith("BEGIN:VCARD\r\nVERSION:"))
                assertTrue(f.text.endsWith("END:VCARD\r\n"))
                val lines = f.text.split("\r\n").dropLast(1)
                lines.forEach { assertTrue(it.toByteArray(Charsets.UTF_8).size <= 75) }
                assertEquals(1, lines.count { it == "FN:VCARD TEST Standard" })
                for (property in listOf("ORG:", "TITLE:", "TEL:", "URL:", "NOTE:")) {
                    assertEquals(
                        base.split("\r\n").filter { it.startsWith(property) },
                        lines.filter { it.startsWith(property) },
                    )
                }
                assertTrue(lines.contains(if (f.id.startsWith("03") || f.id.startsWith("04"))
                    "EMAIL:fixture@example.invalid" else "EMAIL;TYPE=INTERNET:fixture@example.invalid"))
            } else {
                val parsed = com.google.zxing.client.result.ResultParser.parseResult(com.google.zxing.Result(
                    f.text, bytes, null, com.google.zxing.BarcodeFormat.QR_CODE,
                )) as com.google.zxing.client.result.AddressBookParsedResult
                assertEquals("VCARD TEST Standard", parsed.names.single())
                assertEquals("+1 202-555-0123", parsed.phoneNumbers.single())
                assertEquals("fixture@example.invalid", parsed.emails.single())
                assertEquals(3, parsed.getURLs().size)
                assertNull(parsed.org)
                assertTrue(parsed.note.contains("Example Research"))
            }
            val filename = if (vcard) "contact.vcf" else "payload.txt"
            hashes += "${sha(bytes)}  ${f.id}/$filename"
            hashes += "${sha(png)}  ${f.id}/qr.png"
            summary += "${f.id}: ${bytes.size} bytes; QR v${qr.version}; ${raster.side}px; SHA256 ${sha(bytes)}"
            for (platform in listOf("iPhone", "Android")) {
                results += listOf(f.id, platform) + List(20) { "" } + listOf("NOT_RUN", "")
            }
            output?.resolve(f.id)?.let { dir ->
                assertTrue(dir.isDirectory || dir.mkdirs())
                dir.resolve(filename).writeBytes(bytes)
                if (vcard) dir.resolve("payload.txt").writeBytes(bytes)
                dir.resolve("qr.png").writeBytes(png)
                dir.resolve("expected.json").writeText(
                    """{"fixture":${json(f.id)},"difference":${json(f.difference)},"payloadFile":${json(filename)},"payloadBytes":${bytes.size},"payloadSHA256":${json(sha(bytes))},"pngSHA256":${json(sha(png))},"qrVersion":${qr.version},"explicitAsciiECI26":${f.eci},"expectedDisplayName":"VCARD TEST Standard","expectedKnownComponents":${strings(if (f.id.startsWith("04") || !vcard) emptyList() else listOf("given=VCARD","additional=TEST","family=Standard"))},"physicalResult":"NOT_RUN","networkRequired":false}""" + "\n",
                    Charsets.UTF_8,
                )
            }
        }
        assertEquals(base, experiments.single { it.id.startsWith("02") }.text)
        assertEquals(base, reordered.replace(
            "N:Standard;VCARD;TEST;;\r\nFN:VCARD TEST Standard",
            "FN:VCARD TEST Standard\r\nN:Standard;VCARD;TEST;;",
        ))
        output?.let {
            it.resolve("SHA256SUMS.txt").writeText(hashes.joinToString("\n") + "\n", Charsets.UTF_8)
            it.resolve("VERIFICATION.txt").writeText(summary.joinToString("\n") + "\n", Charsets.UTF_8)
            it.resolve("results.csv").writeText(csv(results), Charsets.UTF_8)
        }
    }

    @Test fun controlledNameVariantsRoundTripWithoutChangingOtherProperties() {
        val output = System.getenv("VCARD_NAME_COMPARISON_OUTPUT")?.let(::File)
        val original = ContactExport.from(profile(fixtures().first())).bytes
        assertEquals("1011ea93ad17b27576228b847af8fa050c9ff9950d0475edbfaacb7a37288526", sha(original))
        val originalText = String(original, Charsets.UTF_8)
        val variants = linkedMapOf(
            "A-ORIGINAL" to listOf("", "", "", "", ""),
            "B-FULL-NAME-IN-FAMILY" to listOf("VCARD TEST Standard", "", "", "", ""),
            "C-EXPLICIT-SYNTHETIC" to listOf("Standard", "VCARD", "TEST", "", ""),
        )
        val hashes = mutableListOf<String>()
        val worksheet = mutableListOf(listOf(
            "variant", "platform", "path", "device_model", "os_version", "camera_scanner_app_version",
            "contacts_app_version", "account_type", "qr_recognition", "import_save", "display_name",
            "actual_family", "actual_given", "actual_additional", "actual_prefix", "actual_suffix",
            "company", "job_title", "phone", "email", "urls", "notes_newline_or_literal_escape",
            "new_contact_not_merged", "evidence_reference", "classification", "comments",
        ))
        output?.let {
            assertTrue(it.isDirectory || it.mkdirs())
            assertFalse("Preserve existing results before regenerating.", it.resolve("results.csv").exists())
        }
        variants.forEach { (label, components) ->
            val n = "N:${components.joinToString(";")}"
            val text = originalText.replace("\r\nN:;;;;\r\n", "\r\n$n\r\n")
            val bytes = text.toByteArray(Charsets.UTF_8)
            assertEquals(originalText, text.replace("\r\n$n\r\n", "\r\nN:;;;;\r\n"))
            assertEquals(1, text.split("\r\n").count { it.startsWith("N:") })
            assertTrue(text.contains("\r\nFN:VCARD TEST Standard\r\n"))
            assertTrue(text.contains("\r\nNOTE:Location: Example City\\nBiography: Synthetic contact. Do not call.\r\n"))
            assertTrue(text.endsWith("\r\n"))
            text.split("\r\n").forEach { assertTrue(it.toByteArray(Charsets.UTF_8).size <= 75) }
            val qr = ContactQrEncoder.encodePayload(bytes)
            val raster = image(qr, 10, 40)
            val png = png(raster)
            val decoded = decodeImage(readPng(png))
            assertArrayEquals(bytes, decoded.byteSegments.single())
            assertEquals(text, decoded.text)
            assertEquals("M", decoded.ecLevel)
            assertEquals(4, (decoded.rawBytes[0].toInt() and 255) shr 4)
            hashes += "${sha(bytes)}  $label/contact.vcf"
            hashes += "${sha(png)}  $label/qr.png"
            for (platform in listOf("Android", "iPhone")) for (path in listOf("native-camera-QR", "direct-VCF")) {
                worksheet += listOf(label, platform, path) + List(21) { "" } + listOf("NOT_RUN", "")
            }
            output?.resolve(label)?.let { dir ->
                assertTrue(dir.isDirectory || dir.mkdirs())
                dir.resolve("contact.vcf").writeBytes(bytes)
                dir.resolve("qr.png").writeBytes(png)
                dir.resolve("expected.json").writeText(
                    """{"variant":${json(label)},"FN":"VCARD TEST Standard","N":${json(n)},"componentsFamilyGivenAdditionalPrefixSuffix":${strings(components)},"vcfBytes":${bytes.size},"vcfSHA256":${json(sha(bytes))},"pngSHA256":${json(sha(png))},"qrVersion":${qr.version},"pngSidePixels":${raster.side},"qrDecodedBytesEqualVCF":true,"NOTE":"Location: Example City\\nBiography: Synthetic contact. Do not call.","physicalResults":"NOT_RUN","syntheticOnly":true}""" + "\n",
                    Charsets.UTF_8,
                )
            }
        }
        output?.let {
            it.resolve("SHA256SUMS.txt").writeText(hashes.joinToString("\n") + "\n", Charsets.UTF_8)
            it.resolve("results.csv").writeText(csv(worksheet), Charsets.UTF_8)
            it.resolve("VERIFICATION.txt").writeText(
                "PASS: three vCard 3.0 variants, exact PNG QR decode equals VCF bytes; only N differs.\n" +
                    "FN/ORG/TITLE/TEL/EMAIL/URLs/NOTE and CRLF unchanged; ASCII BYTE QR, correction M.\n" +
                    "12 physical-device trials NOT_RUN. C uses explicitly known synthetic components; never infer name parts.\n",
                Charsets.UTF_8,
            )
        }
    }

    private fun image(qr: ContactQr, module: Int, outer: Int, side: Int = (qr.side + 8) * module + 2 * outer): Raster {
        val pixels = IntArray(side * side)
        val origin = outer + 4 * module
        for (y in 0 until side) for (x in 0 until side) {
            val sx = (x - origin) / module
            val sy = (y - origin) / module
            val black = x >= origin && y >= origin && x < origin + qr.side * module &&
                y < origin + qr.side * module && qr.isBlack(sx, sy)
            pixels[y * side + x] = if (black) 0 else 0xffffff
        }
        return Raster(side, pixels)
    }

    private fun decodeImage(image: Raster) = Decoder().decode(Detector(BinaryBitmap(HybridBinarizer(
        RGBLuminanceSource(image.side, image.side, image.pixels),
    )).blackMatrix).detect().bits)

    private fun png(image: Raster): ByteArray {
        val output = ByteArrayOutputStream()
        val data = DataOutputStream(output)
        data.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        fun chunk(type: String, bytes: ByteArray) {
            val name = type.toByteArray(Charsets.US_ASCII)
            data.writeInt(bytes.size)
            data.write(name)
            data.write(bytes)
            val crc = CRC32().apply { update(name); update(bytes) }
            data.writeInt(crc.value.toInt())
        }
        val header = ByteArrayOutputStream()
        DataOutputStream(header).apply {
            writeInt(image.side); writeInt(image.side)
            write(byteArrayOf(8, 2, 0, 0, 0))
        }
        chunk("IHDR", header.toByteArray())
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { stream ->
            for (y in 0 until image.side) {
                stream.write(0)
                for (x in 0 until image.side) {
                    val value = image.pixels[y * image.side + x]
                    stream.write(value shr 16 and 255)
                    stream.write(value shr 8 and 255)
                    stream.write(value and 255)
                }
            }
        }
        chunk("IDAT", compressed.toByteArray())
        chunk("IEND", ByteArray(0))
        return output.toByteArray()
    }

    private fun readPng(bytes: ByteArray): Raster {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val signature = ByteArray(8).also(input::readFully)
        assertArrayEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10), signature)
        var side = 0
        val compressed = ByteArrayOutputStream()
        var ended = false
        while (!ended) {
            val length = input.readInt()
            require(length in 0..bytes.size)
            val name = ByteArray(4).also(input::readFully)
            val content = ByteArray(length).also(input::readFully)
            assertEquals(input.readInt(), CRC32().apply { update(name); update(content) }.value.toInt())
            when (String(name, Charsets.US_ASCII)) {
                "IHDR" -> {
                    val header = DataInputStream(ByteArrayInputStream(content))
                    side = header.readInt()
                    assertEquals(side, header.readInt())
                    assertArrayEquals(byteArrayOf(8, 2, 0, 0, 0), ByteArray(5).also(header::readFully))
                }
                "IDAT" -> compressed.write(content)
                "IEND" -> ended = true
                else -> error("Unexpected generated PNG chunk")
            }
        }
        assertEquals(0, input.available())
        require(side in 1..2048)
        val pixels = IntArray(side * side)
        DataInputStream(InflaterInputStream(ByteArrayInputStream(compressed.toByteArray()))).use { rgb ->
            for (y in 0 until side) {
                assertEquals(0, rgb.readUnsignedByte())
                for (x in 0 until side) pixels[y * side + x] =
                    (rgb.readUnsignedByte() shl 16) or (rgb.readUnsignedByte() shl 8) or rgb.readUnsignedByte()
            }
            assertEquals(-1, rgb.read())
        }
        return Raster(side, pixels)
    }

    private fun inputJson(f: Fixture, p: Profile): String =
        """{"fixture":${json(f.id)},"disposable":true,"phoneRegion":${json(p.phoneRegion)},"includeCountryCodeWhenSharing":${p.includeCountryCodeWhenSharing},"rawValues":{${f.values.entries.joinToString(",") { json(it.key.key) + ":" + json(it.value) }}},"normalizedValues":{${p.values.entries.joinToString(",") { json(it.key.key) + ":" + json(it.value) }}},"visibility":{${p.visibility.entries.joinToString(",") { (field, v) -> json(field.key) + """:{"showOnCard":${v.showOnCard},"includeInContactExport":${v.includeInContactExport}}""" }}},"note":"Fixture description only; not an A2 store or importable app backup."}""" + "\n"

    private fun rowsJson(rows: List<ContactExportRow>) = rows.joinToString(",", "[", "]") {
        """{"fields":${strings(it.fields.map { f -> f.key })},"value":${json(it.value)}}"""
    }
    private fun strings(values: List<String>) = values.joinToString(",", "[", "]", transform = ::json)
    private fun json(value: String): String = buildString {
        append('"')
        value.forEach { append(when (it) {
            '"' -> "\\\""
            '\\' -> "\\\\"
            '\n' -> "\\n"
            '\r' -> "\\r"
            '\t' -> "\\t"
            else -> it.toString()
        }) }
        append('"')
    }
    private fun csv(rows: List<List<String>>) = rows.joinToString("\r\n", postfix = "\r\n") { values ->
        values.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
