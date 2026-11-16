package com.mackseoul.vcardnative.domain.sharing

import com.mackseoul.vcardnative.domain.profile.ProfileField

object QuickShareSelection {
    val selectableFields: List<ProfileField> = listOf(
        ProfileField.ProfessionalTitle,
        ProfileField.Organization,
        ProfileField.Phone,
        ProfileField.Email,
    )

    fun availableFields(values: Map<ProfileField, String>): List<ProfileField> =
        selectableFields.filter { values[it]?.isNotBlank() == true }

    fun restoreSelection(
        values: Map<ProfileField, String>,
        persistedKeys: Set<String>,
    ): Set<ProfileField> = selectableFields.filterTo(linkedSetOf()) { field ->
        field.key in persistedKeys && values[field]?.isNotBlank() == true
    }

    fun selectAll(values: Map<ProfileField, String>): Set<ProfileField> =
        availableFields(values).toCollection(linkedSetOf())

    fun clearAll(): Set<ProfileField> = emptySet()

    fun selectedValues(
        values: Map<ProfileField, String>,
        selection: Set<ProfileField>,
    ): Map<ProfileField, String> = linkedMapOf<ProfileField, String>().apply {
        selectableFields.forEach { field ->
            val savedValue = values[field]
            if (field in selection && savedValue != null && savedValue.isNotBlank()) {
                put(field, savedValue)
            }
        }
    }

    fun canContinue(
        values: Map<ProfileField, String>,
        selection: Set<ProfileField>,
    ): Boolean = selectableFields.any { field ->
        field in selection && values[field]?.isNotBlank() == true
    }
}