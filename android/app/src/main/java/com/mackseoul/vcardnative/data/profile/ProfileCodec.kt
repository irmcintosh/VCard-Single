package com.mackseoul.vcardnative.data.profile

import com.mackseoul.vcardnative.domain.profile.*
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

internal data class Manifest(
    val id: String,
    val sequence: Long,
    val previous: String?,
    val saved: SavedProfile,
    val blobs: Map<ImageRole, String> = emptyMap(),
    val keyBlob: String? = null,
    val kind: String = "save",
)

internal object ProfileCodec {
    internal fun fieldsForSchema(version: Int): List<ProfileField> =
        when {
            version >= 4 -> ProfileField.entries.filter { it !in legacySocialFields }
            version >= 3 -> ProfileField.entries
            else -> ProfileField.entries.filter { it !in structuredNameFields }
        }

    internal fun textFieldsForSchema(version: Int): List<ProfileField> =
        fieldsForSchema(version).filter { it in textFields }

    fun encode(manifest: Manifest): ByteArray {
        val assets = imageAssets(manifest.saved.data)
        require(manifest.blobs.keys == assets.keys)
        assets.forEach { (role, asset) -> require(ProfileValidator.validImageDescriptor(asset, role)) }
        manifest.blobs.values.forEach { uuid(it) }
        require((manifest.keyBlob != null) == (manifest.saved.data.profile.publicKey != null))
        manifest.keyBlob?.let { uuid(it) }
        require(manifest.kind in setOf("save", "restore"))
        val payload = linkedMapOf(
            "format" to 1, "manifestId" to manifest.id, "sequence" to manifest.sequence,
            "previous" to manifest.previous, "generationId" to manifest.saved.token.generationId,
            "revision" to manifest.saved.token.revision, "data" to encodeData(manifest.saved.data)
        )
        if (manifest.blobs.isNotEmpty())
            payload["imageBlobs"] = manifest.blobs.mapKeys { it.key.name }
        manifest.keyBlob?.let { payload["keyBlob"] = it }
        if (manifest.kind != "save") payload["operation"] = manifest.kind
        return StrictJson.encode(linkedMapOf("payload" to payload, "sha256" to sha256(StrictJson.encode(payload))))
    }

    fun decode(bytes: ByteArray): Manifest {
        val envelope = StrictJson.decode(bytes, allowArrays = true).jsonObject("payload", "sha256")
        val raw = envelope["payload"] as? Map<*, *> ?: error("Invalid payload")
        val keys = arrayOf("format", "manifestId", "sequence", "previous", "generationId", "revision", "data")
        val optional = listOf("imageBlobs", "keyBlob", "operation").filter { raw.containsKey(it) }
        val payload = envelope["payload"].jsonObject(*(keys + optional))
        require(sha256(StrictJson.encode(payload)) == envelope.string("sha256"))
        require(payload.long("format") == 1L)
        val id = uuid(payload.string("manifestId"))
        val previous = payload["previous"]?.let { uuid(it as String) }
        val generation = uuid(payload.string("generationId"))
        val sequence = payload.long("sequence").also { require(it > 0) }
        val revision = payload.long("revision").also { require(it > 0) }
        val data = decodeData(payload["data"])
        val roles = imageAssets(data).keys
        val blobs = if (payload.containsKey("imageBlobs")) {
            val table = payload["imageBlobs"].jsonObject(*roles.map { it.name }.toTypedArray())
            roles.associateWith { uuid(table.string(it.name)) }
        } else {
            require(roles.isEmpty())
            emptyMap()
        }
        val keyBlob = if (payload.containsKey("keyBlob")) uuid(payload.string("keyBlob")) else null
        require((keyBlob != null) == (data.profile.publicKey != null))
        val kind = if (payload.containsKey("operation")) payload.string("operation").also { require(it == "restore") } else "save"
        return Manifest(id, sequence, previous, SavedProfile(data, RevisionToken(generation, revision)), blobs, keyBlob, kind)
    }

