package com.mackseoul.vcardnative.data.keys

import com.mackseoul.vcardnative.domain.keys.DraftPublicKey
import com.mackseoul.vcardnative.domain.keys.PublicKeyException
import com.mackseoul.vcardnative.domain.keys.PublicKeyStatus
import com.mackseoul.vcardnative.domain.profile.ProfileValidator
import com.mackseoul.vcardnative.domain.profile.PublicKeyAsset
import org.bouncycastle.bcpg.BCPGInputStream
import org.bouncycastle.bcpg.ContainedPacket
import org.bouncycastle.bcpg.Packet
import org.bouncycastle.bcpg.PacketFormat
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.ECDHPublicBCPGKey
import org.bouncycastle.bcpg.EdDSAPublicBCPGKey
import org.bouncycastle.bcpg.X25519PublicBCPGKey
import org.bouncycastle.bcpg.Ed25519PublicBCPGKey
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.SignaturePacket
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.api.OpenPGPCertificate
import org.bouncycastle.openpgp.api.OpenPGPDefaultPolicy
import org.bouncycastle.openpgp.api.OpenPGPKeyReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

object PublicKeyValidator {
    private const val MAX_INPUT_BYTES = 65_536
    private const val MAX_PACKETS = 128
    private const val MAX_SUBKEYS = 4
    private const val MAX_USER_IDS = 8
    private const val MAX_ARMOR_HEADERS = 16
    private const val MAX_ARMOR_HEADER_BYTES = 4_096
    private const val ARMOR_BEGIN = "-----BEGIN PGP PUBLIC KEY BLOCK-----"
    private const val ARMOR_END = "-----END PGP PUBLIC KEY BLOCK-----"
    private const val FORMAT = "openpgp-public-armored"
    private const val MEDIA_TYPE = "application/pgp-keys"
    private const val CURVE25519_LEGACY_OID = "1.3.6.1.4.1.3029.1.5.1"
    private const val ED25519_LEGACY_OID = "1.3.6.1.4.1.11591.15.1"
    private val acceptedHashes = setOf(
        HashAlgorithmTags.SHA256,
        HashAlgorithmTags.SHA384,
        HashAlgorithmTags.SHA512,
    )
    private val allowedPacketTags = setOf(2, 6, 10, 13, 14, 21)
    private val signaturePolicy = OpenPGPDefaultPolicy()

    fun import(bytes: ByteArray): DraftPublicKey = guarded {
        requireInputSize(bytes)
        val armored = parseArmor(bytes)
        val inventory = enumeratePackets(armored.binary)
        val certificate = parseSingleCertificate(inventory.certificateBytes)
        val verified = verifyCertificate(certificate, inventory)
        val canonical = armor(armored.binary)
        val canonicalInventory = enumeratePackets(parseArmor(canonical).binary)
        require(canonicalInventory.bytes.contentEquals(armored.binary)) {
            "The public-key data could not be preserved during normalization."
        }
        val reparsed = parseSingleCertificate(canonicalInventory.certificateBytes)
        val reverified = verifyCertificate(reparsed, canonicalInventory)
        require(
            verified.primary.fingerprint.contentEquals(reverified.primary.fingerprint) &&
                verified.primary.version == reverified.primary.version &&
                verified.primary.algorithm == reverified.primary.algorithm
        ) { "The normalized public-key data did not match the verified certificate." }

        val asset = PublicKeyAsset(
            assetId = UUID.randomUUID(),
            format = FORMAT,
            keyVersion = verified.primary.version,
            primaryAlgorithm = verified.primary.algorithm,
            mediaType = MEDIA_TYPE,
            byteLength = canonical.size.toLong(),
            sha256 = sha256(canonical),
            fingerprint = verified.primary.fingerprint.toHex(uppercase = true),
        )
        require(ProfileValidator.validPublicKeyDescriptor(asset)) {
            "The public-key descriptor is not supported."
        }
        DraftPublicKey(asset, canonical)
    }

    fun validateStored(bytes: ByteArray, asset: PublicKeyAsset): PublicKeyStatus =
        validateStored(bytes, asset, Instant.now())

