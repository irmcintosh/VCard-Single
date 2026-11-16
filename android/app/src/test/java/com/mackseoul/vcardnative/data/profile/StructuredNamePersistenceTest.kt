package com.mackseoul.vcardnative.data.profile

import com.mackseoul.vcardnative.data.backup.BackupArchive
import com.mackseoul.vcardnative.domain.backup.BackupSnapshot
import com.mackseoul.vcardnative.domain.profile.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class StructuredNamePersistenceTest {
    private val roots = mutableListOf<File>()
    @After fun cleanup() { roots.forEach { it.deleteRecursively() } }
    private fun files() = JavaTestStoreFiles(File("build\\structured-name-tests\\${UUID.randomUUID()}").also { roots += it })
    private fun draft() = ProfileDraft.empty().copy(
        values = mapOf(ProfileField.FullName to "VCARD TEST Standard", ProfileField.FirstName to "VCARD",
            ProfileField.MiddleName to "TEST", ProfileField.LastName to "Standard"),
        visibility = ProfileDraft.empty().visibility +
            (ProfileField.FirstName to Visibility(null, true)) +
            (ProfileField.LastName to Visibility(null, true)),
    )
    private fun data() = AppData(
        profile = Profile(UUID.randomUUID(), draft().values, draft().visibility),
        metadata = Metadata("2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"),
    )

    @Test fun namesPrivacyIdentityAndEditsSurviveRepositoryRestartAndArchiveRestore() {
        val fs = files()
        val source = ProfileRepository.managed(fs)
        val saved = source.save(draft(), null)
        assertEquals(saved, (ProfileRepository.managed(fs).load() as ProfileRead.Ready).saved)
        val edited = source.save(saved.data.toDraft().copy(
            values = saved.data.profile.values + (ProfileField.MiddleName to "Edited Middle"),
        ), saved.token)
        assertEquals("VCARD TEST Standard", edited.data.value(ProfileField.FullName))
        val backup = source.backupSnapshot()
        val candidate = BackupArchive.validate(BackupArchive.create(backup))
        assertEquals(edited.data, candidate.data)
        assertEquals(Visibility(null, false), candidate.data.visibility(ProfileField.MiddleName))
        val targetFiles = files()
        val target = ProfileRepository.managed(targetFiles)
        val restored = target.restore(BackupSnapshot(candidate.data, candidate.images, candidate.publicKey, candidate.createdAt), null)
        assertEquals(edited.data, restored.data)
        assertEquals(restored, (ProfileRepository.managed(targetFiles).load() as ProfileRead.Ready).saved)
        assertEquals(edited.data.profile.profileId, restored.data.profile.profileId)
    }

    @Test fun schemaOneAndTwoDefaultNamesWithoutGuessingThenSaveAsThree() {
        for (version in 1..2) {
            val old = data().copy(schemaVersion = version,
                profile = data().profile.copy(values = mapOf(ProfileField.FullName to "Unsplit Family Given")))
            val encoded = ProfileCodec.encodeData(old)
            val decoded = ProfileCodec.decodeData(StrictJson.decode(StrictJson.encode(encoded)))
            assertEquals(version, decoded.schemaVersion)
            structuredNameFields.forEach { field ->
                assertNull(decoded.value(field))
                assertEquals(Visibility(null, false), decoded.visibility(field))
            }

            val current = ProfileCodec.currentSchemaData(decoded)
            assertEquals(AppData.CURRENT_SCHEMA_VERSION, current.schemaVersion)
            val repo = ProfileRepository.managed(files())
            val saved = repo.save(decoded.toDraft(), null)
            assertEquals(AppData.CURRENT_SCHEMA_VERSION, saved.data.schemaVersion)
            assertEquals(decoded.profile.values, saved.data.profile.values)
        }
    }

    @Test fun generatedFullNamePersistsWithStructuredComponents() {
        val fs = files()
        val repository = ProfileRepository.managed(fs)
        val generated = "Zoë Maria O'Neil"
        val values = draft().values + (ProfileField.FullName to generated)
        val saved = repository.save(draft().copy(values = values), null)

        assertEquals(generated, saved.data.value(ProfileField.FullName))
        assertEquals(saved, (ProfileRepository.managed(fs).load() as ProfileRead.Ready).saved)
        assertEquals(generated, BackupArchive.validate(BackupArchive.create(repository.backupSnapshot()))
            .data.value(ProfileField.FullName))
    }

    @Test fun schemaThreeSocialMigrationIsOrderedExactAndIdempotent() {
        val profileId = UUID.fromString("00000000-0000-4000-8000-000000000123")
        val values = mapOf(
            ProfileField.FullName to "Legacy Social Owner",
            ProfileField.Website to "HTTP://EXAMPLE.COM/Website",
            ProfileField.LinkedIn to "HTTPS://EXAMPLE.COM/LinkedIn",
            ProfileField.GitHub to "https://example.com/GitHub",
            ProfileField.OtherProfessional to "HTTPS://EXAMPLE.COM/Other",
            ProfileField.HostedProfileUrl to "https://example.com/Hosted",
        )
        val visibility = ProfileDraft.empty().visibility + mapOf(
            ProfileField.LinkedIn to Visibility(false, true),
            ProfileField.GitHub to Visibility(true, false),
            ProfileField.OtherProfessional to Visibility(false, false),
        )
        val schemaThree = AppData(
            schemaVersion = 3,
            profile = Profile(profileId, values, visibility),
            metadata = Metadata("2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"),
        )

        val migrated = ProfileCodec.currentSchemaData(schemaThree)
        val repeated = ProfileCodec.currentSchemaData(schemaThree)
        val encoded = ProfileCodec.encodeData(migrated)
        val decoded = ProfileCodec.decodeData(StrictJson.decode(StrictJson.encode(encoded), allowArrays = true))

        assertEquals(AppData.CURRENT_SCHEMA_VERSION, migrated.schemaVersion)
        assertEquals(listOf("linkedin", "github", "otherProfessional"),
            migrated.profile.socialProfiles.map { it.platform })
        assertEquals(listOf("LinkedIn", "GitHub", "Other professional link"),
            migrated.profile.socialProfiles.map { it.label })
        assertEquals(migrated.profile.socialProfiles, repeated.profile.socialProfiles)
        assertEquals(migrated.profile.socialProfiles, decoded.profile.socialProfiles)
        assertEquals(values[ProfileField.LinkedIn], migrated.profile.socialProfiles[0].url)
        assertEquals(values[ProfileField.Website], migrated.value(ProfileField.Website))
        assertEquals(values[ProfileField.HostedProfileUrl], migrated.value(ProfileField.HostedProfileUrl))
        assertEquals(Visibility(false, true), migrated.visibility(ProfileField.LinkedIn))
        assertEquals(Visibility(true, false), migrated.visibility(ProfileField.GitHub))
        assertEquals(Visibility(false, false), migrated.visibility(ProfileField.OtherProfessional))
    }

    @Test fun genuineLegacyA2GenerationsLoadWithoutRewritingThenUpgradeOnExplicitEdit() {
        for (version in 1..2) {
            val fs = files()
            val repository = ProfileRepository.managed(fs)
            val saved = repository.save(ProfileDraft.empty().copy(values = mapOf(
                ProfileField.FullName to "Legacy Full Identity", ProfileField.Phone to "+1 (202) 555-0123",
            )), null)
            val path = "store-v1\\gen\\${fs.list("store-v1\\gen").single()}"
            val manifest = ProfileCodec.decode(fs.bytes(path))
            val legacy = manifest.copy(saved = saved.copy(data = saved.data.copy(schemaVersion = version)))
            val bytes = ProfileCodec.encode(legacy)
            fs.put(path, bytes)
            val envelope = StrictJson.decode(fs.bytes("store-v1\\ptr\\slot-0")).jsonObject("payload", "sha256")
            val payload = envelope["payload"].jsonObject(
                "magic", "format", "sequence", "manifestId", "manifestLength", "manifestSha256",
                "generationId", "revision", "kind",
            ).toMutableMap()
            payload["manifestLength"] = bytes.size
            payload["manifestSha256"] = sha256(bytes)
            fs.put("store-v1\\ptr\\slot-0", StrictJson.encode(
                linkedMapOf("payload" to payload, "sha256" to sha256(StrictJson.encode(payload))),
            ))
            val before = fs.snapshot()
            val restarted = ProfileRepository.managed(fs)
            val loaded = (restarted.load() as ProfileRead.Ready).saved
            assertEquals(version, loaded.data.schemaVersion)
            assertEquals(saved.data.profile.profileId, loaded.data.profile.profileId)
            assertEquals(saved.data.profile.values, loaded.data.profile.values)
            assertEquals(saved.data.profile.phoneNumberE164, loaded.data.profile.phoneNumberE164)
            assertEquals(before.keys, fs.snapshot().keys)
            before.forEach { (name, content) -> assertArrayEquals(content, fs.bytes(name)) }
            structuredNameFields.forEach {
                assertNull(loaded.data.value(it))
                assertEquals(Visibility(null, false), loaded.data.visibility(it))
            }
            val upgraded = restarted.save(loaded.data.toDraft().copy(
                values = loaded.data.profile.values + (ProfileField.FirstName to "Explicit Given"),
            ), loaded.token)
            assertEquals(AppData.CURRENT_SCHEMA_VERSION, upgraded.data.schemaVersion)
            assertEquals("Legacy Full Identity", upgraded.data.value(ProfileField.FullName))
            assertEquals("Explicit Given", upgraded.data.value(ProfileField.FirstName))
            assertEquals(loaded.data.profile.profileId, upgraded.data.profile.profileId)
        }
    }

    @Test fun schemaFourValidPayloadDecodesAndMutationsReachTheirIntendedValidation() {
        val original = StrictJson.encode(ProfileCodec.encodeData(data().copy(schemaVersion = 4))).toString(Charsets.UTF_8)
        val valid = ProfileCodec.decodeData(
            StrictJson.decode(original.toByteArray(Charsets.UTF_8), allowArrays = true),
        )
        assertEquals(4, valid.schemaVersion)
        assertEquals(draft().values, valid.profile.values)

        fun assertRejected(mutated: String, expectedMessage: String) {
            assertNotEquals(original, mutated)
            val error = assertThrows(IllegalArgumentException::class.java) {
                ProfileCodec.decodeData(
                    StrictJson.decode(mutated.toByteArray(Charsets.UTF_8), allowArrays = true),
                )
            }
            assertEquals(expectedMessage, error.message)
        }

        assertRejected(original.replace("\"firstName\":\"VCARD\",", ""), "Invalid JSON object fields.")
        assertRejected(original.replace("\"middleName\":\"TEST\"", "\"middleName\":42"), "Failed requirement.")
        assertRejected(
            original.replace("\"lastName\":{\"showOnCard\":null", "\"lastName\":{\"showOnCard\":true"),
            "Saved fields are invalid or noncanonical.",
        )
        assertRejected(original.replace("\"schemaVersion\":4", "\"schemaVersion\":6"), "Failed requirement.")
    }
}
