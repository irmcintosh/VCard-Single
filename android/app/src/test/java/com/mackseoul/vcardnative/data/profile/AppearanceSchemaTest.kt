package com.mackseoul.vcardnative.data.profile

import com.mackseoul.vcardnative.data.backup.BackupArchive
import com.mackseoul.vcardnative.domain.backup.BackupSnapshot
import com.mackseoul.vcardnative.domain.profile.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

class AppearanceSchemaTest {
    private val roots = mutableListOf<File>()

    @Before fun offlineHosts() { ProfileValidator.hostValidator = AsciiHostValidator }
    @After fun cleanup() { roots.forEach { it.deleteRecursively() } }

    private fun files() = JavaTestStoreFiles(File("build\\appearance-schema-tests\\${UUID.randomUUID()}").also { roots += it })

    private fun decode(json: String): AppData =
        ProfileCodec.decodeData(StrictJson.decode(json.toByteArray(Charsets.UTF_8), allowArrays = true))

    private fun encode(data: AppData): String = StrictJson.encode(ProfileCodec.encodeData(data)).toString(Charsets.UTF_8)

    private fun withAppearance(base: Map<String, Any?>, appearance: Any?): Any? =
        LinkedHashMap(base).apply { put("appearance", appearance) }

    /** Private-store payload in the exact V1.1 schema 4 layout (appearance holds only theme and accent). */
    private val schemaFour = """{"schemaVersion":4,"profile":{"profileId":"00000000-0000-4000-8000-0000000000a4",""" +
        """"fields":{"fullName":"Legacy Person","firstName":null,"middleName":null,"lastName":null,""" +
        """"professionalTitle":"Engineer","organization":"Example Organization","biography":"Builds things.",""" +
        """"location":"Example City","phone":"+1 650-253-0000","email":"person@example.com",""" +
        """"website":"https://example.com/","hostedProfileUrl":"https://example.com/card"},""" +
        """"visibility":{"fullName":{"showOnCard":true,"includeInContactExport":true},""" +
        """"firstName":{"showOnCard":null,"includeInContactExport":false},""" +
        """"middleName":{"showOnCard":null,"includeInContactExport":false},""" +
        """"lastName":{"showOnCard":null,"includeInContactExport":false},""" +
        """"professionalTitle":{"showOnCard":true,"includeInContactExport":true},""" +
        """"organization":{"showOnCard":true,"includeInContactExport":true},""" +
        """"biography":{"showOnCard":true,"includeInContactExport":false},""" +
        """"location":{"showOnCard":false,"includeInContactExport":false},""" +
        """"phone":{"showOnCard":true,"includeInContactExport":true},""" +
        """"email":{"showOnCard":true,"includeInContactExport":true},""" +
        """"website":{"showOnCard":true,"includeInContactExport":false},""" +
        """"hostedProfileUrl":{"showOnCard":null,"includeInContactExport":null},""" +
        """"publicKey":{"showOnCard":false,"includeInContactExport":null},""" +
        """"profileImage":{"showOnCard":true,"includeInContactExport":null},""" +
        """"coverImage":{"showOnCard":true,"includeInContactExport":null}},""" +
        """"publicKey":null,"profileImage":null,"coverImage":null,"socialProfiles":[""" +
        """{"id":"00000000-0000-4000-8000-0000000000b1","platform":"linkedin","label":"LinkedIn",""" +
        """"url":"https://linkedin.com/in/example","showOnCard":true,"includeInContactExport":false,""" +
        """"legacyField":"linkedin"},""" +
        """{"id":"00000000-0000-4000-8000-0000000000b2","platform":"mastodon","label":"Mastodon",""" +
        """"url":"https://social.example.com/person","showOnCard":false,"includeInContactExport":true,""" +
        """"legacyField":null}],""" +
        """"phoneRegion":"US","includeCountryCodeWhenSharing":true,"phoneNumberE164":"+16502530000"},""" +
        """"appearance":{"theme":"light","accent":"jade"},""" +
        """"metadata":{"createdAt":"2026-06-01T10:00:00Z","updatedAt":"2026-06-02T11:30:00Z"}}"""

