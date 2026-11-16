package com.mackseoul.vcardnative.data.profile

import com.mackseoul.vcardnative.domain.profile.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class StrictJsonTest {
    private fun reject(bytes: ByteArray) {
        try { StrictJson.decode(bytes); fail("Invalid JSON accepted") } catch (_: Exception) { }
    }
    @Test fun strictBoundedUtf8AndFraming() {
        for (text in listOf(
            "{\"x\":1,\"x\":2}", "{\"x\":1,\"\\u0078\":2}", "{\"x\":01}", "{\"x\":1.0}",
            "{\"x\":1e2}", "{\"x\":9223372036854775808}", "{\"x\":NaN}", "{\"x\":true,}",
            "{\"x\":1} trailing", "\uFEFF{}", "{\"x\":\"\\uD800\"}", "{\"x\":\"\\uDC00\"}",
            "{\"x\":\"\\uD800A\"}", "{\"x\":\"\u0000\"}", "{\"x\":\"\\z\"}", "{\"x\":[]}",
            "{\"x\":undefined}", "{\"x\":\"unterminated}", "{/* comment */}", ""
        )) reject(text.toByteArray(Charsets.UTF_8))
        reject(byteArrayOf(0xC0.toByte(), 0xAF.toByte()))
        reject(byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()))
        reject(ByteArray(StrictJson.MAX_BYTES + 1) { 32 })
        reject(("{\"a\":".repeat(26) + "null" + "}".repeat(26)).toByteArray())
        val value = StrictJson.decode("{\"x\":\"\\uD83D\\uDE00\",\"n\":null,\"b\":false}".toByteArray())
        assertEquals(mapOf("x" to "😀", "n" to null, "b" to false), value)
    }

    private fun manifest(): Manifest {
        val normalized = ProfileValidator.validate(ProfileDraft.empty().copy(
            values = mapOf(ProfileField.FullName to "Ada")
        )).normalized!!
        return Manifest(UUID.randomUUID().toString(), 1, null,
            SavedProfile(AppData(profile = Profile(UUID.randomUUID(), normalized.values, normalized.visibility),
                metadata = Metadata("2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")),
                RevisionToken(UUID.randomUUID().toString(), 1)))
    }

    private fun rejectManifest(bytes: ByteArray) {
        try { ProfileCodec.decode(bytes); fail("Invalid manifest accepted") } catch (_: Exception) { }
    }

    @Test fun completeManifestRoundTripAndChecksum() {
        ProfileValidator.hostValidator = AsciiHostValidator
        val manifest = manifest()
        assertEquals(manifest, ProfileCodec.decode(ProfileCodec.encode(manifest)))
        val original = ProfileCodec.encode(manifest).toString(Charsets.UTF_8)
        rejectManifest(original.replace("Ada", "Grace").toByteArray())
    }

    @Test fun unknownMissingSchemaEnumsVisibilityAssetsAndNoncanonicalTextFailClosed() {
        ProfileValidator.hostValidator = AsciiHostValidator
        val original = ProfileCodec.encode(manifest()).toString(Charsets.UTF_8)
        val variants = listOf(
            original.replace("\"schemaVersion\":5", "\"schemaVersion\":1"),
            original.replace("\"schemaVersion\":5", "\"schemaVersion\":4"),
            original.replace("\"schemaVersion\":5", "\"schemaVersion\":6"),
            original.replace("\"schemaVersion\":5", "\"schemaVersion\":5,\"unknown\":null"),
            original.replace("\"theme\":\"dark\"", "\"theme\":\"system\""),
            original.replace("\"accent\":\"sapphire\"", "\"accent\":\"unknown\""),
            original.replace("\"cardStyle\":\"classic\"", "\"cardStyle\":\"compact\""),
            original.replace(",\"cardStyle\":\"classic\"", ""),
            original.replace(",\"minimalItems\":[]", ""),
            original.replace("\"minimalItems\":[]", "\"minimalItems\":[\"fullName\"]"),
            original.replace("\"minimalItems\":[]", "\"minimalItems\":[\"email\",\"email\"]"),
            original.replace("\"minimalItems\":[]",
                "\"minimalItems\":[\"phone\",\"email\",\"location\",\"website\",\"professionalLinks\"]"),
            original.replace("\"minimalItems\":[]", "\"minimalItems\":[],\"extra\":true"),
            original.replace("\"fullName\":\"Ada\"", "\"fullName\":\" Ada \""),
            original.replace("\"publicKey\":null", "\"publicKey\":{}"),
            original.replace("\"profileImage\":null", "\"profileImage\":{}"),
            original.replace("\"coverImage\":null", "\"coverImage\":{}"),
            original.replace("\"fullName\":{\"showOnCard\":true", "\"fullName\":{\"showOnCard\":false"),
            original.replace("\"hostedProfileUrl\":{\"showOnCard\":null", "\"hostedProfileUrl\":{\"showOnCard\":true"),
            original.replace("\"fullName\":\"Ada\",", ""),
            original.replace("\"profileId\":", "\"unknownId\":"),
            original.replace("\"createdAt\":\"2026-01-01T00:00:00Z\"", "\"createdAt\":\"not-a-time\"")
        )
        for (variant in variants) {
            assertNotEquals("Manifest mutation must change the original JSON", original, variant)
            // Recompute the envelope checksum, so rejection exercises the schema rather than merely
            // detecting the intentionally changed bytes.
            val envelope = StrictJson.decode(variant.toByteArray(), allowArrays = true)
                .jsonObject("payload", "sha256")
            val payload = envelope["payload"]
            rejectManifest(StrictJson.encode(linkedMapOf("payload" to payload, "sha256" to sha256(StrictJson.encode(payload)))))
        }
    }

    @Test fun typedAssetsCannotBeSilentlyDroppedByCodecOrDraftConversion() {
        val original = manifest()
        val image = ImageAsset(UUID.randomUUID(), "image/png", 1, 1, 1, "0".repeat(64))
        val key = PublicKeyAsset(UUID.randomUUID(), "openpgp-public-armored", 6, 27,
            "application/pgp-keys", 1, "0".repeat(64), "A".repeat(64))
        for (profile in listOf(
            original.saved.data.profile.copy(profileImage = image),
            original.saved.data.profile.copy(coverImage = image),
            original.saved.data.profile.copy(publicKey = key)
        )) {
            val data = original.saved.data.copy(profile = profile)
            if (profile.publicKey != null) {
                assertEquals(key, data.toDraft().publicKey)
                assertNull(data.toDraft().pendingPublicKey)
            } else {
                assertEquals(profile.profileImage, data.toDraft().profileImage)
                assertEquals(profile.coverImage, data.toDraft().coverImage)
            }
            try { ProfileCodec.encode(original.copy(saved = original.saved.copy(data = data))); fail() }
            catch (_: IllegalArgumentException) { }
        }
        val data = original.saved.data.copy(profile=original.saved.data.profile.copy(profileImage=image))
        val withImage = original.copy(saved=original.saved.copy(data=data),
            blobs=mapOf(ImageRole.Portrait to UUID.randomUUID().toString()))
        assertEquals(withImage,ProfileCodec.decode(ProfileCodec.encode(withImage)))
        val withKey = original.copy(
            saved = original.saved.copy(data = original.saved.data.copy(profile = original.saved.data.profile.copy(publicKey = key))),
            keyBlob = UUID.randomUUID().toString(),
        )
        assertEquals(withKey, ProfileCodec.decode(ProfileCodec.encode(withKey)))
    }
}
