package com.mackseoul.vcardnative.domain.sharing

import com.mackseoul.vcardnative.domain.profile.Profile
import com.mackseoul.vcardnative.domain.profile.ProfileDraft
import com.mackseoul.vcardnative.domain.profile.ProfileField
import com.mackseoul.vcardnative.domain.profile.ProfileValidator
import com.mackseoul.vcardnative.domain.profile.PhoneNumbers
import com.mackseoul.vcardnative.domain.profile.structuredNameFields
import com.mackseoul.vcardnative.domain.profile.migrateLegacySocialProfiles
import java.io.ByteArrayOutputStream

class ContactExportException(message: String) : Exception(message)

data class ContactExportRow(val fields: List<ProfileField>, val value: String, val label: String? = null)

class ContactExport private constructor(rows: List<ContactExportRow>, content: ByteArray) {
    val rows: List<ContactExportRow> = rows.map { it.copy(fields = it.fields.toList()) }
    private val content = content.copyOf()
    val bytes: ByteArray get() = content.copyOf()

    companion object {
        const val MAX_BYTES = 65_536
        const val CONTRACT_VERSION = 2
        const val FILE_NAME = "vcard-native-contact.vcf"
        const val MIME_TYPE = "text/vcard"

        fun from(profile: Profile): ContactExport {
            val savedPhone = profile.values[ProfileField.Phone].orEmpty()
            val socialProfiles = profile.socialProfiles.ifEmpty {
                migrateLegacySocialProfiles(profile.profileId, profile.values, profile.visibility)
            }
            val validation = ProfileValidator.validate(
                ProfileDraft(
                    profile.values, profile.visibility,
                    phoneRegion = profile.phoneRegion,
                    includeCountryCodeWhenSharing = profile.includeCountryCodeWhenSharing,
                    phoneNumberE164 = profile.phoneNumberE164,
                    allowUnparsedLegacyPhone = savedPhone.isNotBlank() && profile.phoneNumberE164 == null,
                    socialProfiles = socialProfiles,
                )
            )
            val normalized = validation.normalized
            if (normalized == null || normalized.values != profile.values ||
                normalized.visibility != profile.visibility ||
                normalized.socialProfiles != socialProfiles ||
                profile.phoneNumberE164 != null && normalized.phoneNumberE164 != profile.phoneNumberE164) {
                throw ContactExportException("Saved contact data is invalid. No contact file was created.")
            }
            val rows = mutableListOf<ContactExportRow>()
            val lines = mutableListOf("BEGIN:VCARD", "VERSION:3.0")
            fun included(field: ProfileField): String? =
                profile.values[field]?.takeIf { profile.visibility[field]?.includeInContactExport == true }
            fun text(field: ProfileField, property: String) {
                included(field)?.let {
                    rows += ContactExportRow(listOf(field), it)
                    lines += "$property:${escapeText(it)}"
                }
            }
            val names = structuredNameFields.associateWith { included(it).orEmpty() }
            if (names.values.any { it.isNotEmpty() }) {
                lines += "N:${escapeText(names.getValue(ProfileField.LastName))};" +
                    "${escapeText(names.getValue(ProfileField.FirstName))};" +
                    "${escapeText(names.getValue(ProfileField.MiddleName))};;"
                text(ProfileField.FullName, "FN")
                structuredNameFields.forEach { field ->
                    names.getValue(field).takeIf { it.isNotEmpty() }?.let {
                        rows += ContactExportRow(listOf(field), it)
                    }
                }
            } else {
                text(ProfileField.FullName, "FN")
                lines += "N:;;;;"
            }
            text(ProfileField.Organization, "ORG")
            text(ProfileField.ProfessionalTitle, "TITLE")
            included(ProfileField.Phone)?.let {
                val exportedPhone = PhoneNumbers.exportValue(
                    it, profile.phoneRegion, profile.includeCountryCodeWhenSharing, profile.phoneNumberE164,
                ).value
                rows += ContactExportRow(listOf(ProfileField.Phone), exportedPhone)
                lines += "TEL:$exportedPhone"
            }
            included(ProfileField.Email)?.let {
                rows += ContactExportRow(listOf(ProfileField.Email), it)
                lines += "EMAIL;TYPE=INTERNET:$it"
            }
            included(ProfileField.Website)?.let { value ->
                rows += ContactExportRow(listOf(ProfileField.Website), value)
                lines += "URL:$value"
            }
            socialProfiles.filter { it.includeInContactExport }.forEach { entry ->
                rows += ContactExportRow(
                    fields = listOf(entry.legacyField ?: ProfileField.OtherProfessional),
                    value = entry.url,
                    label = entry.label.takeUnless { entry.legacyField != null },
                )
                lines += "URL:${entry.url}"
            }
            val notes = mutableListOf<String>()
            listOf(ProfileField.Location to "Location", ProfileField.Biography to "Biography").forEach { (field, label) ->
                included(field)?.let {
                    rows += ContactExportRow(listOf(field), it)
                    notes += "$label: $it"
                }
            }
            if (notes.isNotEmpty()) lines += "NOTE:${escapeText(notes.joinToString("\n"))}"
            lines += "END:VCARD"
            return ContactExport(rows, encodeLines(lines))
        }

        internal fun escapeText(value: String): String = buildString {
            value.forEach {
                append(when (it) {
                    '\\' -> "\\\\"
                    ',' -> "\\,"
                    ';' -> "\\;"
                    '\n' -> "\\n"
                    else -> it.toString()
                })
            }
        }

        internal fun encodeLines(lines: List<String>): ByteArray {
            val output = ByteArrayOutputStream()
            fun write(bytes: ByteArray) {
                if (output.size().toLong() + bytes.size > MAX_BYTES) {
                    throw ContactExportException("The contact file exceeds 64 KiB. Edit export selections and review again.")
                }
                output.write(bytes)
            }
            lines.forEach { line ->
                var index = 0
                var physicalBytes = 0
                while (index < line.length) {
                    val length = when {
                        line[index] == '\\' && index + 1 < line.length -> 2
                        line[index] == '%' && index + 2 < line.length &&
                            line[index + 1].digitToIntOrNull(16) != null &&
                            line[index + 2].digitToIntOrNull(16) != null -> 3
                        else -> Character.charCount(line.codePointAt(index))
                    }
                    val token = line.substring(index, index + length).toByteArray(Charsets.UTF_8)
                    if (physicalBytes + token.size > 75) {
                        write("\r\n ".toByteArray(Charsets.US_ASCII))
                        physicalBytes = 1
                    }
                    write(token)
                    physicalBytes += token.size
                    index += length
                }
                write("\r\n".toByteArray(Charsets.US_ASCII))
            }
            return output.toByteArray()
        }
    }
}
