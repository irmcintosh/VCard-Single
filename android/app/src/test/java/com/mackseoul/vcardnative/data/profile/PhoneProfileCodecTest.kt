package com.mackseoul.vcardnative.data.profile

import com.mackseoul.vcardnative.domain.profile.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class PhoneProfileCodecTest {
    @Test fun versionOnePhoneLoadsWithoutChangingSavedTextAndUpgradesAsVersionTwo() {
        val original = requireNotNull(ProfileValidator.validate(ProfileDraft.empty().copy(
            values = mapOf(
                ProfileField.FullName to "Ada",
                ProfileField.Phone to "+44 20 7031 3000",
            ),
        )).normalized)
        val current = AppData(
            profile = Profile(
                UUID.randomUUID(), original.values, original.visibility,
                phoneRegion = original.phoneRegion,
                includeCountryCodeWhenSharing = original.includeCountryCodeWhenSharing,
                phoneNumberE164 = original.phoneNumberE164,
            ),
            metadata = Metadata("2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"),
        )
        val currentObject = ProfileCodec.encodeData(current.copy(schemaVersion = 2))
        val legacyRoot = (StrictJson.decode(StrictJson.encode(currentObject)) as Map<*, *>)
            .entries.associateTo(linkedMapOf()) { it.key as String to it.value }
        val currentProfile = legacyRoot.getValue("profile") as Map<*, *>
        val legacyProfile = currentProfile.entries
            .filter { it.key !in setOf("phoneRegion", "includeCountryCodeWhenSharing", "phoneNumberE164") }
            .associateTo(linkedMapOf()) { it.key as String to it.value }
        legacyRoot["schemaVersion"] = 1L
        legacyRoot["profile"] = legacyProfile

        val restored = ProfileCodec.decodeData(legacyRoot)
        assertEquals(1, restored.schemaVersion)
        assertEquals("+44 20 7031 3000", restored.value(ProfileField.Phone))
        assertEquals("+442070313000", restored.profile.phoneNumberE164)
        assertEquals("GB", restored.profile.phoneRegion)
        assertTrue(restored.profile.includeCountryCodeWhenSharing)

        val upgraded = ProfileCodec.currentSchemaData(restored)
        assertEquals(AppData.CURRENT_SCHEMA_VERSION, upgraded.schemaVersion)
        assertEquals(restored.profile.values, upgraded.profile.values)
        assertEquals(restored.profile.phoneNumberE164, upgraded.profile.phoneNumberE164)
        assertEquals(upgraded, ProfileCodec.decodeData(StrictJson.decode(StrictJson.encode(
            ProfileCodec.encodeData(upgraded),
        ), allowArrays = true)))
    }

    @Test fun malformedLegacyPhoneIsRetainedWithoutAnInventedInternationalNumber() {
        val initial = ProfileDraft.empty().copy(
            values = mapOf(ProfileField.FullName to "Ada", ProfileField.Phone to "123"),
            allowUnparsedLegacyPhone = true,
        )
        val values = requireNotNull(ProfileValidator.validate(initial).normalized)
        val data = AppData(
            schemaVersion = 1,
            profile = Profile(UUID.randomUUID(), values.values, values.visibility),
            metadata = Metadata(Instant.parse("2026-01-01T00:00:00Z").toString(),
                Instant.parse("2026-01-01T00:00:00Z").toString()),
        )
        val decoded = ProfileCodec.decodeData(StrictJson.decode(StrictJson.encode(ProfileCodec.encodeData(data))))
        assertEquals("123", decoded.value(ProfileField.Phone))
        assertEquals(null, decoded.profile.phoneNumberE164)
    }
}
