package com.mackseoul.vcardnative.data.keys

import com.mackseoul.vcardnative.domain.keys.DraftPublicKey
import com.mackseoul.vcardnative.domain.keys.PublicKeyException
import org.bouncycastle.bcpg.BCPGInputStream
import org.bouncycastle.bcpg.BCPGOutputStream
import org.bouncycastle.bcpg.PublicKeyPacket
import org.bouncycastle.bcpg.ContainedPacket
import org.bouncycastle.bcpg.MarkerPacket
import org.bouncycastle.bcpg.PacketFormat
import org.bouncycastle.bcpg.PaddingPacket
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.SignaturePacket
import org.bouncycastle.bcpg.SignatureSubpacket
import org.bouncycastle.bcpg.SignatureSubpacketTags
import org.bouncycastle.bcpg.sig.RevocationKey
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.bcpg.TrustPacket
import org.bouncycastle.bcpg.UserAttributePacket
import org.bouncycastle.bcpg.attr.ImageAttribute
import org.bouncycastle.openpgp.api.OpenPGPKeyReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant
import java.util.Base64
import java.util.UUID

class PublicKeyValidatorTest {
    // Existing fixtures from bc-java r1rv86: pg/src/test/resources/org/bouncycastle/openpgp/test/
    // eddsa-sks-pub-keyring.asc and encd-test-key.asc. Upstream license: Bouncy Castle MIT-style license.
    // Additional unchanged public vectors:
    // https://github.com/bcgit/bc-java/blob/r1rv86/pg/src/test/java/org/bouncycastle/openpgp/OpenPGPTestKeys.java
    // BOB_CERT / BOB_REVOCATION_CERT (draft-bre-openpgp-samples); BC's MIT-style license.
    // https://github.com/bcgit/bc-java/blob/r1rv86/pg/src/test/java/org/bouncycastle/openpgp/test/PGPv6KeyTest.java
    // ARMORED_CERT (RFC 9580 appendix A); BC's MIT-style license.
    // https://github.com/pgpainless/pgpainless/blob/2.0.4/pgpainless-core/src/test/java/org/pgpainless/signature/CertificateExpirationTest.java
    // Second CERT literal; Apache-2.0.
    @Test
    fun `BC absorption of bytes appended inside a subkey fails per-packet accounting`() {
        val original = packets(decodeBody(fixture("valid-public.asc")))
        val index = original.indexOfFirst { it.packetTag == 14 }
        val subkey = original[index] as PublicKeyPacket
        val secretArmor = fixture("secret.asc").toString(Charsets.US_ASCII)
            .replace("PRIVATE KEY", "PUBLIC KEY").toByteArray(Charsets.US_ASCII)
        val privatePackets = decodeBody(secretArmor)
        for (suffix in listOf(byteArrayOf(0), privatePackets)) {
            val body = subkey.encodedContents + suffix
            val malformed = ByteArrayOutputStream().also { out ->
                BCPGOutputStream(out, 14, body.size.toLong(), false).use { it.write(body) }
            }.toByteArray()
            val binary = encoded(original.take(index)) + malformed + encoded(original.drop(index + 1))
            assertThrows(PublicKeyException::class.java) {
                PublicKeyValidator.import(armor(binary))
            }
        }
    }

    @Test
    fun `BC hard revocation is historically effective without removing original material`() {
        val revocation = packets(decodeBody(fixture("bc-BOB_REVOCATION_CERT.asc")))
            .single() as SignaturePacket
        val signature = PGPSignature(BCPGInputStream(ByteArrayInputStream(
            revocation.getEncoded(PacketFormat.ROUNDTRIP),
        )))
        assertTrue(signature.isHardRevocation)
        val binary = insertAfterPrimary(
            decodeBody(fixture("bc-BOB_CERT.asc")),
            revocation.getEncoded(PacketFormat.ROUNDTRIP),
        )
        val imported = PublicKeyValidator.import(armor(binary))
        assertFalse(PublicKeyValidator.validateStored(
            imported.bytes, imported.asset, signature.creationTime.toInstant().minusSeconds(1),
        ).usable)
        assertArrayEquals(binary, decodeBody(imported.bytes))
    }

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/openpgp/$name")).use(InputStream::readBytes)