    fun validateStored(bytes: ByteArray, asset: PublicKeyAsset, referenceTime: Instant): PublicKeyStatus =
        guarded {
            require(ProfileValidator.validPublicKeyDescriptor(asset)) {
                "The stored public-key descriptor is invalid."
            }
            requireInputSize(bytes)
            require(bytes.size.toLong() == asset.byteLength) {
                "The stored public-key length does not match its descriptor."
            }
            require(sha256(bytes) == asset.sha256) {
                "The stored public-key digest does not match its descriptor."
            }
            val armored = parseArmor(bytes)
            val inventory = enumeratePackets(armored.binary)
            val certificate = parseSingleCertificate(inventory.certificateBytes)
            val verified = verifyCertificate(certificate, inventory)
            val normalized = armor(armored.binary)
            require(bytes.contentEquals(normalized)) {
                "The stored public-key armor is not canonical."
            }
            require(asset.keyVersion == verified.primary.version) {
                "The stored public-key version does not match its descriptor."
            }
            require(asset.primaryAlgorithm == verified.primary.algorithm) {
                "The stored public-key algorithm does not match its descriptor."
            }
            require(asset.fingerprint == verified.primary.fingerprint.toHex(uppercase = true)) {
                "The stored public-key fingerprint does not match its descriptor."
            }
            evaluateUsability(certificate, verified, referenceTime)
        }

    fun validDescriptor(asset: PublicKeyAsset): Boolean =
        ProfileValidator.validPublicKeyDescriptor(asset)

    private data class ArmorData(val binary: ByteArray)

    private data class PacketInventory(
        val bytes: ByteArray,
        val certificateBytes: ByteArray,
        val packetCount: Int,
        val subkeyCount: Int,
        val userIdCount: Int,
        val signatureCount: Int,
    )

    private data class PrimaryInfo(
        val version: Int,
        val algorithm: Int,
        val fingerprint: ByteArray,
    )

    private data class VerifiedCertificate(
        val primary: PrimaryInfo,
        val encryptionKeyIds: Set<Long>,
        val primaryRevokedAt: List<RevocationState>,
        val subkeyRevocations: Map<Long, List<RevocationState>>,
    )

    private data class RevocationState(val created: Instant, val hard: Boolean) {
        fun effectiveAt(time: Instant): Boolean = hard || !created.isAfter(time)
    }

    private fun parseArmor(bytes: ByteArray): ArmorData {
        require(bytes.all { (it.toInt() and 0x80) == 0 }) {
            "The public-key armor must contain ASCII text only."
        }
        val text = bytes.toString(Charsets.US_ASCII)
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val begin = lines.indexOfFirst { it.trimAsciiWhitespace() == ARMOR_BEGIN }
        require(begin >= 0) {
            "Input must be one armored public-key block."
        }
        val end = lines.indexOfFirstFrom(begin + 1) { it.trimAsciiWhitespace() == ARMOR_END }
        require(end > begin) { "The armored public-key block is incomplete." }
        require(lines.take(begin).all { it.isAsciiWhitespaceOnly() } &&
            lines.drop(end + 1).all { it.isAsciiWhitespaceOnly() }) {
            "Only whitespace may appear outside the public-key block."
        }

        var bodyStarted = false
        var headerCount = 0
        var headerBytes = 0
        val base64 = StringBuilder()
        for (line in lines.subList(begin + 1, end)) {
            require(line.all { it.code in 0x20..0x7e } || line.isEmpty()) {
                "The armored public-key envelope contains invalid text."
            }
            if (line.isEmpty()) {
                if (bodyStarted) continue
                bodyStarted = true
                continue
            }
            if (bodyStarted && line.startsWith("=")) continue
            if (!bodyStarted && line.contains(':')) {
                headerCount++
                headerBytes += line.length
                require(
                    headerCount <= MAX_ARMOR_HEADERS &&
                        line.length <= 512 &&
                        headerBytes <= MAX_ARMOR_HEADER_BYTES
                ) { "The armored public-key headers exceed the supported limit." }
                require(line.substringBefore(':').matches(Regex("[A-Za-z0-9-]{1,64}"))) {
                    "The armored public-key headers are malformed."
                }
                continue
            }
            bodyStarted = true
            require(line.matches(Regex("[A-Za-z0-9+/]+={0,2}"))) {
                "The armored public-key body is not valid base64."
            }
            base64.append(line)
        }
        require(bodyStarted && base64.isNotEmpty()) { "The armored public-key body is empty." }
        val decoded = try {
            Base64.getDecoder().decode(base64.toString())
        } catch (_: IllegalArgumentException) {
            throw PublicKeyException("The armored public-key body is not valid base64.")
        }
        require(decoded.isNotEmpty() && decoded.size <= MAX_INPUT_BYTES) {
            "The decoded public-key data exceeds the supported limit."
        }
        return ArmorData(decoded)
    }

