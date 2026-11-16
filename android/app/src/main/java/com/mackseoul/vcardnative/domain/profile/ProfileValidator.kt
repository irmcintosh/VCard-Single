package com.mackseoul.vcardnative.domain.profile

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.text.Normalizer
import java.util.Locale

fun interface HostValidator {
    /** Returns a validated lowercase ASCII domain, or throws IllegalArgumentException. Never uses DNS. */
    fun normalize(host: String): String
}

object AsciiHostValidator : HostValidator {
    override fun normalize(host: String): String {
        require(host.all { it.code < 128 }) { "Unicode hosts require IDNA validation." }
        val ascii = host.lowercase(Locale.ROOT)
        require(ascii.length in 1..253 && !ascii.endsWith('.'))
        val labels = ascii.split('.')
        require(labels.all {
            it.length in 1..63 && it.first().isAsciiAlphanumeric() && it.last().isAsciiAlphanumeric() &&
                it.all { c -> c.isAsciiAlphanumeric() || c == '-' } &&
                !(it.length >= 4 && it[2] == '-' && it[3] == '-') &&
                !it.startsWith("xn--")
        })
        if (ascii.all { it.isDigit() || it == '.' }) {
            require(labels.size == 4 && labels.all {
                it.length <= 3 && (it == "0" || !it.startsWith('0')) && it.toInt() <= 255
            })
        }
        return ascii
    }
}

private fun Char.isAsciiAlphanumeric() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

enum class SocialEntryField { Platform, Label, Url }

object ProfileValidator {
    @Volatile
    var hostValidator: HostValidator = AsciiHostValidator

    fun validate(draft: ProfileDraft): ValidationResult = validate(draft, hostValidator)

    fun browserUrlCandidate(raw: String, httpsOnly: Boolean): String? = try {
        normalizeUrl(validateRawText(raw, false), httpsOnly, hostValidator)
    } catch (_: IllegalArgumentException) {
        null
    }

    fun socialProfileFieldErrors(
        entry: SocialProfile, hosts: HostValidator = hostValidator,
    ): Map<SocialEntryField, String> {
        val errors = linkedMapOf<SocialEntryField, String>()
        fun check(field: SocialEntryField, action: () -> Unit) {
            try { action() } catch (error: IllegalArgumentException) {
                errors[field] = error.message ?: "Enter a supported value."
            }
        }
        check(SocialEntryField.Platform) {
            val platform = validateRawText(entry.platform, false)
            require(platform.isNotBlank()) { "Enter a platform name." }
            require(platform.codePointCount(0, platform.length) <= 160) {
                "Use no more than 160 characters for the platform name."
            }
        }
        check(SocialEntryField.Label) {
            val label = validateRawText(entry.label, false)
            require(label.isNotBlank()) { "Enter a label." }
            require(label.codePointCount(0, label.length) <= 160) {
                "Use no more than 160 characters for the label."
            }
        }
        check(SocialEntryField.Url) {
            val url = validateRawText(entry.url, false)
            require(url.isNotBlank()) { "Enter an HTTPS profile URL." }
            require(url.codePointCount(0, url.length) <= 2048) { "Use no more than 2048 characters for the URL." }
            normalizeUrl(url, true, hosts)
        }
        return errors.toMap()
    }