    private fun decodeBody(bytes: ByteArray): ByteArray {
        val lines = bytes.toString(Charsets.US_ASCII).lines()
        val begin = lines.indexOfFirst { it.startsWith("-----BEGIN PGP PUBLIC KEY BLOCK-----") }
        val end = lines.indexOfFirst { it.startsWith("-----END PGP PUBLIC KEY BLOCK-----") }
        val encoded = lines.subList(begin + 1, end)
            .filterNot { it.startsWith("Version:") || it.startsWith("Comment:") || it.startsWith("Charset:") ||
                it.startsWith("=") || it.isBlank() }
            .joinToString("")
        return Base64.getDecoder().decode(encoded)
    }

    private fun armor(binary: ByteArray): ByteArray {
        val body = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(binary)
        return "-----BEGIN PGP PUBLIC KEY BLOCK-----\n$body\n-----END PGP PUBLIC KEY BLOCK-----\n"
            .toByteArray(Charsets.US_ASCII)
    }

    @Test
    fun `imports and revalidates an upstream Ed25519Legacy Curve25519Legacy certificate`() {
        val source = fixture("valid-public.asc")
        val imported = PublicKeyValidator.import(source)

        assertEquals(4, imported.asset.keyVersion)
        assertEquals(22, imported.asset.primaryAlgorithm)
        assertEquals("B431B310495527DF9235B42E660C54E515C147EA", imported.asset.fingerprint)
        assertEquals("3766b9536f7fcd095db00e191399a03b25f553c31b54e65dda4b186351929fd6", imported.asset.sha256)
        assertEquals(630L, imported.asset.byteLength)
        assertEquals(40, imported.asset.fingerprint.length)
        assertTrue(imported.asset.fingerprint.matches(Regex("[0-9A-F]{40}")))
        assertEquals("application/pgp-keys", imported.asset.mediaType)
        assertEquals("openpgp-public-armored", imported.asset.format)
        assertEquals(imported.bytes.size.toLong(), imported.asset.byteLength)
        assertTrue(imported.bytes.contentEquals(imported.bytes))
        assertFalse(imported.bytes.toString(Charsets.US_ASCII).contains("\r"))
        assertTrue(imported.bytes.toString(Charsets.US_ASCII).endsWith("-----END PGP PUBLIC KEY BLOCK-----\n"))
        val certificate = OpenPGPKeyReader().parseCertificates(source).single()
        val requiredHashes = certificate.keys.flatMap { component ->
            component.getPGPPublicKey().signatures.asSequence().map { it.hashAlgorithm }.toList()
        }.toSet()
        assertEquals(setOf(HashAlgorithmTags.SHA256), requiredHashes)

        val status = PublicKeyValidator.validateStored(imported.bytes, imported.asset)
        assertTrue(status.usable)
        assertEquals(null, status.explanation)
        assertTrue(PublicKeyValidator.validDescriptor(imported.asset))
    }

    @Test
    fun `bounded marker and padding packets survive normalization and packet roundtrip`() {
        val binary = decodeBody(fixture("valid-public.asc"))
        val marker = MarkerPacket(
            BCPGInputStream(ByteArrayInputStream(byteArrayOf('P'.code.toByte(), 'G'.code.toByte(), 'P'.code.toByte()))),
        )
        val markerBytes = (marker as ContainedPacket).getEncoded(PacketFormat.CURRENT)
        val paddingBytes = PaddingPacket(byteArrayOf(7, 9, 11)).getEncoded(PacketFormat.CURRENT)
        val withIgnorablePackets = binary + markerBytes + paddingBytes

        val imported = PublicKeyValidator.import(armor(withIgnorablePackets))

        assertEquals("B431B310495527DF9235B42E660C54E515C147EA", imported.asset.fingerprint)
        assertTrue(decodeBody(imported.bytes).contentEquals(withIgnorablePackets))
    }