    internal fun encodeData(data: AppData): Map<String, Any?> {
        require(data.schemaVersion in 1..AppData.CURRENT_SCHEMA_VERSION)
        require(data.schemaVersion >= 3 || structuredNameFields.none { data.value(it) != null }) {
            "Structured names require schema 3."
        }
        data.profile.publicKey?.let { require(ProfileValidator.validPublicKeyDescriptor(it)) }
        val profile = linkedMapOf<String, Any?>(
            "profileId" to data.profile.profileId.toString(),
            "fields" to textFieldsForSchema(data.schemaVersion).associate { it.key to data.value(it) },
            "visibility" to fieldsForSchema(data.schemaVersion).associate { field ->
                field.key to linkedMapOf(
                    "showOnCard" to data.visibility(field).showOnCard,
                    "includeInContactExport" to data.visibility(field).includeInContactExport
                )
            },
            "publicKey" to encodeKey(data.profile.publicKey),
            "profileImage" to encodeImage(data.profile.profileImage),
            "coverImage" to encodeImage(data.profile.coverImage),
        )
        if (data.schemaVersion >= 4) {
            profile["socialProfiles"] = data.profile.socialProfiles.map(::encodeSocialProfile)
        }
        if (data.schemaVersion >= 2) {
            profile["phoneRegion"] = data.profile.phoneRegion
            profile["includeCountryCodeWhenSharing"] = data.profile.includeCountryCodeWhenSharing
            profile["phoneNumberE164"] = data.profile.phoneNumberE164
        }
        return linkedMapOf(
            "schemaVersion" to data.schemaVersion,
            "profile" to profile,
            "appearance" to encodeAppearance(data.schemaVersion, data.appearance),
            "metadata" to linkedMapOf("createdAt" to data.metadata.createdAt, "updatedAt" to data.metadata.updatedAt)
        )
    }

    internal fun currentSchemaData(data: AppData): AppData {
        require(data.schemaVersion in 1..AppData.CURRENT_SCHEMA_VERSION)
        val phone = data.profile.values[ProfileField.Phone].orEmpty()
        val region = if (data.schemaVersion == 1) {
            PhoneNumbers.regionForNumber(phone, PhoneNumbers.defaultRegion())
        } else data.profile.phoneRegion
        val e164 = if (data.schemaVersion == 1) PhoneNumbers.parse(phone, region)?.e164
        else data.profile.phoneNumberE164
        val socialProfiles = data.profile.socialProfiles.ifEmpty {
            migrateLegacySocialProfiles(data.profile.profileId, data.profile.values, data.profile.visibility)
        }
        val projection = projectLegacySocialFields(
            data.profile.values, data.profile.visibility, socialProfiles,
        )
        return data.copy(
            schemaVersion = AppData.CURRENT_SCHEMA_VERSION,
            profile = data.profile.copy(
                values = projection.values,
                visibility = projection.visibility,
                phoneRegion = region,
                includeCountryCodeWhenSharing = if (data.schemaVersion == 1) true
                else data.profile.includeCountryCodeWhenSharing,
                phoneNumberE164 = e164,
                socialProfiles = socialProfiles,
            ),
        )
    }