    fun validate(draft: ProfileDraft, hosts: HostValidator): ValidationResult {
        val errors = linkedMapOf<ProfileField, String>()
        val normalized = linkedMapOf<ProfileField, String>()
        val visibility = linkedMapOf<ProfileField, Visibility>()
        val source = draft.withLegacySocialCompatibility()
        var phoneNumberE164: String? = null
        if (!PhoneNumbers.isSupportedRegion(source.phoneRegion)) {
            errors[ProfileField.Phone] = "Choose a supported country or region."
        }
        val socialIds = mutableSetOf<java.util.UUID>()
        val legacySources = mutableSetOf<ProfileField>()
        source.socialProfiles.forEach { social ->
            val errorField = social.legacyField ?: ProfileField.OtherProfessional
            try {
                require(socialIds.add(social.id)) { "Social profile identifiers must be unique." }
                social.legacyField?.let { field ->
                    require(field in legacySocialFields && legacySources.add(field)) {
                        "Legacy social profile mapping is invalid."
                    }
                }
                socialProfileFieldErrors(social, hosts).values.firstOrNull()?.let {
                    throw IllegalArgumentException(it)
                }
            } catch (error: IllegalArgumentException) {
                errors.putIfAbsent(errorField, error.message ?: "Enter a supported social profile.")
            }
        }
        for (field in ProfileField.entries) {
            val preference = source.visibility[field] ?: field.defaultVisibility()
            val validPreference = when (field) {
                ProfileField.FullName -> preference == Visibility(true, true)
                ProfileField.FirstName, ProfileField.MiddleName, ProfileField.LastName ->
                    preference.showOnCard == null && preference.includeInContactExport != null
                ProfileField.HostedProfileUrl -> preference == Visibility(null, null)
                ProfileField.PublicKey, ProfileField.ProfileImage, ProfileField.CoverImage ->
                    preference.showOnCard != null && preference.includeInContactExport == null
                else -> preference.showOnCard != null && preference.includeInContactExport != null
            }
            if (!validPreference) errors[field] = "These visibility settings are not supported."
            visibility[field] = preference
            if (field !in textFields) {
                if (draft.values.containsKey(field)) errors[field] = "Asset acquisition is not available yet."
                continue
            }
            try {
                val raw = source.values[field].orEmpty()
                val urlField = field == ProfileField.Website || field == ProfileField.HostedProfileUrl ||
                    field in legacySocialFields
                val value = when {
                    urlField && raw.all { it.isWhitespace() || Character.isSpaceChar(it) } -> ""
                    urlField -> validateRawText(raw, false)
                    else -> normalizeText(raw, field == ProfileField.Biography)
                }
                if (value.isEmpty()) {
                    require(field != ProfileField.FullName) { "Enter a full name." }
                    continue
                }
                val limit = when (field) {
                    ProfileField.Biography -> 2048
                    ProfileField.Phone -> 32
                    ProfileField.Email -> 254
                    ProfileField.Website, ProfileField.LinkedIn, ProfileField.GitHub,
                    ProfileField.OtherProfessional, ProfileField.HostedProfileUrl -> 2048
                    ProfileField.FullName -> 602
                    ProfileField.FirstName, ProfileField.MiddleName, ProfileField.LastName -> 200
                    else -> 160
                }
                require(value.codePointCount(0, value.length) <= limit) { "Use no more than $limit characters." }
                normalized[field] = when (field) {
                    ProfileField.Phone -> {
                        val parsed = validatePhone(value, draft.phoneRegion, draft.allowUnparsedLegacyPhone)
                        phoneNumberE164 = parsed?.e164
                        value
                    }
                    ProfileField.Email -> normalizeEmail(value, hosts)
                    ProfileField.Website -> value.also { normalizeUrl(it, false, hosts) }
                    ProfileField.LinkedIn, ProfileField.GitHub, ProfileField.OtherProfessional,
                    ProfileField.HostedProfileUrl -> value.also { normalizeUrl(it, true, hosts) }
                    else -> value
                }
            } catch (e: IllegalArgumentException) {
                errors[field] = e.message ?: "Enter a supported value."
            }
        }
        for (role in ImageRole.entries) {
            val asset = if (role == ImageRole.Portrait) source.profileImage else source.coverImage
            val pending = source.pendingImages[role]
            val field = if (role == ImageRole.Portrait) ProfileField.ProfileImage else ProfileField.CoverImage
            if (asset != null && !validImageDescriptor(asset, role) || pending != null && pending.asset != asset)
                errors[field] = "Choose a supported image again."
        }
        if (source.publicKey != null && !validPublicKeyDescriptor(source.publicKey) ||
            source.pendingPublicKey != null && source.pendingPublicKey.asset != source.publicKey) {
            errors[ProfileField.PublicKey] = "Import a supported public key again."
        }
        return ValidationResult(
            if (errors.isEmpty()) source.copy(
                values = normalized.toMap(),
                visibility = visibility.toMap(),
                pendingImages = source.pendingImages.toMap(),
                socialProfiles = source.socialProfiles.toList(),
                phoneNumberE164 = phoneNumberE164,
            ) else null,
            errors.toMap()
        )
    }

    fun validImageDescriptor(asset: ImageAsset, role: ImageRole): Boolean =
        asset.mediaType in setOf("image/jpeg", "image/png") &&
            asset.byteLength in 1..8_388_608L &&
            asset.width in 1..role.maximumEdge && asset.height in 1..role.maximumEdge &&
            asset.sha256.matches(Regex("[0-9a-f]{64}")) && asset.canonicalizationPolicyVersion == 1

    fun validPublicKeyDescriptor(asset: PublicKeyAsset): Boolean =
        asset.format == "openpgp-public-armored" && asset.mediaType == "application/pgp-keys" &&
            asset.byteLength in 1..65_536L &&
            when (asset.keyVersion) {
                4 -> asset.primaryAlgorithm in setOf(1, 22) && asset.fingerprint.matches(Regex("[0-9A-F]{40}"))
                6 -> asset.primaryAlgorithm in setOf(1, 27) && asset.fingerprint.matches(Regex("[0-9A-F]{64}"))
                else -> false
            } && asset.sha256.matches(Regex("[0-9a-f]{64}"))

    private fun normalizeText(raw: String, multiline: Boolean): String {
        val validated = validateRawText(raw, multiline)
        val lines = if (multiline) validated.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ') else validated
        return Normalizer.normalize(lines, Normalizer.Form.NFC)
            .trim { it.isWhitespace() || Character.isSpaceChar(it) }
    }