    @Test fun defaultsAreV11DarkSapphireWithClassicAndNoMinimalItems() {
        val appearance = Appearance()
        assertEquals(Theme.dark, appearance.theme)
        assertEquals(Accent.sapphire, appearance.accent)
        assertEquals(CardStyle.Classic, appearance.cardStyle)
        assertTrue(appearance.minimalItems.isEmpty())
        assertEquals(Appearance(Theme.dark, Accent.sapphire), appearance)
        assertEquals(5, AppData.CURRENT_SCHEMA_VERSION)
    }

    @Test fun persistedIdentifiersAreStableAndComplete() {
        assertEquals(listOf("classic", "minimal", "professional", "modern", "executive", "editorial", "horizon"),
            CardStyle.entries.map { it.key })
        assertEquals(listOf("profilePhoto", "coverImage", "professionalTitle", "organization", "biography", "phone",
            "email", "location", "website", "professionalLinks", "hostedProfile"),
            MinimalItem.entries.map { it.key })
    }

    @Test fun domainAcceptsZeroThroughFourMinimalItemsAndRejectsMore() {
        for (count in 0..4) {
            assertEquals(count, Appearance(minimalItems = MinimalItem.entries.take(count).toSet()).minimalItems.size)
        }
        for (count in 5..MinimalItem.entries.size) {
            assertThrows(IllegalArgumentException::class.java) {
                Appearance(minimalItems = MinimalItem.entries.take(count).toSet())
            }
        }
        val full = Appearance(minimalItems = MinimalItem.entries.take(4).toSet())
        assertThrows(IllegalArgumentException::class.java) {
            full.copy(minimalItems = full.minimalItems + MinimalItem.HostedProfile)
        }
    }

    @Test fun minimalItemsCannotBeMutatedPastTheLimitAfterConstruction() {
        val source = linkedSetOf(MinimalItem.Email, MinimalItem.Phone, MinimalItem.Website, MinimalItem.Biography)
        val appearance = Appearance(minimalItems = source)
        source += MinimalItem.Location
        source += MinimalItem.HostedProfile
        assertEquals(setOf(MinimalItem.Email, MinimalItem.Phone, MinimalItem.Website, MinimalItem.Biography),
            appearance.minimalItems)
        @Suppress("UNCHECKED_CAST")
        val leaked = appearance.minimalItems as MutableSet<MinimalItem>
        assertThrows(UnsupportedOperationException::class.java) { leaked += MinimalItem.Location }
        assertEquals(4, appearance.minimalItems.size)
        assertEquals(listOf(MinimalItem.Biography, MinimalItem.Phone, MinimalItem.Email, MinimalItem.Website),
            appearance.minimalItems.toList())
        assertEquals(appearance, appearance.copy())
        assertEquals(appearance.hashCode(), Appearance(minimalItems = source.take(4).toSet()).hashCode())
    }

    @Test fun switchingStylesKeepsMinimalSelectionsAndProfileData() {
        val items = setOf(MinimalItem.Phone, MinimalItem.ProfessionalLinks, MinimalItem.HostedProfile)
        val legacy = decode(schemaFour)
        val minimal = ProfileCodec.currentSchemaData(legacy)
            .let { it.copy(appearance = it.appearance.copy(cardStyle = CardStyle.Minimal, minimalItems = items)) }
        for (style in CardStyle.entries) {
            val switched = minimal.copy(appearance = minimal.appearance.copy(cardStyle = style))
            val decoded = decode(encode(switched))
            assertEquals(style, decoded.appearance.cardStyle)
            assertEquals(items, decoded.appearance.minimalItems)
            assertEquals(legacy.profile, decoded.profile)
        }
    }

