package com.mackseoul.vcardnative.domain.sharing

import com.mackseoul.vcardnative.domain.profile.PhoneNumbers
import com.mackseoul.vcardnative.domain.profile.Profile
import com.mackseoul.vcardnative.domain.profile.ProfileDraft
import com.mackseoul.vcardnative.domain.profile.ProfileField
import com.mackseoul.vcardnative.domain.profile.ProfileValidator
import com.mackseoul.vcardnative.domain.profile.Visibility
import java.util.UUID

object QuickShareContactExport {
    fun from(profile: Profile, selection: Set<ProfileField>): ContactExport {
        val fullName = profile.values[ProfileField.FullName]
            ?.takeIf { it.isNotBlank() }
            ?: throw ContactExportException("A saved full name is required to share a contact.")
        val selectedValues = QuickShareSelection.selectedValues(profile.values, selection)
        if (selectedValues.isEmpty()) {
            throw ContactExportException("Select at least one populated additional field to share.")
        }

        val values = linkedMapOf(ProfileField.FullName to fullName).apply {
            putAll(selectedValues)
        }
        val visibility = ProfileDraft.empty().visibility.mapValues { (field, preference) ->
            if (field == ProfileField.FullName) preference
            else if (preference.includeInContactExport != null) {
                preference.copy(includeInContactExport = field in selectedValues)
            } else {
                preference
            }
        }
        val includesPhone = ProfileField.Phone in selectedValues
        val projected = Profile(
            profileId = UUID.randomUUID(),
            values = values,
            visibility = visibility,
            phoneRegion = if (includesPhone) profile.phoneRegion else PhoneNumbers.defaultRegion(),
            includeCountryCodeWhenSharing = includesPhone && profile.includeCountryCodeWhenSharing,
            phoneNumberE164 = profile.phoneNumberE164.takeIf { includesPhone },
        )

        return ContactExport.from(projected)
    }
}