    private fun enumeratePackets(bytes: ByteArray): PacketInventory {
        val source = ByteArrayInputStream(bytes)
        val input = BCPGInputStream(source)
        val roundTrip = ByteArrayOutputStream(bytes.size)
        val certificatePackets = ByteArrayOutputStream(bytes.size)
        var count = 0
        var subkeys = 0
        var userIds = 0
        var signatures = 0
        var primaryCount = 0
        var targetTag = -1
        while (input.available() > 0) {
            val packetStart = bytes.size - source.available()
            require(++count <= MAX_PACKETS) { "The public-key packet count exceeds the supported limit." }
            val tag = input.nextPacketTag()
            require(tag in allowedPacketTags) {
                when (tag) {
                    5, 7 -> "Secret-key material is not accepted."
                    12, 17 -> "The public-key block contains an unsupported packet."
                    else -> "The public-key block contains an unsupported packet."
                }
            }
            val packet = input.readPacket()
            require(packet != null && packet.packetTag == tag) {
                "The public-key packet stream is malformed."
            }
            when (tag) {
                6 -> {
                    require(++primaryCount == 1 && targetTag == -1) {
                        "Input must contain exactly one primary public key."
                    }
                    targetTag = 6
                }
                14 -> {
                    require(primaryCount == 1) { "A public subkey has no primary key." }
                    subkeys++
                    targetTag = 14
                }
                13 -> {
                    require(primaryCount == 1 && subkeys == 0) {
                        "A public user ID is outside the supported certificate order."
                    }
                    userIds++
                    targetTag = 13
                }
                2 -> {
                    signatures++
                    val signature = packet as? SignaturePacket
                        ?: throw PublicKeyException("The public-key signature packet is malformed.")
                    val allowedTypes = when (targetTag) {
                        6 -> setOf(PGPSignature.DIRECT_KEY, PGPSignature.KEY_REVOCATION)
                        13 -> CERTIFICATION_TYPES + PGPSignature.CERTIFICATION_REVOCATION
                        14 -> setOf(PGPSignature.SUBKEY_BINDING, PGPSignature.SUBKEY_REVOCATION)
                        else -> emptySet()
                    }
                    require(signature.signatureType in allowedTypes) {
                        "A public-key signature is outside the supported certificate order."
                    }
                }
            }
            require(subkeys <= MAX_SUBKEYS) { "The public-key block has too many subkeys." }
            require(userIds <= MAX_USER_IDS) { "The public-key block has too many user IDs." }
            val contained = packet as? ContainedPacket
                ?: throw PublicKeyException("The public-key packet could not be accounted for.")
            val encoded = contained.getEncoded(PacketFormat.ROUNDTRIP)
            val packetEnd = bytes.size - source.available()
            require(encoded.size == packetEnd - packetStart &&
                encoded.contentEquals(bytes.copyOfRange(packetStart, packetEnd))) {
                "A complete public-key packet could not be accounted for."
            }
            val framing = if (packet.hasNewPacketFormat()) {
                contained.getEncoded(PacketFormat.CURRENT)
            } else {
                contained.getEncoded(PacketFormat.LEGACY)
            }
            require(encoded.contentEquals(framing)) {
                "The public-key packet framing is not supported."
            }
            roundTrip.write(encoded)
            if (tag != 10 && tag != 21) certificatePackets.write(encoded)
            require(roundTrip.size() <= bytes.size) {
                "The public-key packet accounting exceeded the input."
            }
        }
        val accounted = roundTrip.toByteArray()
        require(count > 0 && accounted.size == bytes.size && accounted.contentEquals(bytes)) {
            "The complete public-key packet stream could not be accounted for."
        }
        require(bytes.size <= MAX_INPUT_BYTES) { "The public-key packet stream exceeds the supported limit." }
        return PacketInventory(accounted, certificatePackets.toByteArray(), count, subkeys, userIds, signatures)
    }

