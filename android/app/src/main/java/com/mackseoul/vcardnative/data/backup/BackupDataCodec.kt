package com.mackseoul.vcardnative.data.backup

import com.mackseoul.vcardnative.data.profile.ProfileCodec
import com.mackseoul.vcardnative.data.profile.StrictJson
import com.mackseoul.vcardnative.data.profile.long
import com.mackseoul.vcardnative.data.profile.jsonObject
import com.mackseoul.vcardnative.domain.profile.AppData
import com.mackseoul.vcardnative.domain.profile.ProfileField
import com.mackseoul.vcardnative.domain.profile.textFields
import com.mackseoul.vcardnative.domain.profile.structuredNameFields
import com.mackseoul.vcardnative.domain.profile.legacySocialFields

/** Backup schema is flat, unlike the private A2 store's fields envelope. */
internal object BackupDataCodec {
    private val links = listOf(ProfileField.LinkedIn, ProfileField.GitHub, ProfileField.OtherProfessional)
    private val flat = textFields.filter { it !in links }
    private val legacyProfileKeys = listOf("profileId") + flat.filter { it !in structuredNameFields }.map { it.key } +
        listOf("professionalLinks", "publicKey", "profileImage", "coverImage", "visibility")
    private val currentProfileKeys = legacyProfileKeys +
        listOf("phoneRegion", "includeCountryCodeWhenSharing", "phoneNumberE164")
    private val structuredProfileKeys = currentProfileKeys +
        structuredNameFields.map { it.key }
    private val collectionProfileKeys = currentProfileKeys.filter { it != "professionalLinks" } +
        structuredNameFields.map { it.key } + "socialProfiles"

    private fun slot(field: ProfileField): String =
        if (field in links) "professionalLinks.${field.key}" else field.key

    fun encode(data: AppData): ByteArray {
        require(data.profile.values.keys.all { it in textFields })
        require(data.profile.visibility.keys == ProfileField.entries.toSet())
        val current = ProfileCodec.currentSchemaData(data)
        val internal = ProfileCodec.encodeData(current)
        val profile = internal["profile"].jsonObject(
            "profileId", "fields", "visibility", "publicKey", "profileImage", "coverImage",
            "phoneRegion", "includeCountryCodeWhenSharing", "phoneNumberE164", "socialProfiles")
        val fields = profile["fields"].jsonObject(*ProfileCodec.textFieldsForSchema(current.schemaVersion)
            .map { it.key }.toTypedArray())
        val schemaFields = ProfileCodec.fieldsForSchema(current.schemaVersion)
        val settings = profile["visibility"].jsonObject(*schemaFields.map { it.key }.toTypedArray())
        val mapped = linkedMapOf<String, Any?>("profileId" to profile["profileId"])
        flat.filter { it != ProfileField.HostedProfileUrl && it !in structuredNameFields }
            .forEach { mapped[it.key] = fields[it.key] }
        structuredNameFields.forEach { mapped[it.key] = fields[it.key] }
        mapped["hostedProfileUrl"] = fields["hostedProfileUrl"]
        mapped["socialProfiles"] = profile["socialProfiles"]
        listOf("publicKey", "profileImage", "coverImage").forEach { mapped[it] = profile[it] }
        mapped["visibility"] = schemaFields.associate { slot(it) to settings[it.key] }
        mapped["phoneRegion"] = profile["phoneRegion"]
        mapped["includeCountryCodeWhenSharing"] = profile["includeCountryCodeWhenSharing"]
        mapped["phoneNumberE164"] = profile["phoneNumberE164"]
        val encoded = StrictJson.encode(internal + ("profile" to mapped))
        require(decode(encoded) == current) { "Saved data is invalid or noncanonical." }
        return encoded
    }

    fun decode(bytes: ByteArray): AppData {
        val root = StrictJson.decode(bytes, maxDepth = 16, allowArrays = true).jsonObject(
            "schemaVersion", "profile", "appearance", "metadata")
        val schemaVersion = root.long("schemaVersion").also {
            require(it in 1L..AppData.CURRENT_SCHEMA_VERSION.toLong())
        }.toInt()
        val profile = root["profile"].jsonObject(
            *(when (schemaVersion) {
                1 -> legacyProfileKeys
                2 -> currentProfileKeys
                3 -> structuredProfileKeys
                else -> collectionProfileKeys
            }).toTypedArray()
        )
        val professional = if (schemaVersion <= 3) {
            profile["professionalLinks"].jsonObject(*links.map { it.key }.toTypedArray())
        } else emptyMap()
        val schemaFields = ProfileCodec.fieldsForSchema(schemaVersion)
        val settings = profile["visibility"].jsonObject(*schemaFields.map { slot(it) }.toTypedArray())
        val fields = ProfileCodec.textFieldsForSchema(schemaVersion).associate {
            it.key to if (it in links) professional[it.key] else profile[it.key]
        }
        val internal = linkedMapOf(
            "profileId" to profile["profileId"],
            "fields" to fields,
            "visibility" to schemaFields.associate { it.key to settings[slot(it)] },
            "publicKey" to profile["publicKey"],
            "profileImage" to profile["profileImage"],
            "coverImage" to profile["coverImage"],
        )
        if (schemaVersion >= 4) internal["socialProfiles"] = profile["socialProfiles"]
        if (schemaVersion >= 2) {
            internal["phoneRegion"] = profile["phoneRegion"]
            internal["includeCountryCodeWhenSharing"] = profile["includeCountryCodeWhenSharing"]
            internal["phoneNumberE164"] = profile["phoneNumberE164"]
        }
        return ProfileCodec.decodeData(root + ("profile" to internal))
    }
}