    @Test
    fun `packet count includes preserved marker packets and fails closed over the limit`() {
        val binary = decodeBody(fixture("valid-public.asc"))
        val marker = MarkerPacket(
            BCPGInputStream(ByteArrayInputStream(byteArrayOf('P'.code.toByte(), 'G'.code.toByte(), 'P'.code.toByte()))),
        )
        val markerBytes = (marker as ContainedPacket).getEncoded(PacketFormat.CURRENT)
        val excessive = binary + ByteArrayOutputStream().also { out ->
            repeat(129) { out.write(markerBytes) }
        }.toByteArray()

        assertThrows(PublicKeyException::class.java) {
            PublicKeyValidator.import(armor(excessive))
        }
    }

    private fun insertAfterPrimary(public: ByteArray, extra: ByteArray): ByteArray {
        val input = BCPGInputStream(ByteArrayInputStream(public))
        val first = (requireNotNull(input.readPacket()) as ContainedPacket).getEncoded(PacketFormat.ROUNDTRIP)
        return first + extra + public.copyOfRange(first.size, public.size)
    }

    @Test
    fun `trust and user-attribute packets are rejected`() {
        val binary = decodeBody(fixture("valid-public.asc"))
        val trust = TrustPacket(0).getEncoded(PacketFormat.CURRENT)
        val attributes = UserAttributePacket(arrayOf(ImageAttribute(ByteArray(16))))
            .getEncoded(PacketFormat.CURRENT)

        for (unsupported in listOf(trust, attributes)) {
            assertThrows(PublicKeyException::class.java) {
                PublicKeyValidator.import(armor(binary + unsupported))
            }
        }
    }

    @Test
    fun `unaccounted trailing binary data is rejected`() {
        val binary = decodeBody(fixture("valid-public.asc")) + byteArrayOf(0)
        assertThrows(PublicKeyException::class.java) {
            PublicKeyValidator.import(armor(binary))
        }
    }

    @Test
    fun `definite old-format packet headers are accepted when BC roundtrips every byte`() {
        val source = BCPGInputStream(ByteArrayInputStream(decodeBody(fixture("valid-public.asc"))))
        val oldFormat = ByteArrayOutputStream()
        while (source.available() > 0) {
            val packet = requireNotNull(source.readPacket()) as ContainedPacket
            oldFormat.write(packet.getEncoded(PacketFormat.LEGACY))
        }

        val imported = PublicKeyValidator.import(armor(oldFormat.toByteArray()))

        assertEquals("B431B310495527DF9235B42E660C54E515C147EA", imported.asset.fingerprint)
        assertTrue(imported.bytes.isNotEmpty())
    }

    @Test
    fun `draft bytes are defensive and equality compares content`() {
        val imported = PublicKeyValidator.import(fixture("valid-public.asc"))
        val source = imported.bytes
        val draft = DraftPublicKey(imported.asset, source)
        source[0] = 0
        assertArrayEquals(imported.bytes, draft.bytes)

        val exposed = draft.bytes
        exposed[0] = 0
        assertArrayEquals(imported.bytes, draft.bytes)
        assertEquals(draft, DraftPublicKey(imported.asset, imported.bytes))
        assertNotEquals(draft, DraftPublicKey(imported.asset, imported.bytes + byteArrayOf(1)))
    }

    @Test
    fun `ignores absent malformed or incorrect optional armor checksum`() {
        val original = fixture("valid-public.asc").toString(Charsets.US_ASCII)
        val withoutChecksum = original.replace(Regex("(?m)^=.*(?:\\r?\\n)?"), "")
        val malformedChecksum = original.replace(Regex("(?m)^=.*"), "=not-a-crc")
        val wrongChecksum = original.replace(Regex("(?m)^=.*"), "=AAAA")

        val canonical = PublicKeyValidator.import(original.toByteArray(Charsets.US_ASCII))
        for (candidate in listOf(withoutChecksum, malformedChecksum, wrongChecksum)) {
            val imported = PublicKeyValidator.import(candidate.toByteArray(Charsets.US_ASCII))
            assertEquals(canonical.asset.fingerprint, imported.asset.fingerprint)
            assertArrayEquals(canonical.bytes, imported.bytes)
        }
    }

