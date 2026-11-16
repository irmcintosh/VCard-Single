package com.mackseoul.vcardnative.data.keys

import android.test.InstrumentationTestCase
import com.mackseoul.vcardnative.domain.keys.PublicKeyException
import java.time.Instant

@Suppress("DEPRECATION")
class PublicKeyValidatorAndroidTest : InstrumentationTestCase() {
    private fun fixture(name: String): ByteArray =
        instrumentation.context.assets.open("openpgp/$name").use { it.readBytes() }

    fun testPublicFixtureImportAndStoredValidationOnAndroid() {
        val imported = PublicKeyValidator.import(fixture("valid-public.asc"))
        val status = PublicKeyValidator.validateStored(imported.bytes, imported.asset)
        assertTrue(status.usable)
        assertNull(status.explanation)
        assertEquals(imported.asset.fingerprint, imported.asset.fingerprint.uppercase())
    }

    fun testMaintainedRsaAndV6VectorsOnAndroid() {
        for ((name, version, algorithm) in listOf(
            Triple("bc-BOB_CERT.asc", 4, 1),
            Triple("v6-ed25519-x25519.asc", 6, 27),
        )) {
            val imported = PublicKeyValidator.import(fixture(name))
            assertEquals(version, imported.asset.keyVersion)
            assertEquals(algorithm, imported.asset.primaryAlgorithm)
            assertTrue(PublicKeyValidator.validateStored(imported.bytes, imported.asset).usable)
        }
    }

    fun testExpiredMaterialRetainedWithReferenceTimeStatusOnAndroid() {
        val imported = PublicKeyValidator.import(
            fixture("pgpainless-signature-CertificateExpirationTest-CERT.asc"),
        )
        assertFalse(PublicKeyValidator.validateStored(imported.bytes, imported.asset).usable)
        assertTrue(PublicKeyValidator.validateStored(
            imported.bytes, imported.asset, Instant.parse("2021-04-28T14:00:00Z"),
        ).usable)
    }

    fun testSecretFixtureIsRejectedOnAndroid() {
        try {
            PublicKeyValidator.import(fixture("secret.asc"))
            fail("Secret-key material was accepted")
        } catch (expected: PublicKeyException) {
            assertNotNull(expected.message)
            assertFalse(expected.message!!.contains("PRIVATE KEY BLOCK"))
        }
    }
}