    @Test fun v11SchemaFourPayloadDecodesUnchangedAndMigratesExplicitlyToSchemaFive() {
        val legacy = decode(schemaFour)
        assertEquals(4, legacy.schemaVersion)
        assertEquals(Appearance(Theme.light, Accent.jade, CardStyle.Classic, emptySet()), legacy.appearance)
        assertEquals(UUID.fromString("00000000-0000-4000-8000-0000000000a4"), legacy.profile.profileId)
        assertEquals("Legacy Person", legacy.value(ProfileField.FullName))
        assertEquals("Engineer", legacy.value(ProfileField.ProfessionalTitle))
        assertEquals("Example Organization", legacy.value(ProfileField.Organization))
        assertEquals("Builds things.", legacy.value(ProfileField.Biography))
        assertEquals("Example City", legacy.value(ProfileField.Location))
        assertEquals("+1 650-253-0000", legacy.value(ProfileField.Phone))
        assertEquals("person@example.com", legacy.value(ProfileField.Email))
        assertEquals("https://example.com/", legacy.value(ProfileField.Website))
        assertEquals("https://example.com/card", legacy.value(ProfileField.HostedProfileUrl))
        assertEquals("https://linkedin.com/in/example", legacy.value(ProfileField.LinkedIn))
        assertEquals(Visibility(false, false), legacy.visibility(ProfileField.Location))
        assertEquals(listOf("linkedin", "mastodon"), legacy.profile.socialProfiles.map { it.platform })
        assertEquals("US", legacy.profile.phoneRegion)
        assertEquals("+16502530000", legacy.profile.phoneNumberE164)
        assertEquals(Metadata("2026-06-01T10:00:00Z", "2026-06-02T11:30:00Z"), legacy.metadata)

        // The V1.1 schema 4 write format is unchanged byte for byte.
        assertEquals(schemaFour, encode(legacy))

        val migrated = ProfileCodec.currentSchemaData(legacy)
        assertEquals(5, migrated.schemaVersion)
        assertEquals(legacy.profile, migrated.profile)
        assertEquals(legacy.appearance, migrated.appearance)
        assertEquals(legacy.metadata, migrated.metadata)
        assertEquals(migrated, ProfileCodec.currentSchemaData(migrated))

        val upgraded = encode(migrated)
        assertEquals(
            schemaFour.replace("{\"schemaVersion\":4,", "{\"schemaVersion\":5,")
                .replace("\"appearance\":{\"theme\":\"light\",\"accent\":\"jade\"}",
                    "\"appearance\":{\"theme\":\"light\",\"accent\":\"jade\",\"cardStyle\":\"classic\",\"minimalItems\":[]}"),
            upgraded,
        )
        assertEquals(migrated, decode(upgraded))
    }

    @Test fun schemaFiveRoundTripsEveryStyleAndZeroThroughFourItemsInStoreAndManifest() {
        val base = ProfileCodec.currentSchemaData(decode(schemaFour))
        for (style in CardStyle.entries) for (count in 0..4) {
            val items = MinimalItem.entries.reversed().take(count).toSet()
            val data = base.copy(appearance = Appearance(Theme.dark, Accent.amethyst, style, items))
            assertEquals(data, decode(encode(data)))
            val manifest = Manifest(UUID.randomUUID().toString(), 1, null,
                SavedProfile(data, RevisionToken(UUID.randomUUID().toString(), 1)))
            assertEquals(manifest, ProfileCodec.decode(ProfileCodec.encode(manifest)))
        }
    }

    @Test fun serializationIsDeterministicAndCanonicallyOrdered() {
        val base = ProfileCodec.currentSchemaData(decode(schemaFour))
        assertTrue(encode(base.copy(appearance = Appearance())).contains(
            "\"appearance\":{\"theme\":\"dark\",\"accent\":\"sapphire\",\"cardStyle\":\"classic\",\"minimalItems\":[]}"))
        val forward = base.copy(appearance = Appearance(Theme.light, Accent.sapphire, CardStyle.Editorial,
            linkedSetOf(MinimalItem.ProfilePhoto, MinimalItem.Email, MinimalItem.Website, MinimalItem.HostedProfile)))
        val backward = base.copy(appearance = Appearance(Theme.light, Accent.sapphire, CardStyle.Editorial,
            linkedSetOf(MinimalItem.HostedProfile, MinimalItem.Website, MinimalItem.Email, MinimalItem.ProfilePhoto)))
        assertEquals(encode(forward), encode(backward))
        assertTrue(encode(forward).contains(
            "\"cardStyle\":\"editorial\",\"minimalItems\":[\"profilePhoto\",\"email\",\"website\",\"hostedProfile\"]}"))
    }

