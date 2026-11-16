package com.mackseoul.vcardnative.domain.profile

import java.util.Collections
import java.util.EnumSet
import java.util.UUID
import com.mackseoul.vcardnative.domain.keys.DraftPublicKey

enum class ProfileField(val key: String, val label: String, val group: String) {
    FullName("fullName", "Full name", "Identity"),
    FirstName("firstName", "First name", "Identity"),
    MiddleName("middleName", "Middle name", "Identity"),
    LastName("lastName", "Last name", "Identity"),
    ProfessionalTitle("professionalTitle", "Professional title", "Identity"),
    Organization("organization", "Organization", "Identity"),
    Biography("biography", "Biography", "Identity"),
    Location("location", "Location", "Identity"),
    Phone("phone", "Phone", "Contact"),
    Email("email", "Email", "Contact"),
    Website("website", "Website", "Contact"),
    LinkedIn("linkedin", "LinkedIn", "Professional links"),
    GitHub("github", "GitHub", "Professional links"),
    OtherProfessional("otherProfessional", "Other professional link", "Professional links"),
    HostedProfileUrl("hostedProfileUrl", "Hosted profile URL", "Web sharing"),
    PublicKey("publicKey", "Public key", "Assets"),
    ProfileImage("profileImage", "Profile image", "Assets"),
    CoverImage("coverImage", "Cover image", "Assets");
}

val structuredNameFields = listOf(ProfileField.FirstName, ProfileField.MiddleName, ProfileField.LastName)
val legacySocialFields = listOf(ProfileField.LinkedIn, ProfileField.GitHub, ProfileField.OtherProfessional)
val textFields: List<ProfileField> = ProfileField.entries.filter {
    it !in setOf(ProfileField.PublicKey, ProfileField.ProfileImage, ProfileField.CoverImage)
}

data class Visibility(val showOnCard: Boolean?, val includeInContactExport: Boolean?)

data class SocialProfile(
    val id: UUID,
    val platform: String,
    val label: String,
    val url: String,
    val showOnCard: Boolean,
    val includeInContactExport: Boolean,
    val legacyField: ProfileField? = null,
)

data class LegacySocialProjection(
    val values: Map<ProfileField, String>,
    val visibility: Map<ProfileField, Visibility>,
)

fun migrateLegacySocialProfiles(
    profileId: UUID,
    values: Map<ProfileField, String>,
    visibility: Map<ProfileField, Visibility>,
): List<SocialProfile> = legacySocialFields.mapNotNull { field ->
    val url = values[field]?.takeIf(String::isNotBlank) ?: return@mapNotNull null
    val (platform, label) = when (field) {
        ProfileField.LinkedIn -> "linkedin" to "LinkedIn"
        ProfileField.GitHub -> "github" to "GitHub"
        ProfileField.OtherProfessional -> "otherProfessional" to "Other professional link"
        else -> error("Not a legacy social field.")
    }
    val preference = visibility[field] ?: field.defaultVisibility()
    SocialProfile(
        id = UUID.nameUUIDFromBytes("vcard-legacy:${profileId}:${field.key}".toByteArray(Charsets.UTF_8)),
        platform = platform,
        label = label,
        url = url,
        showOnCard = preference.showOnCard == true,
        includeInContactExport = preference.includeInContactExport == true,
        legacyField = field,
    )
}

fun projectLegacySocialFields(
    values: Map<ProfileField, String>,
    visibility: Map<ProfileField, Visibility>,
    socialProfiles: List<SocialProfile>,
): LegacySocialProjection {
    val projectedValues = values.toMutableMap()
    val projectedVisibility = visibility.toMutableMap()
    legacySocialFields.forEach { field ->
        val legacyEntry = socialProfiles.firstOrNull { it.legacyField == field }
        if (legacyEntry == null) {
            projectedValues.remove(field)
            projectedVisibility.putIfAbsent(field, field.defaultVisibility())
        } else {
            projectedValues[field] = legacyEntry.url
            projectedVisibility[field] = Visibility(
                legacyEntry.showOnCard,
                legacyEntry.includeInContactExport,
            )
        }
    }
    return LegacySocialProjection(projectedValues.toMap(), projectedVisibility.toMap())
}

private fun legacySocialDetails(field: ProfileField): Pair<String, String> = when (field) {
    ProfileField.LinkedIn -> "linkedin" to "LinkedIn"
    ProfileField.GitHub -> "github" to "GitHub"
    ProfileField.OtherProfessional -> "otherProfessional" to "Other professional link"
    else -> throw IllegalArgumentException("Not a legacy social field.")
}

fun ProfileDraft.withLegacySocialCompatibility(): ProfileDraft {
    val profiles = socialProfiles.ifEmpty {
        legacySocialFields.mapNotNull { field ->
            val url = values[field]?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val (platform, label) = legacySocialDetails(field)
            val preference = visibility[field] ?: field.defaultVisibility()
            SocialProfile(
                id = UUID.nameUUIDFromBytes("vcard-draft:${field.key}:$url".toByteArray(Charsets.UTF_8)),
                platform = platform, label = label, url = url,
                showOnCard = preference.showOnCard == true,
                includeInContactExport = preference.includeInContactExport == true,
                legacyField = field,
            )
        }
    }
    val projection = projectLegacySocialFields(values, visibility, profiles)
    return copy(values = projection.values, visibility = projection.visibility, socialProfiles = profiles)
}