    private fun parseSingleCertificate(bytes: ByteArray): OpenPGPCertificate {
        val certificates = try {
            OpenPGPKeyReader().parseCertificates(bytes)
        } catch (_: Exception) {
            throw PublicKeyException("The public-key certificate could not be parsed.")
        }
        require(certificates.size == 1) {
            "Input must contain exactly one public-key certificate."
        }
        require(!certificates.single().isSecretKey) { "Secret-key material is not accepted." }
        return certificates.single()
    }

    private fun verifyCertificate(
        certificate: OpenPGPCertificate,
        inventory: PacketInventory,
    ): VerifiedCertificate {
        val primary = certificate.primaryKey.getPGPPublicKey()
        val version = primary.version
        val algorithm = primary.algorithm
        require(version == 4 || version == 6) { "The public-key version is not supported." }
        require(
            when (version) {
                4 -> algorithm == PublicKeyAlgorithmTags.RSA_GENERAL ||
                    algorithm == PublicKeyAlgorithmTags.EDDSA_LEGACY
                else -> algorithm == PublicKeyAlgorithmTags.RSA_GENERAL ||
                    algorithm == PublicKeyAlgorithmTags.Ed25519
            }
        ) { "The primary public-key algorithm is not supported." }
        requireSupportedPrimaryParameters(primary)

        val keys = certificate.keys
        require(keys.isNotEmpty() && keys.count { !it.isPrimaryKey } == inventory.subkeyCount) {
            "The public-key components do not match the packet stream."
        }
        require(certificate.primaryKey.userIDs.size <= MAX_USER_IDS) {
            "The public-key block has too many user IDs."
        }
        require(keys.sumOf { it.getPGPPublicKey().signatures.asSequence().count() } == inventory.signatureCount) {
            "The complete public-key signature stream could not be accounted for."
        }
        for (component in keys) {
            val key = component.getPGPPublicKey()
            require(key.version == version) { "Mixed public-key versions are not supported." }
            if (!component.isPrimaryKey) requireSupportedSubkey(primary, key, version)
        }

        val selfSignatures = mutableListOf<PGPSignature>()
        val requiredRevocations = mutableListOf<PGPSignature>()
        val primaryKeySignatures = primary.keySignatures.asSequence()
        val direct = primaryKeySignatures.filter {
            it.signatureType == PGPSignature.DIRECT_KEY && isIssuedBy(it, primary)
        }.toList()
        if (version == 6) {
            require(direct.isNotEmpty()) { "The v6 primary key has no direct-key self-signature." }
        }
        direct.forEach { verifySignature(it, primary) }
        selfSignatures += direct

        val userIdIterator = primary.userIDs
        var verifiedUserIds = 0
        while (userIdIterator.hasNext()) {
            val userId = userIdIterator.next()
            val signatures = primary.getSignaturesForID(userId).asSequence()
                .filter { it.signatureType in CERTIFICATION_TYPES && isIssuedBy(it, primary) }
                .toList()
            require(signatures.isNotEmpty()) {
                "A public-key user ID has no verifiable self-certification."
            }
            signatures.forEach { verifySignature(it, primary, userId) }
            selfSignatures += signatures
            verifiedUserIds++
        }
        require(inventory.userIdCount == verifiedUserIds) {
            "The public-key user identities could not be verified."
        }
        if (version == 4) {
            require(direct.isNotEmpty() || verifiedUserIds > 0) {
                "The primary key has no verifiable self-certification."
            }
        }
        require(selfSignatures.any {
            (it.hashedSubPackets?.keyFlags ?: 0) and KeyFlags.CERTIFY_OTHER != 0
        }) { "The primary key is not certified for signing." }
        val primarySelfSignatures = selfSignatures.toList()

        val verifiedEncryptionIds = mutableSetOf<Long>()
        val subkeyRevocations = mutableMapOf<Long, MutableList<RevocationState>>()
        val primaryRevocations = mutableListOf<RevocationState>()
        val primaryRevocationSignatures = primary.getSignaturesOfType(PGPSignature.KEY_REVOCATION).asSequence().toList()
        primaryRevocationSignatures.forEach { signature ->
            require(isIssuedBy(signature, primary)) {
                "A designated or unverifiable primary-key revocation is not supported."
            }
            verifySignature(signature, primary)
            primaryRevocations += RevocationState(signature.creationTime.toInstant(), signature.isHardRevocation)
            requiredRevocations += signature
        }

        for (component in keys.filterNot { it.isPrimaryKey }) {
            val subkey = component.getPGPPublicKey()
            val bindings = subkey.getSignaturesOfType(PGPSignature.SUBKEY_BINDING).asSequence()
                .filter { isIssuedBy(it, primary) }
                .toList()
            require(bindings.isNotEmpty()) { "A public subkey has no verifiable binding." }
            bindings.forEach { verifySignature(it, primary, subkey) }
            selfSignatures += bindings
            val hasEncryptionFlag = bindings.any {
                val flags = it.hashedSubPackets?.keyFlags ?: 0
                flags and (KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE) != 0
            }
            require(hasEncryptionFlag) { "A public subkey has no verified encryption key flag." }
            verifiedEncryptionIds += subkey.keyID

            if (bindings.any {
                    val flags = it.hashedSubPackets?.keyFlags ?: 0
                    flags and (KeyFlags.SIGN_DATA or KeyFlags.CERTIFY_OTHER) != 0
                }
            ) {
                for (binding in bindings) {
                    val flags = binding.hashedSubPackets?.keyFlags ?: 0
                    if (flags and (KeyFlags.SIGN_DATA or KeyFlags.CERTIFY_OTHER) == 0) continue
                    val embedded = try {
                        binding.hashedSubPackets?.embeddedSignatures?.asSequence()?.toList().orEmpty()
                    } catch (_: Exception) {
                        throw PublicKeyException("A signing subkey binding is malformed.")
                    }
                    val crossCertifications = embedded.filter {
                        it.signatureType == PGPSignature.PRIMARYKEY_BINDING
                    }
                    require(crossCertifications.isNotEmpty()) {
                        "A signing public subkey has no embedded primary-key binding."
                    }
                    crossCertifications.forEach { signature ->
                        verifySignature(signature, subkey, primary)
                    }
                    selfSignatures += crossCertifications
                }
            }

            val revocations = subkey.getSignaturesOfType(PGPSignature.SUBKEY_REVOCATION).asSequence().toList()
            revocations.forEach { signature ->
                require(isIssuedBy(signature, primary)) {
                    "A designated or unverifiable subkey revocation is not supported."
                }
                verifySignature(signature, primary, subkey)
                subkeyRevocations.getOrPut(subkey.keyID) { mutableListOf() }
                    .add(RevocationState(signature.creationTime.toInstant(), signature.isHardRevocation))
                requiredRevocations += signature
            }
        }

        for (userId in primary.userIDs.asSequence()) {
            val revocations = primary.getSignaturesForID(userId).asSequence()
                .filter { it.signatureType == PGPSignature.CERTIFICATION_REVOCATION }
                .toList()
            revocations.forEach { signature ->
                require(isIssuedBy(signature, primary)) {
                    "A designated or unverifiable user-ID revocation is not supported."
                }
                verifySignature(signature, primary, userId)
                requiredRevocations += signature
            }
        }

        val primaryCanEncrypt = supportedEncryptionAlgorithm(primary, version) &&
            primarySelfSignatures.any {
                it.hashedSubPackets?.keyFlags?.let { flags ->
                    flags and (KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE) != 0
                } == true && isIssuedBy(it, primary)
            }
        if (primaryCanEncrypt) verifiedEncryptionIds += primary.keyID
        require(verifiedEncryptionIds.isNotEmpty()) {
            "The public-key certificate has no supported encryption-capable component."
        }
        require(selfSignatures.isNotEmpty()) {
            "The public-key certificate has no verified self-signatures."
        }
        val primaryFlags = primarySelfSignatures.any {
            isIssuedBy(it, primary) &&
                (it.hashedSubPackets?.keyFlags ?: 0) and KeyFlags.CERTIFY_OTHER != 0
        }
        require(primaryFlags) { "The primary key is not certified for signing." }

        val requiredSignatures = (selfSignatures + requiredRevocations).distinct()
        requiredSignatures.forEach(::checkSignaturePolicy)

        return VerifiedCertificate(
            PrimaryInfo(version, algorithm, primary.fingerprint),
            verifiedEncryptionIds,
            primaryRevocations,
            subkeyRevocations,
        )
    }