    @Test fun schemaFourAndEarlierCannotSilentlyDropCardStyleData() {
        val legacy = decode(schemaFour)
        for (version in 1..4) {
            val old = legacy.copy(schemaVersion = version, profile = legacy.profile.copy(
                values = mapOf(ProfileField.FullName to "Legacy Person"),
                visibility = ProfileField.entries.associateWith { it.defaultVisibility() },
                phoneNumberE164 = null, socialProfiles = emptyList()))
            // The default appearance remains writable, so the failures below are caused by the style data alone.
            ProfileCodec.encodeData(old)
            assertThrows(IllegalArgumentException::class.java) {
                ProfileCodec.encodeData(old.copy(appearance = old.appearance.copy(cardStyle = CardStyle.Modern)))
            }
            assertThrows(IllegalArgumentException::class.java) {
                ProfileCodec.encodeData(old.copy(appearance = old.appearance.copy(minimalItems = setOf(MinimalItem.Email))))
            }
        }
    }

    @Test fun malformedPartialUnknownDuplicateOrExcessiveAppearanceIsRejected() {
        @Suppress("UNCHECKED_CAST")
        val five = StrictJson.decode(encode(ProfileCodec.currentSchemaData(decode(schemaFour))).toByteArray(),
            allowArrays = true) as Map<String, Any?>
        fun appearance(vararg entries: Pair<String, Any?>) = linkedMapOf(*entries)
        val valid = listOf("theme" to "light", "accent" to "jade", "cardStyle" to "minimal",
            "minimalItems" to listOf("phone", "email"))
        assertEquals(setOf(MinimalItem.Phone, MinimalItem.Email),
            ProfileCodec.decodeData(withAppearance(five, appearance(*valid.toTypedArray()))).appearance.minimalItems)

        val invalid = listOf(
            null,
            "classic",
            listOf<Any?>(),
            appearance("theme" to "light", "accent" to "jade"),
            appearance("theme" to "light", "accent" to "jade", "cardStyle" to "classic"),
            appearance("theme" to "light", "accent" to "jade", "minimalItems" to listOf<Any?>()),
            appearance(*valid.toTypedArray(), "extra" to null),
            appearance("theme" to "system", "accent" to "jade", "cardStyle" to "classic", "minimalItems" to listOf<Any?>()),
            appearance("theme" to "light", "accent" to "gold", "cardStyle" to "classic", "minimalItems" to listOf<Any?>()),
            appearance("theme" to "light", "accent" to "jade", "cardStyle" to "compact", "minimalItems" to listOf<Any?>()),
            appearance("theme" to "light", "accent" to "jade", "cardStyle" to "Classic", "minimalItems" to listOf<Any?>()),
            appearance("theme" to "light", "accent" to "jade", "cardStyle" to "", "minimalItems" to listOf<Any?>()),
            appearance("theme" to "light", "accent" to "jade", "cardStyle" to null, "minimalItems" to listOf<Any?>()),
            appearance("theme" to "light", "accent" to "jade", "cardStyle" to 1L, "minimalItems" to listOf<Any?>()),
            appearance("theme" to "light", "accent" to "jade", "cardStyle" to "classic", "minimalItems" to null),
            appearance("theme" to "light", "accent" to "jade", "cardStyle" to "classic", "minimalItems" to "phone"),
            appearance("theme" to "light", "accent" to "jade", "cardStyle" to "classic", "minimalItems" to mapOf<String, Any?>()),
            appearance("theme" to "light", "accent" to "jade", "cardStyle" to "classic", "minimalItems" to listOf(null)),
            appearance("theme" to "light", "accent" to "jade", "cardStyle" to "classic", "minimalItems" to listOf(1L)),
            appearance("theme" to "light", "accent" to "jade", "cardStyle" to "classic", "minimalItems" to listOf("fullName")),
            appearance("theme" to "light", "accent" to "jade", "cardStyle" to "classic", "minimalItems" to listOf("Phone")),
            appearance("theme" to "light", "accent" to "jade", "cardStyle" to "classic", "minimalItems" to listOf("phone", "phone")),
            appearance("theme" to "light", "accent" to "jade", "cardStyle" to "classic", "minimalItems" to listOf("email", "phone")),
            appearance("theme" to "light", "accent" to "jade", "cardStyle" to "classic",
                "minimalItems" to MinimalItem.entries.take(5).map { it.key }),
            appearance("theme" to "light", "accent" to "jade", "cardStyle" to "classic",
                "minimalItems" to MinimalItem.entries.map { it.key }),
        )
        invalid.forEachIndexed { index, value ->
            try {
                assertThrows(IllegalArgumentException::class.java) { ProfileCodec.decodeData(withAppearance(five, value)) }
            } catch (error: AssertionError) {
                throw AssertionError("Appearance variant $index accepted", error)
            }
        }

        // Schema 4 payloads accept only the V1.1 two-key appearance.
        @Suppress("UNCHECKED_CAST")
        val four = StrictJson.decode(schemaFour.toByteArray(), allowArrays = true) as Map<String, Any?>
        for (value in listOf(
            appearance(*valid.toTypedArray()),
            appearance("theme" to "light", "accent" to "jade", "cardStyle" to "classic", "minimalItems" to listOf<Any?>()),
            appearance("theme" to "light"),
        )) {
            assertThrows(IllegalArgumentException::class.java) { ProfileCodec.decodeData(withAppearance(four, value)) }
        }
    }