fun ProfileDraft.updateLegacySocialValue(field: ProfileField, value: String): ProfileDraft {
    if (field !in legacySocialFields) return copy(values = values + (field to value))
    val current = withLegacySocialCompatibility()
    val profiles = current.socialProfiles.toMutableList()
    val index = profiles.indexOfFirst { it.legacyField == field }
    val blank = value.all { it.isWhitespace() || Character.isSpaceChar(it) }
    if (blank) {
        if (index >= 0) profiles.removeAt(index)
    } else if (index >= 0) {
        profiles[index] = profiles[index].copy(url = value)
    } else {
        val (platform, label) = legacySocialDetails(field)
        val preference = current.visibility[field] ?: field.defaultVisibility()
        profiles += SocialProfile(
            id = UUID.randomUUID(), platform = platform, label = label, url = value,
            showOnCard = preference.showOnCard == true,
            includeInContactExport = preference.includeInContactExport == true,
            legacyField = field,
        )
    }
    val projection = projectLegacySocialFields(current.values, current.visibility, profiles)
    return current.copy(
        values = projection.values,
        visibility = projection.visibility,
        socialProfiles = profiles.toList(),
    )
}

fun ProfileDraft.updateLegacySocialVisibility(field: ProfileField, value: Visibility): ProfileDraft {
    if (field !in legacySocialFields) return copy(visibility = visibility + (field to value))
    val current = withLegacySocialCompatibility()
    val profiles = current.socialProfiles.map { entry ->
        if (entry.legacyField == field) entry.copy(
            showOnCard = value.showOnCard == true,
            includeInContactExport = value.includeInContactExport == true,
        ) else entry
    }
    val projected = projectLegacySocialFields(
        current.values,
        current.visibility + (field to value),
        profiles,
    )
    val visibility = if (profiles.none { it.legacyField == field }) {
        projected.visibility + (field to value)
    } else projected.visibility
    return current.copy(visibility = visibility, socialProfiles = profiles)
}

fun ProfileDraft.withSocialProfiles(entries: List<SocialProfile>): ProfileDraft {
    val projection = projectLegacySocialFields(values, visibility, entries)
    return copy(values = projection.values, visibility = projection.visibility, socialProfiles = entries.toList())
}

fun ProfileDraft.moveSocialProfile(id: UUID, offset: Int): ProfileDraft {
    val index = socialProfiles.indexOfFirst { it.id == id }
    val target = index + offset
    if (index < 0 || target !in socialProfiles.indices) return this
    val entries = socialProfiles.toMutableList()
    val entry = entries.removeAt(index)
    entries.add(target, entry)
    return withSocialProfiles(entries)
}

fun ProfileField.defaultVisibility(): Visibility = when (this) {
    ProfileField.FullName -> Visibility(true, true)
    ProfileField.FirstName, ProfileField.MiddleName, ProfileField.LastName -> Visibility(null, false)
    ProfileField.HostedProfileUrl -> Visibility(null, null)
    ProfileField.PublicKey -> Visibility(false, null)
    ProfileField.ProfileImage, ProfileField.CoverImage -> Visibility(true, null)
    else -> Visibility(true, false)
}

data class ProfileDraft(
    val values: Map<ProfileField, String>,
    val visibility: Map<ProfileField, Visibility>,
    val profileImage: ImageAsset? = null,
    val coverImage: ImageAsset? = null,
    val pendingImages: Map<ImageRole, DraftImage> = emptyMap(),
    val appearance: Appearance = Appearance(),
    val publicKey: PublicKeyAsset? = null,
    val pendingPublicKey: DraftPublicKey? = null,
    val phoneRegion: String = PhoneNumbers.defaultRegion(),
    val includeCountryCodeWhenSharing: Boolean = true,
    val phoneNumberE164: String? = null,
    val allowUnparsedLegacyPhone: Boolean = false,
    val socialProfiles: List<SocialProfile> = emptyList(),
) {
    companion object {
        fun empty() = ProfileDraft(emptyMap(), ProfileField.entries.associateWith { it.defaultVisibility() })
    }
}

enum class ImageRole(val maximumEdge: Int) { Portrait(1024), Cover(2048) }

class DraftImage(val asset: ImageAsset, bytes: ByteArray) {
    private val content = bytes.copyOf()
    val bytes: ByteArray get() = content.copyOf()
    override fun equals(other: Any?): Boolean =
        other is DraftImage && asset == other.asset && content.contentEquals(other.content)
    override fun hashCode(): Int = 31 * asset.hashCode() + content.contentHashCode()
}

data class ImageAsset(
    val assetId: UUID,
    val mediaType: String,
    val byteLength: Long,
    val width: Int,
    val height: Int,
    val sha256: String,
    val canonicalizationPolicyVersion: Int = 1
)