    @Test
    fun `rejects secret material private containers and mixed objects`() {
        val secret = fixture("secret.asc")
        val error = assertThrows(PublicKeyException::class.java) {
            PublicKeyValidator.import(secret)
        }
        assertNotNull(error.message)
        assertFalse(error.message!!.contains("PRIVATE KEY BLOCK"))

        val public = fixture("valid-public.asc").toString(Charsets.US_ASCII)
        val privateBlock = secret.toString(Charsets.US_ASCII)
        assertThrows(PublicKeyException::class.java) {
            PublicKeyValidator.import((public + privateBlock).toByteArray(Charsets.US_ASCII))
        }
        assertThrows(PublicKeyException::class.java) {
            PublicKeyValidator.import(
                public.replace("PUBLIC KEY BLOCK", "PRIVATE KEY BLOCK").toByteArray(Charsets.US_ASCII),
            )
        }
        val relabeledSecret = privateBlock.replace("PRIVATE KEY BLOCK", "PUBLIC KEY BLOCK")
        assertThrows(PublicKeyException::class.java) {
            PublicKeyValidator.import(relabeledSecret.toByteArray(Charsets.US_ASCII))
        }
    }

    @Test
    fun `requires a single exact armor envelope and rejects trailing packet bytes`() {
        val public = fixture("valid-public.asc").toString(Charsets.US_ASCII)
        assertThrows(PublicKeyException::class.java) {
            PublicKeyValidator.import((public + public).toByteArray(Charsets.US_ASCII))
        }
        assertThrows(PublicKeyException::class.java) {
            PublicKeyValidator.import((public + "trailing").toByteArray(Charsets.US_ASCII))
        }
        assertThrows(PublicKeyException::class.java) {
            PublicKeyValidator.import(public.replaceFirst("Version:", "Version").toByteArray(Charsets.US_ASCII))
        }
    }

    @Test
    fun `stored data must match descriptor digest fingerprint and canonical bytes`() {
        val imported = PublicKeyValidator.import(fixture("valid-public.asc"))
        val changedDigest = imported.asset.copy(sha256 = "0".repeat(64))
        val changedFingerprint = imported.asset.copy(fingerprint = "0".repeat(40))
        val changedLength = imported.asset.copy(byteLength = imported.asset.byteLength - 1)

        for (asset in listOf(changedDigest, changedFingerprint, changedLength)) {
            assertThrows(PublicKeyException::class.java) {
                PublicKeyValidator.validateStored(imported.bytes, asset)
            }
        }
        assertThrows(PublicKeyException::class.java) {
            PublicKeyValidator.validateStored(imported.bytes + byteArrayOf(10), imported.asset)
        }
    }

    @Test
    fun `reference-time evaluation reports currently unusable material without changing data validity`() {
        val imported = PublicKeyValidator.import(fixture("valid-public.asc"))
        val beforeCreation = Instant.parse("1970-01-01T00:00:00Z")
        val status = PublicKeyValidator.validateStored(imported.bytes, imported.asset, beforeCreation)

        assertFalse(status.usable)
        assertNotNull(status.explanation)
        assertTrue(status.explanation!!.contains("usable"))
    }

    @Test
    fun `rejects oversized and non-ascii envelopes without exposing input`() {
        val oversized = ByteArray(65_537) { 0x41 }
        val oversizedError = assertThrows(PublicKeyException::class.java) {
            PublicKeyValidator.import(oversized)
        }
        assertFalse(oversizedError.message!!.contains("A"))

        val nonAscii = fixture("valid-public.asc") + byteArrayOf(0xff.toByte())
        assertThrows(PublicKeyException::class.java) {
            PublicKeyValidator.import(nonAscii)
        }
    }

