package com.mackseoul.vcardnative.ui.profile

import com.mackseoul.vcardnative.domain.profile.AppData
import com.mackseoul.vcardnative.domain.profile.ProfileField
import com.mackseoul.vcardnative.domain.profile.ProfileDraft
import com.mackseoul.vcardnative.domain.profile.ProfileValidator
import com.mackseoul.vcardnative.domain.profile.PhoneNumbers
import com.mackseoul.vcardnative.domain.profile.toDraft
import com.mackseoul.vcardnative.ui.screens.card.CardContactField
import com.mackseoul.vcardnative.ui.screens.card.CardProfessionalLink
import com.mackseoul.vcardnative.ui.screens.card.MyCardProfile
import com.mackseoul.vcardnative.ui.screens.card.MyCardUiState
import com.mackseoul.vcardnative.ui.screens.card.CardPublicKey
import com.mackseoul.vcardnative.domain.keys.PublicKeyStatus

fun AppData.toMyCard(keyStatus: PublicKeyStatus? = null): MyCardUiState {
    fun displayed(field: ProfileField): String? = value(field).takeIf {
        visibility(field).showOnCard == true
    }
    fun contact(field: ProfileField): CardContactField? = value(field)?.let { raw ->
        val normalized = ProfileValidator.validate(
            ProfileDraft.empty().copy(
                values = mapOf(ProfileField.FullName to requireNotNull(value(ProfileField.FullName)), field to raw),
                phoneRegion = profile.phoneRegion,
                phoneNumberE164 = profile.phoneNumberE164,
                allowUnparsedLegacyPhone = field == ProfileField.Phone &&
                    raw.isNotBlank() && profile.phoneNumberE164 == null,
            ),
        ).normalized?.values?.get(field)
        val phoneValid = field != ProfileField.Phone || profile.phoneNumberE164 != null ||
            PhoneNumbers.parse(raw, profile.phoneRegion) != null
        CardContactField(
            normalized ?: raw,
            displayEnabled = visibility(field).showOnCard == true,
            validated = normalized != null && phoneValid,
            actionValue = if (field == ProfileField.Phone) {
                profile.phoneNumberE164 ?: PhoneNumbers.parse(raw, profile.phoneRegion)?.e164 ?: raw
            } else {
                normalized ?: raw
            },
        )
    }
    return MyCardUiState(
        MyCardProfile(
            fullName = requireNotNull(value(ProfileField.FullName)),
            professionalTitle = displayed(ProfileField.ProfessionalTitle),
            organization = displayed(ProfileField.Organization),
            biography = displayed(ProfileField.Biography),
            location = displayed(ProfileField.Location),
            phone = contact(ProfileField.Phone),
            email = contact(ProfileField.Email),
            website = contact(ProfileField.Website),
            professionalLinks = toDraft().socialProfiles.map { entry ->
                CardProfessionalLink(entry.label, CardContactField(entry.url, entry.showOnCard,
                    ProfileValidator.browserUrlCandidate(entry.url, true) != null), entry.platform)
            },
            hostedProfileUrl = value(ProfileField.HostedProfileUrl)?.takeIf {
                ProfileValidator.browserUrlCandidate(it, true) != null
            },
            publicKey = profile.publicKey?.takeIf { visibility(ProfileField.PublicKey).showOnCard == true }?.let {
                CardPublicKey(true, true, it, keyStatus)
            },
        ),
    )
}
