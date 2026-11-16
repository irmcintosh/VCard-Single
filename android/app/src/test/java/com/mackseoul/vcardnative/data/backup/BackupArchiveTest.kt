package com.mackseoul.vcardnative.data.backup

import com.mackseoul.vcardnative.data.keys.PublicKeyValidator
import com.mackseoul.vcardnative.data.profile.ProfileCodec
import com.mackseoul.vcardnative.data.profile.StrictJson
import com.mackseoul.vcardnative.data.profile.jsonObject
import com.mackseoul.vcardnative.data.profile.sha256
import com.mackseoul.vcardnative.domain.backup.BackupException
import com.mackseoul.vcardnative.domain.backup.BackupSnapshot
import com.mackseoul.vcardnative.domain.images.ImageStructureTest
import com.mackseoul.vcardnative.domain.profile.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.util.Base64
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupArchiveTest {
    private val time = Instant.parse("2026-10-31T12:34:56Z")

    @Before fun offlineHosts() { ProfileValidator.hostValidator = AsciiHostValidator }

    private fun data(): AppData {
        val values = mapOf(
            ProfileField.FullName to "Example Person",
            ProfileField.FirstName to "Example",
            ProfileField.MiddleName to "Synthetic",
            ProfileField.LastName to "Person",
            ProfileField.ProfessionalTitle to "Engineer",
            ProfileField.Organization to "Example Organization",
            ProfileField.Biography to "Hidden biography\nSecond line",
            ProfileField.Location to "Hidden City",
            ProfileField.Phone to "+1 650-253-0000",
            ProfileField.Email to "person@example.com",
            ProfileField.Website to "https://example.com/",
            ProfileField.LinkedIn to "https://linkedin.com/in/example",
            ProfileField.GitHub to "https://github.com/example",
            ProfileField.OtherProfessional to "https://example.com/other",
            ProfileField.HostedProfileUrl to "https://example.com/card",
        )
        val visibility = ProfileField.entries.associateWith { field ->
            when (field) {
                ProfileField.FullName, ProfileField.HostedProfileUrl,
                ProfileField.FirstName, ProfileField.MiddleName, ProfileField.LastName -> field.defaultVisibility()
                ProfileField.PublicKey, ProfileField.ProfileImage, ProfileField.CoverImage -> Visibility(false, null)
                else -> Visibility(false, false)
            }
        }
        val draft = requireNotNull(ProfileValidator.validate(ProfileDraft(values, visibility)).normalized)
        return AppData(profile = Profile(UUID.fromString("00000000-0000-4000-8000-000000000001"),
            draft.values, draft.visibility,
            phoneRegion = draft.phoneRegion,
            includeCountryCodeWhenSharing = draft.includeCountryCodeWhenSharing,
            phoneNumberE164 = draft.phoneNumberE164,
            socialProfiles = draft.socialProfiles), appearance = Appearance(Theme.light, Accent.amethyst),
            metadata = Metadata("2026-10-30T00:00:00Z", "2026-10-29T00:00:00Z"))
    }

    private fun snapshot(assets: Boolean = false, fixture: String = "valid-public.asc"): BackupSnapshot {
        var saved = data()
        val images = linkedMapOf<ImageRole, ByteArray>()
        var key: ByteArray? = null
        if (assets) {
            val bytes = ImageStructureTest.png()
            fun descriptor() = ImageAsset(UUID.randomUUID(), "image/png", bytes.size.toLong(),
                1, 1, sha256(bytes))
            val imported = PublicKeyValidator.import(resource(fixture))
            saved = saved.copy(profile = saved.profile.copy(profileImage = descriptor(),
                coverImage = descriptor(), publicKey = imported.asset))
            images[ImageRole.Portrait] = bytes
            images[ImageRole.Cover] = bytes.copyOf()
            key = imported.bytes
        }
        return BackupSnapshot(saved, images, key, time)
    }

    private fun resource(name: String) =
        requireNotNull(javaClass.getResourceAsStream("/openpgp/$name")).use { it.readBytes() }

    private fun reject(bytes: ByteArray) {
        assertThrows(BackupException::class.java) { BackupArchive.validate(bytes) }
    }

    private fun unpack(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes()
            }
        }
        return result
    }

    private fun pack(entries: Map<String, ByteArray>, compressed: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                val entry = ZipEntry(name)
                entry.setTimeLocal(LocalDateTime.of(1980, 1, 2, 0, 0))
                if (!compressed) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = entry.size
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun mime(name: String) = when {
        name.endsWith(".json") -> "application/json"
        name.endsWith(".png") -> "image/png"
        name.endsWith(".jpg") -> "image/jpeg"
        else -> "application/pgp-keys"
    }

    private fun inventory(entries: LinkedHashMap<String, ByteArray>) {
        val manifest = (StrictJson.decode(entries.getValue("manifest.json"), allowArrays = true) as Map<*, *>)
            .entries.associateTo(linkedMapOf()) { it.key as String to it.value }
        manifest["files"] = entries.filterKeys { it != "manifest.json" }.map { (name, bytes) ->
            linkedMapOf("path" to name, "mediaType" to mime(name), "byteLength" to bytes.size,
                "sha256" to sha256(bytes))
        }
        entries["manifest.json"] = StrictJson.encode(manifest)
    }

    private fun changedData(source: ByteArray, transform: (String) -> String): ByteArray {
        val entries = unpack(source)
        entries["data.json"] = transform(entries.getValue("data.json").toString(Charsets.UTF_8)).toByteArray()
        inventory(entries)
        return pack(entries)
    }

    private fun legacyBackup(version: Int, source: BackupSnapshot = snapshot()): ByteArray {
        require(version in 1..4)
        val entries = unpack(BackupArchive.create(source))
        val root = StrictJson.decode(entries.getValue("data.json"), allowArrays = true)
            .jsonObject("schemaVersion", "profile", "appearance", "metadata").toMutableMap()
        // Schemas 1-4 stored only theme and accent.
        val appearance = root.getValue("appearance")
            .jsonObject("theme", "accent", "cardStyle", "minimalItems")
        root["appearance"] = linkedMapOf("theme" to appearance["theme"], "accent" to appearance["accent"])
        val profile = (root.getValue("profile") as Map<*, *>).entries
            .associateTo(linkedMapOf()) { it.key as String to it.value }
        if (version <= 3) {
            val socialProfiles = profile.remove("socialProfiles") as List<Map<String, Any?>>
            val visibility = (profile.getValue("visibility") as Map<*, *>).entries
                .associateTo(linkedMapOf()) { it.key as String to it.value }
            profile["professionalLinks"] = legacySocialFields.associate { field ->
                field.key to socialProfiles.firstOrNull { it["legacyField"] == field.key }?.get("url")
            }
            legacySocialFields.forEach { field ->
                val entry = socialProfiles.firstOrNull { it["legacyField"] == field.key }
                visibility["professionalLinks.${field.key}"] = linkedMapOf(
                    "showOnCard" to (entry?.get("showOnCard") ?: field.defaultVisibility().showOnCard),
                    "includeInContactExport" to
                        (entry?.get("includeInContactExport") ?: field.defaultVisibility().includeInContactExport),
                )
            }
            profile["visibility"] = visibility
            if (version < 3) {
                structuredNameFields.forEach { field ->
                    profile.remove(field.key)
                    visibility.remove(field.key)
                }
                profile["visibility"] = visibility
            }
        }
        if (version == 1) {
            listOf("phoneRegion", "includeCountryCodeWhenSharing", "phoneNumberE164").forEach(profile::remove)
        }
        root["schemaVersion"] = version
        root["profile"] = profile
        entries["data.json"] = StrictJson.encode(root)

        val manifest = (StrictJson.decode(entries.getValue("manifest.json"), allowArrays = true) as Map<*, *>).entries
            .associateTo(linkedMapOf()) { it.key as String to it.value }
        manifest["appSchemaVersion"] = version
        entries["manifest.json"] = StrictJson.encode(manifest)
        inventory(entries)
        return pack(entries)
    }

    @Test fun allSavedFieldsHiddenValuesAppearanceMetadataAndAssetsRoundTripDeterministically() {
        for (source in listOf(snapshot(), snapshot(true), snapshot(true, "v6-ed25519-x25519.asc"),
            snapshot(true, "bc-BOB_CERT.asc"))) {
            val bytes = BackupArchive.create(source)
            assertArrayEquals(bytes, BackupArchive.create(source))
            val restored = BackupArchive.validate(bytes)
            assertEquals(source.data, restored.data)
            assertEquals(time, restored.createdAt)
            source.images.forEach { (role, content) -> assertArrayEquals(content, restored.images[role]) }
            assertArrayEquals(source.publicKey, restored.publicKey)
            assertArrayEquals(bytes, BackupArchive.create(BackupSnapshot(
                restored.data, restored.images, restored.publicKey, restored.createdAt)))
            val entries = unpack(bytes)
            assertEquals(if (source.images.isEmpty()) listOf("manifest.json", "data.json")
                else listOf("manifest.json", "data.json", "profile.png", "cover.png", "public-key.asc"),
                entries.keys.toList())
            val json = entries.getValue("data.json").toString(Charsets.UTF_8)
            assertTrue(json.contains("\"socialProfiles\":"))
            assertTrue(json.contains("\"platform\":\"linkedin\""))
            assertFalse(json.contains("\"fields\":"))
            assertFalse(json.contains("generationId"))
            assertFalse(json.contains("revision"))
            assertFalse(json.contains("usable"))
            assertEquals(20, u16(bytes, 4))
            assertEquals(0, u16(bytes, 6))
            assertEquals(0, u16(bytes, 8))
            assertEquals(0, u16(bytes, 10))
            assertEquals(33, u16(bytes, 12))
        }
    }

    @Test fun snapshotAndCandidateOwnAllMapsAndBytesWithoutExposingMutableContent() {
        val original = snapshot(true)
        val values = original.data.profile.values.toMutableMap()
        val visibility = original.data.profile.visibility.toMutableMap()
        val images = original.images.toMutableMap()
        val key = original.publicKey!!
        val owned = BackupSnapshot(original.data.copy(profile = original.data.profile.copy(
            values = values, visibility = visibility)), images, key, time)
        values.clear(); visibility.clear(); images.values.forEach { it.fill(0) }; images.clear(); key.fill(0)
        assertEquals(original.data, owned.data)
        assertArrayEquals(original.publicKey, owned.publicKey)
        owned.images.values.forEach { it.fill(0) }
        owned.publicKey!!.fill(0)
        val bytes = BackupArchive.create(owned)
        val candidate = BackupArchive.validate(bytes)
        bytes.fill(0)
        candidate.images.values.forEach { it.fill(0) }
        candidate.publicKey!!.fill(0)
        assertEquals(original.data, candidate.data)
        assertArrayEquals(original.publicKey, candidate.publicKey)
        assertArrayEquals(original.images[ImageRole.Portrait], candidate.images[ImageRole.Portrait])
        assertThrows(UnsupportedOperationException::class.java) {
            (candidate.data.profile.values as MutableMap).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (candidate.images as MutableMap).clear()
        }
    }

    @Test fun nameOnlyNullSlotsAndAllCanonicalJpegPngRoleVariantsRoundTrip() {
        val minimal = data().copy(profile = data().profile.copy(
            values = mapOf(ProfileField.FullName to "Example Person"),
            visibility = ProfileField.entries.associateWith { it.defaultVisibility() },
            phoneNumberE164 = null,
            socialProfiles = emptyList()))
        val plain = BackupArchive.validate(BackupArchive.create(BackupSnapshot(minimal, emptyMap(), null, time)))
        assertEquals(minimal, plain.data)
        assertTrue(plain.images.isEmpty())
        assertNull(plain.publicKey)
        // Synthetic one-pixel JPEG generated locally; no Android decoder or desktop API needed.
        val jpeg = Base64.getDecoder().decode(
                "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMUFRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBT/wAARCAABAAEDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD8qqKKKAP/2Q==")
        val png = ImageStructureTest.png()
        for ((portraitBytes, coverBytes) in listOf(jpeg to png, png to jpeg, jpeg to jpeg)) {
            fun asset(bytes: ByteArray) = ImageAsset(UUID.randomUUID(),
                if (bytes === jpeg) "image/jpeg" else "image/png", bytes.size.toLong(), 1, 1, sha256(bytes))
            val saved = minimal.copy(profile = minimal.profile.copy(
                profileImage = asset(portraitBytes), coverImage = asset(coverBytes)))
            val candidate = BackupArchive.validate(BackupArchive.create(BackupSnapshot(saved,
                mapOf(ImageRole.Portrait to portraitBytes, ImageRole.Cover to coverBytes), null, time)))
            assertEquals(saved, candidate.data)
            assertArrayEquals(portraitBytes, candidate.images[ImageRole.Portrait])
            assertArrayEquals(coverBytes, candidate.images[ImageRole.Cover])
        }
    }

    @Test fun interoperableStoredUtf8AndSafeDosUnixRegularAttributesAreAccepted() {
        val bytes = pack(unpack(BackupArchive.create(snapshot())))
        assertEquals(data(), BackupArchive.validate(bytes).data)
        for (attrs in listOf(0x20L, 0x21L, 0x81a40020L)) {
            val mutated = bytes.copyOf()
            central(mutated).forEach { offset ->
                set16(mutated, offset + 4, if (attrs > 65535) 0x314 else 20)
                set32(mutated, offset + 38, attrs)
            }
            assertEquals(data(), BackupArchive.validate(mutated).data)
        }
    }

    @Test fun versionOneBackupKeepsLegacyNumberAndDefaultsNewPhoneSettings() {
        val restored = BackupArchive.validate(legacyBackup(1)).data
        assertEquals(1, restored.schemaVersion)
        assertEquals("+1 650-253-0000", restored.value(ProfileField.Phone))
        assertEquals("+16502530000", restored.profile.phoneNumberE164)
        assertEquals("US", restored.profile.phoneRegion)
        assertTrue(restored.profile.includeCountryCodeWhenSharing)
        structuredNameFields.forEach {
            assertNull(restored.value(it))
            assertEquals(Visibility(null, false), restored.visibility(it))
        }
    }

    @Test fun versionTwoBackupPreservesRegionAndNationalExportPreference() {
        val source = data().let { it.copy(profile = it.profile.copy(values = it.profile.values - structuredNameFields.toSet())) }
        val draft = requireNotNull(ProfileValidator.validate(ProfileDraft(
            values = source.profile.values + (ProfileField.Phone to "020 7031 3000"),
            visibility = source.profile.visibility,
            phoneRegion = "GB",
            includeCountryCodeWhenSharing = false,
        )).normalized)
        val saved = source.copy(profile = source.profile.copy(
            values = draft.values,
            visibility = draft.visibility,
            phoneRegion = draft.phoneRegion,
            includeCountryCodeWhenSharing = draft.includeCountryCodeWhenSharing,
            phoneNumberE164 = draft.phoneNumberE164,
        ))
        val restored = BackupArchive.validate(legacyBackup(2, BackupSnapshot(saved, emptyMap(), null, time))).data
        assertEquals(saved.copy(
            schemaVersion = 2,
            profile = saved.profile.copy(socialProfiles = emptyList()),
        ), restored)
        assertEquals("GB", restored.profile.phoneRegion)
        assertFalse(restored.profile.includeCountryCodeWhenSharing)
        assertEquals("+442070313000", restored.profile.phoneNumberE164)
    }

    @Test fun versionThreeBackupMigratesSocialLinksOnceOnSchemaUpgrade() {
        val restored = BackupArchive.validate(legacyBackup(3)).data
        assertEquals(3, restored.schemaVersion)
        assertEquals("https://linkedin.com/in/example", restored.value(ProfileField.LinkedIn))
        assertEquals("https://github.com/example", restored.value(ProfileField.GitHub))
        assertEquals("https://example.com/other", restored.value(ProfileField.OtherProfessional))

        val upgraded = ProfileCodec.currentSchemaData(restored)
        val repeated = ProfileCodec.currentSchemaData(upgraded)
        assertEquals(AppData.CURRENT_SCHEMA_VERSION, upgraded.schemaVersion)
        assertEquals(listOf("linkedin", "github", "otherProfessional"), upgraded.profile.socialProfiles.map { it.platform })
        assertEquals(upgraded.profile.socialProfiles, repeated.profile.socialProfiles)
        assertEquals(Visibility(false, false), upgraded.visibility(ProfileField.LinkedIn))
    }

    private fun manifestJson(bytes: ByteArray) = unpack(bytes).getValue("manifest.json").toString(Charsets.UTF_8)
    private fun dataJson(bytes: ByteArray) = unpack(bytes).getValue("data.json").toString(Charsets.UTF_8)

    private fun withManifestVersion(source: ByteArray, version: Int): ByteArray {
        val entries = unpack(source)
        val manifest = (StrictJson.decode(entries.getValue("manifest.json"), allowArrays = true) as Map<*, *>).entries
            .associateTo(linkedMapOf()) { it.key as String to it.value }
        manifest["appSchemaVersion"] = version
        entries["manifest.json"] = StrictJson.encode(manifest)
        return pack(entries)
    }

    @Test fun schemaFourBackupRestoresAllDataAndAppearanceThenMigratesToClassicSchemaFive() {
        val source = snapshot(true)
        val bytes = legacyBackup(4, source)
        assertTrue(manifestJson(bytes).contains("\"appSchemaVersion\":4"))
        val legacyJson = dataJson(bytes)
        assertTrue(legacyJson.startsWith("{\"schemaVersion\":4,"))
        assertTrue(legacyJson.contains("\"appearance\":{\"theme\":\"light\",\"accent\":\"amethyst\"}"))
        assertFalse(legacyJson.contains("cardStyle"))
        assertFalse(legacyJson.contains("minimalItems"))

        val restored = BackupArchive.validate(bytes)
        assertEquals(4, restored.data.schemaVersion)
        assertEquals(source.data.copy(schemaVersion = 4), restored.data)
        assertEquals(Appearance(Theme.light, Accent.amethyst, CardStyle.Classic, emptySet()), restored.data.appearance)
        source.images.forEach { (role, content) -> assertArrayEquals(content, restored.images[role]) }
        assertArrayEquals(source.publicKey, restored.publicKey)

        val migrated = ProfileCodec.currentSchemaData(restored.data)
        assertEquals(5, migrated.schemaVersion)
        assertEquals(restored.data.profile, migrated.profile)
        assertEquals(restored.data.appearance, migrated.appearance)
        assertEquals(restored.data.metadata, migrated.metadata)

        val rewritten = BackupArchive.create(BackupSnapshot(
            restored.data, restored.images, restored.publicKey, restored.createdAt))
        assertTrue(manifestJson(rewritten).contains("\"appSchemaVersion\":5"))
        assertTrue(dataJson(rewritten).contains(
            "\"appearance\":{\"theme\":\"light\",\"accent\":\"amethyst\",\"cardStyle\":\"classic\",\"minimalItems\":[]}"))
        assertEquals(migrated, BackupArchive.validate(rewritten).data)
    }

    @Test fun schemaFiveBackupRoundTripsEveryStyleAndMinimalSelectionDeterministically() {
        val selections = listOf(
            emptySet(),
            setOf(MinimalItem.ProfessionalLinks),
            setOf(MinimalItem.HostedProfile, MinimalItem.ProfilePhoto),
            setOf(MinimalItem.Website, MinimalItem.Biography, MinimalItem.CoverImage),
            setOf(MinimalItem.HostedProfile, MinimalItem.ProfessionalLinks, MinimalItem.Email, MinimalItem.Phone),
        )
        for (style in CardStyle.entries) for (items in selections) {
            val base = snapshot(true)
            val appearance = Appearance(Theme.dark, Accent.jade, style, items)
            val source = BackupSnapshot(base.data.copy(appearance = appearance), base.images, base.publicKey, time)
            val bytes = BackupArchive.create(source)
            assertArrayEquals(bytes, BackupArchive.create(source))
            assertTrue(manifestJson(bytes).contains("\"appSchemaVersion\":5"))
            assertTrue(dataJson(bytes).startsWith("{\"schemaVersion\":5,"))
            val restored = BackupArchive.validate(bytes)
            assertEquals(source.data, restored.data)
            assertEquals(appearance, restored.data.appearance)
            assertArrayEquals(bytes, BackupArchive.create(BackupSnapshot(
                restored.data, restored.images, restored.publicKey, restored.createdAt)))
        }
        val unordered = BackupSnapshot(snapshot().data.copy(appearance = Appearance(Theme.dark, Accent.jade,
            CardStyle.Minimal, linkedSetOf(MinimalItem.HostedProfile, MinimalItem.Email, MinimalItem.Phone))),
            emptyMap(), null, time)
        assertTrue(dataJson(BackupArchive.create(unordered)).contains(
            "\"appearance\":{\"theme\":\"dark\",\"accent\":\"jade\",\"cardStyle\":\"minimal\"," +
                "\"minimalItems\":[\"phone\",\"email\",\"hostedProfile\"]}"))
    }

    @Test fun archiveAndEmbeddedSchemaVersionsMustAgreeAndAppearanceMustMatchSchema() {
        val schemaFour = legacyBackup(4)
        assertEquals(4, BackupArchive.validate(schemaFour).data.schemaVersion)
        reject(withManifestVersion(schemaFour, 5))
        val schemaFive = BackupArchive.create(snapshot())
        assertEquals(5, BackupArchive.validate(schemaFive).data.schemaVersion)
        reject(withManifestVersion(schemaFive, 4))
        // Schema 4 data must not carry schema 5 appearance fields.
        reject(changedData(schemaFour) {
            it.replace("\"accent\":\"amethyst\"}", "\"accent\":\"amethyst\",\"cardStyle\":\"classic\",\"minimalItems\":[]}")
        })
        // Schema 5 data must not omit them.
        reject(withManifestVersion(changedData(schemaFour) {
            it.replace("{\"schemaVersion\":4,", "{\"schemaVersion\":5,")
        }, 5))
        // No separate settings file is accepted alongside the existing entries.
        for (source in listOf(schemaFour, schemaFive)) {
            val entries = unpack(source)
            entries["settings.json"] = "{\"cardStyle\":\"minimal\"}".toByteArray()
            inventory(entries)
            reject(pack(entries))
        }
    }

    @Test fun strictDataSchemaRejectsHashConsistentInvalidFieldsTypesFlagsEnumsAndMetadata() {
        val source = BackupArchive.create(snapshot())
        val mutations: List<(String) -> String> = listOf(
            { it.replace("\"schemaVersion\":5", "\"schemaVersion\":1") },
            { it.replace("\"schemaVersion\":5", "\"schemaVersion\":4") },
            { it.replace("\"schemaVersion\":5", "\"schemaVersion\":6") },
            { it.replace("\"schemaVersion\":5", "\"schemaVersion\":5.0") },
            { it.replace("\"schemaVersion\":5", "\"schemaVersion\":null") },
            { it.replace("\"schemaVersion\":5", "\"schemaVersion\":5,\"unknown\":null") },
            { it.replace("\"fullName\":\"Example Person\"", "\"fullName\":null") },
            { it.replace("\"fullName\":\"Example Person\"", "\"fullName\":\" Example Person \"") },
            { it.replace("\"location\":\"Hidden City\"", "\"location\":[]") },
            { it.replace("\"phone\":\"+1 650-253-0000\"", "\"phone\":\"invalid\"") },
            { it.replace("\"email\":\"person@example.com\"", "\"email\":\"invalid\"") },
            { it.replace("\"location\":\"Hidden City\"", "\"location\":\"Hidden City\",\"location\":null") },
            { it.replace("\"location\":\"Hidden City\"", "\"location\":\"\\uD800\"") },
            { it.replace("\"platform\":\"github\"", "\"platform\":false") },
            { it.replace("\"label\":\"Other professional link\"", "\"label\":false") },
            { it.replace("\"socialProfiles\":[", "\"socialProfiles\":{") },
            { it.replace("\"fullName\":{\"showOnCard\":true", "\"fullName\":{\"showOnCard\":false") },
            { it.replace("\"location\":{\"showOnCard\":false", "\"location\":{\"showOnCard\":null") },
            { it.replace("\"publicKey\":{\"showOnCard\":false,\"includeInContactExport\":null}",
                "\"publicKey\":{\"showOnCard\":false,\"includeInContactExport\":true}") },
            { it.replace("\"hostedProfileUrl\":{\"showOnCard\":null", "\"hostedProfileUrl\":{\"showOnCard\":true") },
            { it.replace("\"includeInContactExport\":false", "\"includeInContactExport\":false,\"unknown\":false") },
            { it.replace("\"theme\":\"light\"", "\"theme\":\"system\"") },
            { it.replace("\"accent\":\"amethyst\"", "\"accent\":\"unknown\"") },
            { it.replace("\"cardStyle\":\"classic\"", "\"cardStyle\":\"compact\"") },
            { it.replace("\"cardStyle\":\"classic\"", "\"cardStyle\":\"Classic\"") },
            { it.replace("\"cardStyle\":\"classic\"", "\"cardStyle\":null") },
            { it.replace(",\"cardStyle\":\"classic\"", "") },
            { it.replace(",\"minimalItems\":[]", "") },
            { it.replace("\"minimalItems\":[]", "\"minimalItems\":null") },
            { it.replace("\"minimalItems\":[]", "\"minimalItems\":{}") },
            { it.replace("\"minimalItems\":[]", "\"minimalItems\":[\"unknown\"]") },
            { it.replace("\"minimalItems\":[]", "\"minimalItems\":[\"phone\",\"phone\"]") },
            { it.replace("\"minimalItems\":[]", "\"minimalItems\":[\"email\",\"phone\"]") },
            { it.replace("\"minimalItems\":[]",
                "\"minimalItems\":[\"profilePhoto\",\"coverImage\",\"professionalTitle\",\"organization\",\"biography\"]") },
            { it.replace("\"minimalItems\":[]", "\"minimalItems\":[],\"unknown\":null") },
            { it.replace("\"profileId\":\"00000000-0000-4000-8000-000000000001\"", "\"profileId\":\"bad\"") },
            { it.replace("\"createdAt\":\"2026-10-30T00:00:00Z\"", "\"createdAt\":\"not-time\"") },
            { it.replace("\"createdAt\":\"2026-10-30T00:00:00Z\"", "\"createdAt\":\"2026-10-30T00:00:00+00:00\"") },
            { "\uFEFF$it" },
            { "$it trailing" },
        )
        mutations.forEachIndexed { index, transform ->
            try { reject(changedData(source, transform)) } catch (e: AssertionError) {
                throw AssertionError("Data mutation $index accepted", e)
            }
        }
        val entries = unpack(source)
        entries["data.json"] = StrictJson.encode(ProfileCodec.encodeData(data()))
        inventory(entries)
        reject(pack(entries))
    }

    @Test fun strictManifestRejectsWrongOrderDuplicatesVersionsTypesMimeSizeHashesAndDepth() {
        val source = BackupArchive.create(snapshot(true))
        val mutations: List<(String) -> String> = listOf(
            { it.replace("vcard-native-backup", "unknown") },
            { it.replace("\"formatVersion\":1", "\"formatVersion\":2") },
            { it.replace("\"appSchemaVersion\":5", "\"appSchemaVersion\":1") },
            { it.replace("\"appSchemaVersion\":5", "\"appSchemaVersion\":4") },
            { it.replace("\"appSchemaVersion\":5", "\"appSchemaVersion\":6") },
            { it.replace("\"formatVersion\":1", "\"formatVersion\":1.0") },
            { it.replace("\"formatVersion\":1", "\"formatVersion\":true") },
            { it.replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1") },
            { it.replace("\"formatVersion\":1", "\"formatVersion\":1,\"unknown\":null") },
            { it.replace("\"createdAt\":\"$time\"", "\"createdAt\":\"bad\"") },
            { it.replace("\"mediaType\":\"application/json\"", "\"mediaType\":\"text/plain\"") },
            { it.replace("\"byteLength\":", "\"unknownLength\":") },
            { it.replace("\"sha256\":\"", "\"sha256\":\"A") },
            { it.replace("\"files\":[", "\"files\":{") },
            { it.replace("\"files\":[", "\"files\":" + "[".repeat(17)) },
        )
        mutations.forEach { transform ->
            val entries = unpack(source)
            entries["manifest.json"] = transform(entries.getValue("manifest.json").toString(Charsets.UTF_8)).toByteArray()
            reject(pack(entries))
        }
        val entries = unpack(source)
        val raw = StrictJson.decode(entries.getValue("manifest.json"), allowArrays = true) as Map<*, *>
        val manifest = raw.entries.associateTo(linkedMapOf()) { it.key as String to it.value }
        val files = manifest["files"] as List<*>
        for (bad in listOf(files.reversed(), files + files.first(), files.drop(1))) {
            manifest["files"] = bad
            entries["manifest.json"] = StrictJson.encode(manifest)
            reject(pack(entries))
        }
        val hashMismatch = unpack(source)
        hashMismatch["data.json"] = hashMismatch.getValue("data.json") + byteArrayOf(32)
        reject(pack(hashMismatch))
    }

    @Test fun assetInventoryRejectsMissingUnreferencedWrongVariantsDuplicateIdsAndForgedDescriptors() {
        val source = BackupArchive.create(snapshot(true))
        for (missing in listOf("data.json", "manifest.json", "profile.png", "cover.png", "public-key.asc")) {
            reject(pack(unpack(source).apply { remove(missing) }))
        }
        for (extra in listOf("profile.jpg", "cover.jpg", "../evil", "Profile.png", "folder/", "C:\\evil")) {
            reject(pack(unpack(source).apply { put(extra, byteArrayOf(1)) }))
        }
        for (field in listOf("profileImage", "coverImage", "publicKey")) {
            val start = "\"$field\":"
            reject(changedData(source) { json ->
                val i = json.indexOf(start) + start.length
                val end = json.indexOf('}', i)
                json.substring(0, i) + "null" + json.substring(end + 1)
            })
        }
        for ((from, to) in listOf(
            "\"width\":1" to "\"width\":2", "\"height\":1" to "\"height\":2",
            "\"canonicalizationPolicyVersion\":1" to "\"canonicalizationPolicyVersion\":2",
            "\"image/png\"" to "\"image/jpeg\"", "\"keyVersion\":4" to "\"keyVersion\":5",
            "\"primaryAlgorithm\":22" to "\"primaryAlgorithm\":27",
            "\"fingerprint\":\"" to "\"fingerprint\":\"A",
        )) reject(changedData(source) { it.replace(from, to) })
        val saved = snapshot(true)
        val duplicateIds = BackupArchive.create(saved)
        reject(changedData(duplicateIds) {
            it.replace(saved.data.profile.coverImage!!.assetId.toString(),
                saved.data.profile.profileImage!!.assetId.toString())
        })
        assertThrows(BackupException::class.java) {
            BackupArchive.create(BackupSnapshot(saved.data.copy(profile = saved.data.profile.copy(
                coverImage = saved.data.profile.coverImage!!.copy(assetId = saved.data.profile.profileImage!!.assetId))),
                saved.images, saved.publicKey, time))
        }
        assertThrows(BackupException::class.java) {
            BackupArchive.create(BackupSnapshot(saved.data, emptyMap(), saved.publicKey, time))
        }
        assertThrows(BackupException::class.java) {
            BackupArchive.create(BackupSnapshot(saved.data, saved.images, null, time))
        }
    }

    @Test fun hashConsistentMalformedImagesAndUnsupportedPrivateOrChangedKeysRejectEntireCandidate() {
        val source = BackupArchive.create(snapshot(true))
        for ((path, bad) in listOf(
            "profile.png" to ImageStructureTest.png(raw = byteArrayOf(0, 1, 2)),
            "profile.png" to ImageStructureTest.png(extraType = "tEXt"),
            "profile.png" to ImageStructureTest.png(width = 8193),
            "cover.png" to (ImageStructureTest.png() + byteArrayOf(0)),
            "public-key.asc" to resource("secret.asc"),
            "public-key.asc" to "-----BEGIN PGP PUBLIC KEY BLOCK-----\ninvalid\n".toByteArray(),
        )) {
            val entries = unpack(source)
            val old = entries.getValue(path)
            entries[path] = bad
            entries["data.json"] = entries.getValue("data.json").toString(Charsets.UTF_8)
                .replace(sha256(old), sha256(bad))
                .replace("\"byteLength\":${old.size}", "\"byteLength\":${bad.size}").toByteArray()
            inventory(entries)
            reject(pack(entries))
        }
        val entries = unpack(source)
        val old = entries.getValue("public-key.asc")
        val different = PublicKeyValidator.import(resource("v6-ed25519-x25519.asc")).bytes
        entries["public-key.asc"] = different
        entries["data.json"] = entries.getValue("data.json").toString(Charsets.UTF_8)
            .replace(sha256(old), sha256(different))
            .replace("\"byteLength\":${old.size}", "\"byteLength\":${different.size}").toByteArray()
        inventory(entries)
        reject(pack(entries))
    }

    @Test fun supportedExpiredAndVerifiedRevokedPublicCertificatesRemainExactHistoricalBytes() {
        val expired = PublicKeyValidator.import(resource("pgpainless-signature-CertificateExpirationTest-CERT.asc"))
        assertFalse(PublicKeyValidator.validateStored(expired.bytes, expired.asset, time).usable)
        val bob = body(resource("bc-BOB_CERT.asc"))
        val firstLength = ((bob[1].toInt() and 255) - 192) * 256 + (bob[2].toInt() and 255) + 192
        val primaryEnd = 3 + firstLength
        val revokedBytes = bob.copyOfRange(0, primaryEnd) + body(resource("bc-BOB_REVOCATION_CERT.asc")) +
            bob.copyOfRange(primaryEnd, bob.size)
        val revoked = PublicKeyValidator.import(armor(revokedBytes))
        assertFalse(PublicKeyValidator.validateStored(revoked.bytes, revoked.asset, time).usable)
        for (key in listOf(expired, revoked)) {
            val saved = data().copy(profile = data().profile.copy(publicKey = key.asset))
            val candidate = BackupArchive.validate(BackupArchive.create(BackupSnapshot(saved, emptyMap(), key.bytes, time)))
            assertEquals(saved, candidate.data)
            assertArrayEquals(key.bytes, candidate.publicKey)
            assertFalse(PublicKeyValidator.validateStored(candidate.publicKey!!, candidate.data.profile.publicKey!!, time).usable)
        }
    }

    private fun body(bytes: ByteArray): ByteArray = Base64.getDecoder().decode(
        bytes.toString(Charsets.US_ASCII).lines().filter {
            it.isNotBlank() && !it.startsWith("-") && !it.contains(':') && !it.startsWith("=")
        }.joinToString(""))

    private fun armor(bytes: ByteArray): ByteArray =
        ("-----BEGIN PGP PUBLIC KEY BLOCK-----\n" +
            Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(bytes) +
            "\n-----END PGP PUBLIC KEY BLOCK-----\n").toByteArray(Charsets.US_ASCII)

    @Test fun zipRejectsFlagsCompressionDescriptorsUnsafeAttributesAndHeaderMismatches() {
        val source = BackupArchive.create(snapshot())
        val c = central(source).first()
        val mutations: List<(ByteArray) -> Unit> = listOf(
            { set16(it, 6, 1); set16(it, c + 8, 1) },
            { set16(it, 6, 8); set16(it, c + 8, 8) },
            { set16(it, 6, 0x10); set16(it, c + 8, 0x10) },
            { set16(it, 8, 8); set16(it, c + 10, 8) },
            { set16(it, 4, 45); set16(it, c + 6, 45) },
            { set16(it, 28, 1) },
            { set16(it, c + 30, 1) },
            { set16(it, c + 32, 1) },
            { set16(it, c + 34, 1) },
            { set16(it, c + 36, 2) },
            { set16(it, c + 4, 0xA14) },
            { set32(it, c + 38, 0x10) },
            { set32(it, c + 38, 0x08) },
            { set16(it, c + 4, 0x314); set32(it, c + 38, 0xa1ff0000L) },
            { set16(it, c + 4, 0x314); set32(it, c + 38, 0x21b60000L) },
            { set16(it, c + 4, 0x314); set32(it, c + 38, 0x81ed0000L) },
            { set16(it, c + 4, 0x314); set32(it, c + 38, 0x89a40000L) },
            { set16(it, c + 8, 0x800) },
            { set16(it, c + 12, 1) },
            { set16(it, c + 14, 34) },
            { set32(it, c + 16, 0) },
            { set32(it, c + 20, 1) },
            { set32(it, c + 24, 1) },
            { it[c + 46] = 'M'.code.toByte() },
            { it[30] = 0 },
            { it[43] = 0 },
            { it[45] = (it[45].toInt() xor 1).toByte() },
        )
        mutations.forEachIndexed { index, mutate ->
            try { reject(source.copyOf().apply(mutate)) } catch (e: AssertionError) {
                throw AssertionError("ZIP mutation $index accepted", e)
            }
        }
        reject(pack(unpack(source), compressed = true))
    }

    @Test fun zipRejectsPrefixTrailerTruncationMultiVolumeCountsZip64OverflowAndOverlappingOffsets() {
        val source = BackupArchive.create(snapshot())
        val e = source.size - 22
        for (bad in listOf(source + byteArrayOf(0), byteArrayOf(0) + source, source.copyOf(source.size - 1),
            source.copyOf(22), ByteArray(0), "BEGIN:VCARD".toByteArray())) reject(bad)
        val mutations: List<(ByteArray) -> Unit> = listOf(
            { set16(it, e + 4, 1) }, { set16(it, e + 6, 1) },
            { set16(it, e + 8, 1) }, { set16(it, e + 10, 1) },
            { set16(it, e + 8, 6); set16(it, e + 10, 6) },
            { set16(it, e + 20, 1) },
            { set32(it, e + 12, 0xffffffffL) }, { set32(it, e + 16, 0xffffffffL) },
            { set32(it, central(it).first() + 42, 1) },
            { set32(it, central(it).last() + 42, 0) },
            { set32(it, central(it).last() + 42, 0xffffffffL) },
            { set32(it, central(it).first() + 20, 0xffffffffL) },
            { set32(it, central(it).first() + 24, 0xffffffffL) },
        )
        mutations.forEach { mutate -> reject(source.copyOf().apply(mutate)) }
        val duplicate = BackupArchive.create(snapshot(true))
        val coverCentral = central(duplicate).first { offset ->
            String(duplicate, offset + 46, u16(duplicate, offset + 28), Charsets.US_ASCII) == "cover.png"
        }
        val coverLocal = u32(duplicate, coverCentral + 42).toInt()
        "data.json".toByteArray().copyInto(duplicate, coverCentral + 46)
        "data.json".toByteArray().copyInto(duplicate, coverLocal + 30)
        reject(duplicate)
    }

    @Test fun exactByteLimitsAreEnforcedBeforeJsonAndEntryAllocation() {
        assertEquals(25_165_824, BackupArchive.MAX_ARCHIVE_BYTES)
        assertEquals(20_971_520, BackupArchive.MAX_ENTRY_BYTES)
        reject(ByteArray(BackupArchive.MAX_ARCHIVE_BYTES + 1))
        val source = BackupArchive.create(snapshot())
        for ((name, limit) in listOf("manifest.json" to 65_536, "data.json" to 524_288)) {
            val entries = unpack(source)
            entries[name] = entries.getValue(name).copyOf(limit).apply {
                fill(32, unpack(source).getValue(name).size)
            }
            if (name == "data.json") inventory(entries)
            assertEquals(data(), BackupArchive.validate(pack(entries)).data)
            entries[name] = entries.getValue(name) + byteArrayOf(32)
            if (name == "data.json") inventory(entries)
            reject(pack(entries))
        }
        val assets = BackupArchive.create(snapshot(true))
        for ((name, limit) in listOf("profile.png" to 8_388_608, "cover.png" to 8_388_608,
            "public-key.asc" to 65_536)) {
            val bad = assets.copyOf()
            val c = central(bad).first { offset ->
                String(bad, offset + 46, u16(bad, offset + 28), Charsets.US_ASCII) == name
            }
            val local = u32(bad, c + 42).toInt()
            for (p in listOf(c + 20, c + 24, local + 18, local + 22)) set32(bad, p, limit.toLong() + 1)
            reject(bad)
        }
    }

    private fun central(bytes: ByteArray): List<Int> {
        var p = u32(bytes, bytes.size - 6).toInt()
        return List(u16(bytes, bytes.size - 12)) {
            val begin = p
            p += 46 + u16(bytes, p + 28) + u16(bytes, p + 30) + u16(bytes, p + 32)
            begin
        }
    }

    private fun u16(bytes: ByteArray, p: Int): Int =
        (bytes[p].toInt() and 255) or ((bytes[p + 1].toInt() and 255) shl 8)
    private fun u32(bytes: ByteArray, p: Int): Long =
        (0..3).fold(0L) { n, i -> n or ((bytes[p + i].toLong() and 255) shl (i * 8)) }
    private fun set16(bytes: ByteArray, p: Int, n: Int) {
        repeat(2) { bytes[p + it] = (n ushr (8 * it)).toByte() }
    }
    private fun set32(bytes: ByteArray, p: Int, n: Long) {
        repeat(4) { bytes[p + it] = (n ushr (8 * it)).toByte() }
    }
}