    @Test
    fun `descriptor guard accepts only the v4 and v6 fingerprint shapes`() {
        val imported = PublicKeyValidator.import(fixture("valid-public.asc"))
        assertTrue(PublicKeyValidator.validDescriptor(imported.asset))
        assertFalse(PublicKeyValidator.validDescriptor(imported.asset.copy(fingerprint = UUID.randomUUID().toString())))
        assertFalse(PublicKeyValidator.validDescriptor(imported.asset.copy(keyVersion = 3)))
        assertFalse(PublicKeyValidator.validDescriptor(imported.asset.copy(byteLength = 65_537)))
    }

    @Test
    fun `upstream RSA3072 and native v6 certificate vectors import with verified encryption material`() {
        // bc-java r1rv86 OpenPGPTestKeys.BOB_CERT and PGPv6KeyTest.ARMORED_CERT.
        for ((name, version, algorithm) in listOf(
            Triple("bc-BOB_CERT.asc", 4, 1),
            Triple("v6-ed25519-x25519.asc", 6, 27),
        )) {
            val imported = PublicKeyValidator.import(fixture(name))
            assertEquals(version, imported.asset.keyVersion)
            assertEquals(algorithm, imported.asset.primaryAlgorithm)
            val certificate = OpenPGPKeyReader().parseCertificates(imported.bytes).single()
            val primary = certificate.primaryKey.getPGPPublicKey()
            val encryption = certificate.keys.single { !it.isPrimaryKey }.getPGPPublicKey()
            assertEquals(if (version == 4) 1 else 25, encryption.algorithm)
            if (version == 4) {
                assertEquals(3_072, primary.bitStrength)
                assertEquals(3_072, encryption.bitStrength)
            }
            val hashes = certificate.keys.flatMap { key ->
                key.getPGPPublicKey().signatures.asSequence().map { it.hashAlgorithm }.toList()
            }.toSet()
            assertEquals(setOf(HashAlgorithmTags.SHA512), hashes)
            assertTrue(PublicKeyValidator.validateStored(imported.bytes, imported.asset).usable)
        }
    }

    @Test
    fun `existing RSA3072 expiry fixture is preserved but currently unusable`() {
        // pgpainless 2.0.4 signature/CertificateExpirationTest.java second CERT literal.
        val source = fixture("pgpainless-signature-CertificateExpirationTest-CERT.asc")
        val imported = PublicKeyValidator.import(source)
        assertEquals(1, imported.asset.primaryAlgorithm)
        assertArrayEquals(decodeBody(source), decodeBody(imported.bytes))
        assertFalse(PublicKeyValidator.validateStored(imported.bytes, imported.asset).usable)
        assertTrue(PublicKeyValidator.validateStored(
            imported.bytes, imported.asset, Instant.parse("2021-04-28T14:00:00Z"),
        ).usable)
    }

    @Test
    fun `existing RSA3072 primary revocation is verified retained and disables encryption`() {
        // bc-java OpenPGPTestKeys.BOB_CERT + BOB_REVOCATION_CERT, unchanged signed packets.
        val binary = insertAfterPrimary(
            decodeBody(fixture("bc-BOB_CERT.asc")),
            decodeBody(fixture("bc-BOB_REVOCATION_CERT.asc")),
        )
        val imported = PublicKeyValidator.import(armor(binary))
        assertArrayEquals(binary, decodeBody(imported.bytes))
        val status = PublicKeyValidator.validateStored(imported.bytes, imported.asset)
        assertFalse(status.usable)
        assertEquals("This public key has been revoked.", status.explanation)
    }

    @Test
    fun `forged and truncated upstream revocation packets fail closed`() {
        val public = decodeBody(fixture("bc-BOB_CERT.asc"))
        val revocation = decodeBody(fixture("bc-BOB_REVOCATION_CERT.asc"))
        val forged = revocation.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        for (invalid in listOf(forged, revocation.copyOf(revocation.size - 1))) {
            assertThrows(PublicKeyException::class.java) {
                PublicKeyValidator.import(armor(insertAfterPrimary(public, invalid)))
            }
        }
    }