    private fun requireSupportedPrimaryParameters(primary: PGPPublicKey) {
        if (primary.algorithm == PublicKeyAlgorithmTags.RSA_GENERAL) {
            requireRsaStrength(primary)
        } else if (primary.version == 4) {
            val key = primary.publicKeyPacket.key
            require(key is EdDSAPublicBCPGKey && key.curveOID.id == ED25519_LEGACY_OID) {
                "The v4 EdDSA primary key is not Ed25519Legacy."
            }
        }
        if (primary.version == 6 && primary.algorithm == PublicKeyAlgorithmTags.Ed25519) {
            require(primary.publicKeyPacket.key is Ed25519PublicBCPGKey) {
                "The v6 Ed25519 primary key parameters are not supported."
            }
        }
    }

    private fun requireSupportedSubkey(primary: PGPPublicKey, subkey: PGPPublicKey, version: Int) {
        require(supportedEncryptionAlgorithm(subkey, version)) {
            "A public subkey algorithm is not supported."
        }
        if (subkey.algorithm == PublicKeyAlgorithmTags.RSA_GENERAL) requireRsaStrength(subkey)
        if (version == 4 && subkey.algorithm == PublicKeyAlgorithmTags.ECDH) {
            val key = subkey.publicKeyPacket.key
            require(key is ECDHPublicBCPGKey && key.curveOID.id == CURVE25519_LEGACY_OID) {
                "The v4 ECDH subkey is not Curve25519Legacy."
            }
        }
        if (version == 6 && subkey.algorithm == PublicKeyAlgorithmTags.X25519) {
            require(subkey.publicKeyPacket.key is X25519PublicBCPGKey) {
                "The v6 X25519 subkey parameters are not supported."
            }
        }
        require(subkey.creationTime >= primary.creationTime) {
            "A public subkey predates its primary key."
        }
    }