    internal fun decodeData(value: Any?): AppData {
        val data = value.jsonObject("schemaVersion", "profile", "appearance", "metadata")
        val schemaVersion = data.long("schemaVersion").also {
            require(it in 1L..AppData.CURRENT_SCHEMA_VERSION.toLong())
        }.toInt()
        val legacyProfileKeys = listOf("profileId", "fields", "visibility", "publicKey", "profileImage", "coverImage")
        val currentProfileKeys = legacyProfileKeys +
            listOf("phoneRegion", "includeCountryCodeWhenSharing", "phoneNumberE164")
        val collectionProfileKeys = currentProfileKeys + "socialProfiles"
        val profile = data["profile"].jsonObject(
            *(when (schemaVersion) {
                1 -> legacyProfileKeys
                in 2..3 -> currentProfileKeys
                else -> collectionProfileKeys
            }).toTypedArray()
        )
        val publicKey = decodeKey(profile["publicKey"])
        val fields = profile["fields"].jsonObject(*textFieldsForSchema(schemaVersion).map { it.key }.toTypedArray())
        val values = linkedMapOf<ProfileField, String>()
        for (field in textFields) {
            val item = fields[field.key]
            require(item == null || item is String)
            if (item != null) values[field] = item as String
        }
        val socialProfiles = if (schemaVersion >= 4) decodeSocialProfiles(profile["socialProfiles"]) else emptyList()
        val settings = profile["visibility"].jsonObject(*fieldsForSchema(schemaVersion).map { it.key }.toTypedArray())
        val preferences = ProfileField.entries.associateWith { field ->
            if (schemaVersion < 3 && field in structuredNameFields) return@associateWith field.defaultVisibility()
            if (schemaVersion >= 4 && field in legacySocialFields) {
                val entry = socialProfiles.firstOrNull { it.legacyField == field }
                return@associateWith if (entry == null) field.defaultVisibility() else Visibility(
                    entry.showOnCard, entry.includeInContactExport,
                )
            }
            val item = settings[field.key].jsonObject("showOnCard", "includeInContactExport")
            val show = item["showOnCard"]
            val export = item["includeInContactExport"]
            require((show == null || show is Boolean) && (export == null || export is Boolean))
            Visibility(show as Boolean?, export as Boolean?)
        }
        val portrait = decodeImage(profile["profileImage"], ImageRole.Portrait)
        val cover = decodeImage(profile["coverImage"], ImageRole.Cover)
        val savedPhone = values[ProfileField.Phone].orEmpty()
        val phoneRegion = if (schemaVersion == 1) {
            PhoneNumbers.regionForNumber(savedPhone, PhoneNumbers.defaultRegion())
        } else {
            profile.string("phoneRegion")
        }
        require(PhoneNumbers.isSupportedRegion(phoneRegion))
        val parsedPhone = PhoneNumbers.parse(savedPhone, phoneRegion)
        val includeCountryCode = if (schemaVersion == 1) true else {
            profile["includeCountryCodeWhenSharing"] as? Boolean
                ?: throw IllegalArgumentException("Invalid country-code preference.")
        }
        val phoneNumberE164 = if (schemaVersion == 1) parsedPhone?.e164 else {
            val stored = profile["phoneNumberE164"]
            require(stored == null || stored is String)
            (stored as? String).also { require(it == parsedPhone?.e164) }
        }
        val projection = if (schemaVersion >= 4) {
            projectLegacySocialFields(values, preferences, socialProfiles)
        } else LegacySocialProjection(values.toMap(), preferences.toMap())
        val draft = ProfileDraft(
            projection.values, projection.visibility, portrait, cover, publicKey = publicKey,
            phoneRegion = phoneRegion,
            includeCountryCodeWhenSharing = includeCountryCode,
            phoneNumberE164 = phoneNumberE164,
            allowUnparsedLegacyPhone = savedPhone.isNotBlank() && parsedPhone == null,
            socialProfiles = socialProfiles,
        )
        val validation = ProfileValidator.validate(draft)
        val normalized = validation.normalized
        require(normalized != null && if (schemaVersion >= 4) normalized == draft else {
            normalized.values == draft.values && normalized.visibility == draft.visibility
        }) { "Saved fields are invalid or noncanonical." }
        val appearance = decodeAppearance(schemaVersion, data["appearance"])
        val metadata = data["metadata"].jsonObject("createdAt", "updatedAt")
        val created = timestamp(metadata.string("createdAt"))
        val updated = timestamp(metadata.string("updatedAt"))
        return AppData(
            schemaVersion = schemaVersion,
            profile = Profile(
                UUID.fromString(uuid(profile.string("profileId"))), projection.values, projection.visibility,
                publicKey = publicKey, profileImage = portrait, coverImage = cover,
                phoneRegion = phoneRegion,
                includeCountryCodeWhenSharing = includeCountryCode,
                phoneNumberE164 = phoneNumberE164,
                socialProfiles = socialProfiles,
            ),
            appearance = appearance,
            metadata = Metadata(created, updated)
        )
    }

    private fun encodeAppearance(schemaVersion: Int, appearance: Appearance): Map<String, Any?> {
        val encoded = linkedMapOf<String, Any?>("theme" to appearance.theme.name, "accent" to appearance.accent.name)
        if (schemaVersion < AppData.CARD_STYLE_SCHEMA_VERSION) {
            require(appearance.cardStyle == CardStyle.Classic && appearance.minimalItems.isEmpty()) {
                "Card styles require schema ${AppData.CARD_STYLE_SCHEMA_VERSION}."
            }
            return encoded
        }
        // Re-checked at write time in case a caller mutated the set after construction.
        require(appearance.minimalItems.size <= Appearance.MAX_MINIMAL_ITEMS)
        encoded["cardStyle"] = appearance.cardStyle.key
        encoded["minimalItems"] = MinimalItem.entries.filter { it in appearance.minimalItems }.map { it.key }
        return encoded
    }

    private fun decodeAppearance(schemaVersion: Int, value: Any?): Appearance {
        if (schemaVersion < AppData.CARD_STYLE_SCHEMA_VERSION) {
            val legacy = value.jsonObject("theme", "accent")
            // Explicit schema 4-and-earlier migration: no style data existed, so Classic with no Minimal items.
            return Appearance(
                theme = Theme.valueOf(legacy.string("theme")),
                accent = Accent.valueOf(legacy.string("accent")),
                cardStyle = CardStyle.Classic,
                minimalItems = emptySet(),
            )
        }
        val item = value.jsonObject("theme", "accent", "cardStyle", "minimalItems")
        val styleKey = item.string("cardStyle")
        val style = CardStyle.entries.firstOrNull { it.key == styleKey }
            ?: throw IllegalArgumentException("Invalid card style.")
        val rawItems = item["minimalItems"] as? List<*> ?: throw IllegalArgumentException("Invalid Minimal items.")
        require(rawItems.size <= Appearance.MAX_MINIMAL_ITEMS) { "Too many Minimal items." }
        val items = rawItems.map { raw ->
            val key = raw as? String ?: throw IllegalArgumentException("Invalid Minimal item.")
            MinimalItem.entries.firstOrNull { it.key == key } ?: throw IllegalArgumentException("Invalid Minimal item.")
        }
        require(items.distinct().size == items.size) { "Duplicate Minimal items." }
        require(items == MinimalItem.entries.filter { it in items }) { "Minimal items are noncanonical." }
        return Appearance(
            theme = Theme.valueOf(item.string("theme")),
            accent = Accent.valueOf(item.string("accent")),
            cardStyle = style,
            minimalItems = items.toSet(),
        )
    }