    private fun packets(binary: ByteArray): List<ContainedPacket> {
        val input = BCPGInputStream(ByteArrayInputStream(binary))
        return buildList {
            while (input.available() > 0) add(requireNotNull(input.readPacket()) as ContainedPacket)
        }
    }

    private fun encoded(packets: List<ContainedPacket>): ByteArray =
        ByteArrayOutputStream().also { out ->
            packets.forEach { out.write(it.getEncoded(PacketFormat.ROUNDTRIP)) }
        }.toByteArray()

    @Test
    fun `required self binding and v6 direct signatures cannot be omitted`() {
        for ((name, type) in listOf(
            "valid-public.asc" to PGPSignature.POSITIVE_CERTIFICATION,
            "valid-public.asc" to PGPSignature.SUBKEY_BINDING,
            "v6-ed25519-x25519.asc" to PGPSignature.DIRECT_KEY,
        )) {
            val remaining = packets(decodeBody(fixture(name))).filterNot {
                it is SignaturePacket && it.signatureType == type
            }
            assertThrows(PublicKeyException::class.java) {
                PublicKeyValidator.import(armor(encoded(remaining)))
            }
        }
    }

    @Test
    fun `required signature hash critical semantics version and designated revoker fail closed`() {
        val original = packets(decodeBody(fixture("bc-BOB_CERT.asc")))
        val index = original.indexOfFirst { it is SignaturePacket }
        val signature = original[index] as SignaturePacket
        // Re-encode existing packet fields with BC, retaining the original signature MPIs.
        // These are rejection-only mutations; no signing or key generation is performed.
        val variants = listOf(
            Triple(signature.version, HashAlgorithmTags.SHA1, signature.hashedSubPackets),
            Triple(3, signature.hashAlgorithm, signature.hashedSubPackets),
            Triple(signature.version, signature.hashAlgorithm,
                signature.hashedSubPackets + object : SignatureSubpacket(100, true, false, byteArrayOf(1)) {}),
            Triple(signature.version, signature.hashAlgorithm,
                signature.hashedSubPackets + RevocationKey(false, 0x80.toByte(), 1, ByteArray(20))),
            Triple(signature.version, signature.hashAlgorithm, signature.hashedSubPackets.filterNot {
                it.type == SignatureSubpacketTags.CREATION_TIME
            }.toTypedArray()),
        )
        for ((version, hash, hashed) in variants) {
            val changed = SignaturePacket(
                version, signature.signatureType, signature.keyID, signature.keyAlgorithm, hash,
                hashed, signature.unhashedSubPackets, signature.fingerPrint, signature.signature,
            )
            val changedPackets = original.toMutableList().also { it[index] = changed }
            assertThrows(PublicKeyException::class.java) {
                PublicKeyValidator.import(armor(encoded(changedPackets)))
            }
        }
    }

    @Test
    fun `misplaced and wrong-issuer revocations cannot be skipped behind a subkey`() {
        val public = decodeBody(fixture("valid-public.asc"))
        val foreignRevocation = decodeBody(fixture("bc-BOB_REVOCATION_CERT.asc"))
        for (binary in listOf(public + foreignRevocation, insertAfterPrimary(public, foreignRevocation))) {
            assertThrows(PublicKeyException::class.java) {
                PublicKeyValidator.import(armor(binary))
            }
        }
    }

    @Test
    fun `subkey and user id count bounds fail before certificate aggregation`() {
        val source = packets(decodeBody(fixture("valid-public.asc")))
        val subkey = source.indexOfFirst { it.packetTag == 14 }
        val userId = source.indexOfFirst { it.packetTag == 13 }
        val excessiveSubkeys = source.take(subkey) + List(5) { source.drop(subkey) }.flatten()
        val excessiveUserIds = source.take(userId) +
            List(9) { source.subList(userId, subkey) }.flatten() + source.drop(subkey)
        for (invalid in listOf(excessiveSubkeys, excessiveUserIds)) {
            assertThrows(PublicKeyException::class.java) {
                PublicKeyValidator.import(armor(encoded(invalid)))
            }
        }
    }
}