    private fun validateRawText(raw: String, multiline: Boolean): String {
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c.isHighSurrogate()) {
                require(i + 1 < raw.length && raw[i + 1].isLowSurrogate()) { "Text contains invalid Unicode." }
                i += 2
                continue
            }
            require(!c.isLowSurrogate()) { "Text contains invalid Unicode." }
            require(c.code !in 0x202A..0x202E && c.code !in 0x2066..0x2069) { "Remove directional formatting controls." }
            require(c.code != 127 && (c.code >= 32 || multiline && c in "\r\n\t")) {
                "Remove control characters."
            }
            i++
        }
        return raw
    }

    fun dialValue(display: String): String = display.filter { it in '0'..'9' || it == '+' }

    private fun validatePhone(value: String, region: String, allowUnparsedLegacy: Boolean): ParsedPhoneNumber? {
        require(value.all { it in '0'..'9' || it in " +-.()" } &&
            value.drop(1).none { it == '+' }) {
            "Use digits and phone punctuation only; extensions are unsupported."
        }
        return PhoneNumbers.parse(value, region)
            ?: if (allowUnparsedLegacy) null
            else throw IllegalArgumentException("Enter a valid phone number for the selected country or region.")
    }

    private fun normalizeEmail(value: String, hosts: HostValidator): String {
        require(value.count { it == '@' } == 1) { "Enter one email address." }
        val (local, host) = value.split('@')
        val atom = "!#$%&'*+-/=?^_`{|}~"
        require(local.length in 1..64 && local.split('.').all { part ->
            part.isNotEmpty() && part.all { it.isAsciiAlphanumeric() || it in atom }
        }) { "Use an ASCII email local part without quotes or spaces." }
        val normalizedHost = try { hosts.normalize(host) } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Enter a valid email domain.")
        }
        require(!normalizedHost.all { it.isDigit() || it == '.' }) { "Use a domain, not an IP address." }
        val result = "$local@$normalizedHost"
        require(result.length <= 254 && result.all { it.code < 128 }) { "Email exceeds 254 ASCII characters." }
        return result
    }

    private fun normalizeUrl(value: String, httpsOnly: Boolean, hosts: HostValidator): String {
        fun invalid(): Nothing = throw IllegalArgumentException(
            if (httpsOnly) "Enter an explicit HTTPS URL with a valid host." else "Enter an explicit HTTP or HTTPS URL with a valid host."
        )
        if (value.any { it.isWhitespace() || Character.isSpaceChar(it) || it.code < 32 || it.code == 127 || it == '\\' }) invalid()
        var p = 0
        while (p < value.length) {
            if (value[p] == '%') {
                if (p + 2 >= value.length || value[p + 1].digitToIntOrNull(16) == null ||
                    value[p + 2].digitToIntOrNull(16) == null) invalid()
                p += 3
            } else p++
        }
        val delimiter = value.indexOf("://")
        if (delimiter < 1) invalid()
        val scheme = value.substring(0, delimiter).lowercase(Locale.ROOT)
        if (scheme != "https" && (httpsOnly || scheme != "http")) invalid()
        val start = delimiter + 3
        val end = value.indexOfAny(charArrayOf('/', '?', '#'), start).let { if (it < 0) value.length else it }
        val authority = value.substring(start, end)
        if (authority.isEmpty() || '@' in authority || '%' in authority) invalid()
        val host: String
        val port: String
        if (authority.startsWith('[')) {
            val closing = authority.indexOf(']')
            if (closing < 0) invalid()
            val literal = authority.substring(1, closing)
            if (literal.isEmpty() || literal.any { it !in "0123456789abcdefABCDEF:." } || ':' !in literal) invalid()
            val parsed = try { InetAddress.getByName(literal) } catch (_: Exception) { invalid() }
            if (parsed !is Inet6Address && !literal.lowercase(Locale.ROOT).startsWith("::ffff:")) invalid()
            host = "[${literal.lowercase(Locale.ROOT)}]"
            port = authority.substring(closing + 1)
        } else {
            if (authority.count { it == ':' } > 1) invalid()
            val colon = authority.indexOf(':')
            val rawHost = if (colon < 0) authority else authority.substring(0, colon)
            host = try { hosts.normalize(rawHost) } catch (_: IllegalArgumentException) { invalid() }
            port = if (colon < 0) "" else authority.substring(colon)
        }
        if (port.isNotEmpty()) {
            if (!port.startsWith(':') || port.length == 1 ||
                port.drop(1).any { it !in '0'..'9' } || port.drop(1).toIntOrNull() !in 1..65535) invalid()
        }
        val suffix = buildString {
            val tail = value.substring(end)
            var i = 0
            while (i < tail.length) {
                val cp = tail.codePointAt(i)
                if (cp < 128) append(cp.toChar()) else {
                    String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).forEach { byte ->
                        append('%').append("%02X".format(Locale.ROOT, byte.toInt() and 255))
                    }
                }
                i += Character.charCount(cp)
            }
        }
        val result = "$scheme://$host$port$suffix"
        try {
            val uri = URI(result)
            if (!uri.isAbsolute || uri.rawAuthority == null) invalid()
        } catch (_: Exception) { invalid() }
        require(result.toByteArray(Charsets.UTF_8).size <= 2048) { "URL exceeds 2,048 serialized bytes." }
        return result
    }
}