    private fun encodeSocialProfile(entry: SocialProfile): Map<String, Any?> = linkedMapOf(
        "id" to entry.id.toString(),
        "platform" to entry.platform,
        "label" to entry.label,
        "url" to entry.url,
        "showOnCard" to entry.showOnCard,
        "includeInContactExport" to entry.includeInContactExport,
        "legacyField" to entry.legacyField?.key,
    )

    private fun decodeSocialProfiles(value: Any?): List<SocialProfile> {
        val entries = value as? List<*> ?: throw IllegalArgumentException("Invalid social profiles.")
        return entries.map { raw ->
            val item = raw.jsonObject(
                "id", "platform", "label", "url", "showOnCard", "includeInContactExport", "legacyField",
            )
            val legacyKey = item["legacyField"]
            require(legacyKey == null || legacyKey is String)
            val legacyField = (legacyKey as? String)?.let { key ->
                legacySocialFields.firstOrNull { it.key == key }
                    ?: throw IllegalArgumentException("Invalid legacy social field.")
            }
            val showOnCard = item["showOnCard"] as? Boolean
                ?: throw IllegalArgumentException("Invalid social visibility.")
            val includeInContactExport = item["includeInContactExport"] as? Boolean
                ?: throw IllegalArgumentException("Invalid social export visibility.")
            SocialProfile(
                id = UUID.fromString(uuid(item.string("id"))),
                platform = item.string("platform"),
                label = item.string("label"),
                url = item.string("url"),
                showOnCard = showOnCard,
                includeInContactExport = includeInContactExport,
                legacyField = legacyField,
            )
        }
    }

    private fun encodeImage(asset: ImageAsset?): Map<String, Any?>? = asset?.let {
        linkedMapOf("assetId" to it.assetId.toString(), "mediaType" to it.mediaType,
            "byteLength" to it.byteLength, "width" to it.width, "height" to it.height,
            "sha256" to it.sha256, "canonicalizationPolicyVersion" to it.canonicalizationPolicyVersion)
    }

    private fun encodeKey(asset: PublicKeyAsset?): Map<String, Any?>? = asset?.let {
        linkedMapOf("assetId" to it.assetId.toString(), "format" to it.format,
            "keyVersion" to it.keyVersion, "primaryAlgorithm" to it.primaryAlgorithm,
            "mediaType" to it.mediaType, "byteLength" to it.byteLength,
            "sha256" to it.sha256, "fingerprint" to it.fingerprint)
    }

    private fun decodeKey(value: Any?): PublicKeyAsset? {
        if (value == null) return null
        val item = value.jsonObject("assetId", "format", "keyVersion", "primaryAlgorithm",
            "mediaType", "byteLength", "sha256", "fingerprint")
        fun integer(key: String) = item.long(key).also { require(it in 1..Int.MAX_VALUE.toLong()) }.toInt()
        return PublicKeyAsset(UUID.fromString(uuid(item.string("assetId"))), item.string("format"),
            integer("keyVersion"), integer("primaryAlgorithm"), item.string("mediaType"),
            item.long("byteLength"), item.string("sha256"), item.string("fingerprint"))
            .also { require(ProfileValidator.validPublicKeyDescriptor(it)) }
    }

    private fun decodeImage(value: Any?, role: ImageRole): ImageAsset? {
        if (value == null) return null
        val item = value.jsonObject("assetId", "mediaType", "byteLength", "width", "height",
            "sha256", "canonicalizationPolicyVersion")
        fun integer(key: String) = item.long(key).also { require(it in 1..Int.MAX_VALUE.toLong()) }.toInt()
        return ImageAsset(UUID.fromString(uuid(item.string("assetId"))), item.string("mediaType"),
            item.long("byteLength"), integer("width"), integer("height"), item.string("sha256"),
            integer("canonicalizationPolicyVersion")).also { require(ProfileValidator.validImageDescriptor(it, role)) }
    }

    private fun timestamp(value: String): String {
        require(value.length <= 40 && value.endsWith('Z') && Instant.parse(value).toString() == value)
        return value
    }
}

internal fun imageAssets(data: AppData): Map<ImageRole, ImageAsset> = buildMap {
    data.profile.profileImage?.let { put(ImageRole.Portrait, it) }
    data.profile.coverImage?.let { put(ImageRole.Cover, it) }
}

internal fun uuid(value: String): String {
    require(value.length == 36 && UUID.fromString(value).toString() == value)
    return value
}