    @Test fun storedSchemaFourGenerationLoadsWithoutRewritingThenSavesAndBacksUpAsSchemaFive() {
        val fs = files()
        val repository = ProfileRepository.managed(fs)
        val saved = repository.save(ProfileDraft.empty().copy(
            values = mapOf(ProfileField.FullName to "Stored Legacy", ProfileField.Email to "stored@example.com",
                ProfileField.Phone to "+1 (202) 555-0123"),
            appearance = Appearance(Theme.light, Accent.amethyst),
        ), null)
        val path = "store-v1\\gen\\${fs.list("store-v1\\gen").single()}"
        val manifest = ProfileCodec.decode(fs.bytes(path))
        val bytes = ProfileCodec.encode(manifest.copy(saved = saved.copy(data = saved.data.copy(schemaVersion = 4))))
        assertTrue(bytes.toString(Charsets.UTF_8).contains("\"appearance\":{\"theme\":\"light\",\"accent\":\"amethyst\"}"))
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
        val loaded = (ProfileRepository.managed(fs).load() as ProfileRead.Ready).saved
        assertEquals(4, loaded.data.schemaVersion)
        assertEquals(Appearance(Theme.light, Accent.amethyst, CardStyle.Classic, emptySet()), loaded.data.appearance)
        assertEquals(saved.data.profile, loaded.data.profile)
        assertEquals(before.keys, fs.snapshot().keys)
        before.forEach { (name, content) -> assertArrayEquals(content, fs.bytes(name)) }

        val restarted = ProfileRepository.managed(fs)
        val chosen = Appearance(Theme.light, Accent.amethyst, CardStyle.Minimal,
            setOf(MinimalItem.Email, MinimalItem.Biography))
        val upgraded = restarted.save(loaded.data.toDraft().copy(appearance = chosen), loaded.token)
        assertEquals(5, upgraded.data.schemaVersion)
        assertEquals(chosen, upgraded.data.appearance)
        assertEquals(loaded.data.profile, upgraded.data.profile)
        assertEquals(upgraded, (ProfileRepository.managed(fs).load() as ProfileRead.Ready).saved)

        val switched = restarted.save(upgraded.data.toDraft().copy(
            appearance = chosen.copy(cardStyle = CardStyle.Horizon)), upgraded.token)
        assertEquals(chosen.minimalItems, switched.data.appearance.minimalItems)
        assertEquals(loaded.data.profile, switched.data.profile)

        val candidate = BackupArchive.validate(BackupArchive.create(restarted.backupSnapshot()))
        assertEquals(switched.data, candidate.data)
        val target = ProfileRepository.managed(files())
        val restored = target.restore(BackupSnapshot(candidate.data, candidate.images, candidate.publicKey, candidate.createdAt), null)
        assertEquals(5, restored.data.schemaVersion)
        assertEquals(switched.data.appearance, restored.data.appearance)
        assertEquals(switched.data.profile, restored.data.profile)
    }
}