data class PublicKeyAsset(
    val assetId: UUID,
    val format: String,
    val keyVersion: Int,
    val primaryAlgorithm: Int,
    val mediaType: String,
    val byteLength: Long,
    val sha256: String,
    val fingerprint: String
)

data class Profile(
    val profileId: UUID,
    val values: Map<ProfileField, String>,
    val visibility: Map<ProfileField, Visibility>,
    val publicKey: PublicKeyAsset? = null,
    val profileImage: ImageAsset? = null,
    val coverImage: ImageAsset? = null,
    val phoneRegion: String = PhoneNumbers.defaultRegion(),
    val includeCountryCodeWhenSharing: Boolean = true,
    val phoneNumberE164: String? = null,
    val socialProfiles: List<SocialProfile> = emptyList(),
)

enum class Theme { dark, light }
enum class Accent { sapphire, jade, amethyst }

/** [key] is the persisted identifier; it must never change, even if the Kotlin name does. */
enum class CardStyle(val key: String) {
    Classic("classic"),
    Minimal("minimal"),
    Professional("professional"),
    Modern("modern"),
    Executive("executive"),
    Editorial("editorial"),
    Horizon("horizon"),
}

/**
 * Optional items the Minimal style may show beside the always-shown Full Name.
 * [key] is the persisted identifier; declaration order is the canonical persisted order.
 */
enum class MinimalItem(val key: String) {
    ProfilePhoto("profilePhoto"),
    CoverImage("coverImage"),
    ProfessionalTitle("professionalTitle"),
    Organization("organization"),
    Biography("biography"),
    Phone("phone"),
    Email("email"),
    Location("location"),
    Website("website"),
    ProfessionalLinks("professionalLinks"),
    HostedProfile("hostedProfile"),
}

/**
 * Value type with data-class semantics. Not a `data class` so that [minimalItems] can be stored as an
 * unmodifiable defensive copy; a caller mutating its own set afterwards cannot bypass the limit.
 */
class Appearance(
    val theme: Theme = Theme.dark,
    val accent: Accent = Accent.sapphire,
    val cardStyle: CardStyle = CardStyle.Classic,
    // Independent of cardStyle so selections survive switching styles.
    minimalItems: Set<MinimalItem> = emptySet(),
) {
    val minimalItems: Set<MinimalItem> = Collections.unmodifiableSet(
        EnumSet.noneOf(MinimalItem::class.java).apply { addAll(minimalItems) }
    )

    init {
        require(this.minimalItems.size <= MAX_MINIMAL_ITEMS) { "Minimal allows at most $MAX_MINIMAL_ITEMS items." }
    }

    fun copy(
        theme: Theme = this.theme,
        accent: Accent = this.accent,
        cardStyle: CardStyle = this.cardStyle,
        minimalItems: Set<MinimalItem> = this.minimalItems,
    ): Appearance = Appearance(theme, accent, cardStyle, minimalItems)

    override fun equals(other: Any?): Boolean = this === other || other is Appearance &&
        theme == other.theme && accent == other.accent && cardStyle == other.cardStyle &&
        minimalItems == other.minimalItems

    override fun hashCode(): Int = listOf(theme, accent, cardStyle, minimalItems).hashCode()

    override fun toString(): String =
        "Appearance(theme=$theme, accent=$accent, cardStyle=$cardStyle, minimalItems=$minimalItems)"

    companion object {
        const val MAX_MINIMAL_ITEMS = 4
    }
}
data class Metadata(val createdAt: String, val updatedAt: String)
data class AppData(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val profile: Profile,
    val appearance: Appearance = Appearance(),
    val metadata: Metadata
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 5
        const val CARD_STYLE_SCHEMA_VERSION = 5
    }

    fun value(field: ProfileField): String? = profile.values[field]
    fun visibility(field: ProfileField): Visibility =
        requireNotNull(profile.visibility[field]) { "Incomplete visibility." }
}

fun AppData.toDraft(): ProfileDraft {
    val socialProfiles = profile.socialProfiles.ifEmpty {
        migrateLegacySocialProfiles(profile.profileId, profile.values, profile.visibility)
    }
    val projected = projectLegacySocialFields(profile.values, profile.visibility, socialProfiles)
    return ProfileDraft(
        values = projected.values,
        visibility = projected.visibility,
        profileImage = profile.profileImage,
        coverImage = profile.coverImage,
        appearance = appearance,
        publicKey = profile.publicKey,
        phoneRegion = profile.phoneRegion,
        includeCountryCodeWhenSharing = profile.includeCountryCodeWhenSharing,
        phoneNumberE164 = profile.phoneNumberE164,
        allowUnparsedLegacyPhone = profile.values[ProfileField.Phone].orEmpty().isNotBlank() &&
            profile.phoneNumberE164 == null,
        socialProfiles = socialProfiles,
    )
}

data class ValidationResult(
    val normalized: ProfileDraft?,
    val errors: Map<ProfileField, String>
)