    private fun supportedEncryptionAlgorithm(key: PGPPublicKey, version: Int): Boolean =
        when (version) {
            4 -> key.algorithm == PublicKeyAlgorithmTags.RSA_GENERAL ||
                key.algorithm == PublicKeyAlgorithmTags.ECDH
            6 -> key.algorithm == PublicKeyAlgorithmTags.RSA_GENERAL ||
                key.algorithm == PublicKeyAlgorithmTags.X25519
            else -> false
        }

    private fun requireRsaStrength(key: PGPPublicKey) {
        require(key.bitStrength in 3_072..8_192) {
            "An RSA public-key component is outside the supported strength range."
        }
    }

    private fun verifySignature(signature: PGPSignature, issuer: PGPPublicKey, userId: String? = null) {
        checkSignaturePolicy(signature)
        requireSignatureMatchesKey(signature, issuer)
        require(isIssuedBy(signature, issuer)) { "A required public-key signature has an unsupported issuer." }
        try {
            signature.init(
                org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider(),
                issuer,
            )
            val valid = when {
                userId != null -> signature.verifyCertification(userId, issuer)
                signature.signatureType == PGPSignature.SUBKEY_BINDING ||
                    signature.signatureType == PGPSignature.SUBKEY_REVOCATION -> false
                else -> signature.verifyCertification(issuer)
            }
            require(valid) { "A required public-key signature is invalid." }
        } catch (_: PublicKeyException) {
            throw PublicKeyException("A required public-key signature is invalid.")
        } catch (_: Exception) {
            throw PublicKeyException("A required public-key signature could not be verified.")
        }
    }

    private fun verifySignature(signature: PGPSignature, issuer: PGPPublicKey, subject: PGPPublicKey) {
        checkSignaturePolicy(signature)
        requireSignatureMatchesKey(signature, issuer)
        require(signature.creationTime >= subject.creationTime) {
            "A required public-key signature predates its component."
        }
        require(isIssuedBy(signature, issuer)) { "A required public-key signature has an unsupported issuer." }
        try {
            signature.init(
                org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider(),
                issuer,
            )
            val valid = if (signature.signatureType == PGPSignature.PRIMARYKEY_BINDING) {
                signature.verifyCertification(subject, issuer)
            } else {
                signature.verifyCertification(issuer, subject)
            }
            require(valid) {
                "A required public-key signature is invalid."
            }
        } catch (_: PublicKeyException) {
            throw PublicKeyException("A required public-key signature is invalid.")
        } catch (_: Exception) {
            throw PublicKeyException("A required public-key signature could not be verified.")
        }
    }

    private fun checkSignaturePolicy(signature: PGPSignature) {
        require(signature.hashAlgorithm in acceptedHashes) {
            "A required public-key signature uses an unsupported hash algorithm."
        }
        val hashed = signature.hashedSubPackets
        require(hashed?.signatureCreationTime != null) {
            "A required public-key signature has no authenticated creation time."
        }
        require(
            signaturePolicy.hasNoCriticalUnknownSubpackets(signature) &&
                signaturePolicy.hasNoCriticalUnknownNotations(signature)
        ) { "A required public-key signature has unsupported critical semantics." }
        require(hashed?.revocationKeys.isNullOrEmpty()) {
            "Designated third-party revocation is not supported."
        }
    }

    private fun isIssuedBy(signature: PGPSignature, issuer: PGPPublicKey): Boolean {
        val vectors = listOfNotNull(signature.hashedSubPackets, signature.unhashedSubPackets)
        val fingerprints = vectors.mapNotNull { it.issuerFingerprint }
        val keyIds = vectors.map { it.issuerKeyID }.filter { it != 0L }
        if (fingerprints.any { it.keyVersion != issuer.version ||
                !it.fingerprint.contentEquals(issuer.fingerprint) } ||
            keyIds.any { it != issuer.keyID }) return false
        return fingerprints.isNotEmpty() || keyIds.isNotEmpty()
    }

    private fun requireSignatureMatchesKey(signature: PGPSignature, issuer: PGPPublicKey) {
        require(signature.version == issuer.version && signature.keyAlgorithm == issuer.algorithm) {
            "A required public-key signature has an unsupported version or algorithm."
        }
        require(signature.creationTime >= issuer.creationTime) {
            "A required public-key signature predates its issuer."
        }
        if (issuer.version == 6) {
            require(signature.hashedSubPackets?.issuerFingerprint?.fingerprint
                ?.contentEquals(issuer.fingerprint) == true) {
                "A v6 public-key signature has no authenticated issuer fingerprint."
            }
        }
    }

    private fun evaluateUsability(
        certificate: OpenPGPCertificate,
        verified: VerifiedCertificate,
        referenceTime: Instant,
    ): PublicKeyStatus {
        val date = Date.from(referenceTime)
        val revokedPrimary = verified.primaryRevokedAt.any { it.effectiveAt(referenceTime) }
        if (revokedPrimary) {
            return PublicKeyStatus(false, "This public key has been revoked.")
        }
        val available = certificate.getEncryptionKeys(date).any { component ->
            component.getPGPPublicKey().keyID in verified.encryptionKeyIds &&
                when {
                    component.isPrimaryKey -> true
                    else -> verified.subkeyRevocations[component.getPGPPublicKey().keyID]
                        .orEmpty().none { it.effectiveAt(referenceTime) }
                }
        }
        return if (available) {
            PublicKeyStatus(true, null)
        } else {
            PublicKeyStatus(false, "No supported encryption key is currently usable.")
        }
    }

    private fun armor(binary: ByteArray): ByteArray {
        val body = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(binary)
        return "$ARMOR_BEGIN\n\n$body\n$ARMOR_END\n".toByteArray(Charsets.US_ASCII)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex(uppercase = false)

    private fun ByteArray.toHex(uppercase: Boolean): String =
        joinToString("") { byte ->
            val encoded = "%02x".format(byte.toInt() and 0xff)
            if (uppercase) encoded.uppercase() else encoded
        }

    private fun requireInputSize(bytes: ByteArray) {
        require(bytes.size in 1..MAX_INPUT_BYTES) {
            "The public-key input must be between 1 and 65,536 bytes."
        }
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (exception: PublicKeyException) {
        throw exception
    } catch (_: Exception) {
        throw PublicKeyException("The public-key data is invalid or unsupported.")
    }

    private fun String.isAsciiWhitespaceOnly(): Boolean = all { it in " \t\r\n" }
    private fun String.trimAsciiWhitespace(): String = trim { it in " \t\r\n" }

    private inline fun <T> List<T>.indexOfFirstFrom(start: Int, predicate: (T) -> Boolean): Int {
        for (index in start until size) if (predicate(this[index])) return index
        return -1
    }

    private val CERTIFICATION_TYPES = setOf(
        PGPSignature.DEFAULT_CERTIFICATION,
        PGPSignature.NO_CERTIFICATION,
        PGPSignature.CASUAL_CERTIFICATION,
        PGPSignature.POSITIVE_CERTIFICATION,
    )
}
