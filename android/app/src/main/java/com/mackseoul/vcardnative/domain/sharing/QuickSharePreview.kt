package com.mackseoul.vcardnative.domain.sharing

import com.mackseoul.vcardnative.domain.profile.Profile
import com.mackseoul.vcardnative.domain.profile.ProfileField

data class QuickSharePreviewRow(val field: ProfileField, val value: String)

/**
 * Read-only projection of the rows Custom Share will actually export. Values and order come from
 * [QuickShareContactExport.from]; nothing here formats, persists, or alters the export payload.
 */
sealed interface QuickSharePreview {
    data object MissingFullName : QuickSharePreview
    data object NoSelection : QuickSharePreview
    data class Ready(val fullName: String, val rows: List<QuickSharePreviewRow>) : QuickSharePreview

    /** The saved profile cannot currently be exported with this selection; the selection is left untouched. */
    data class Unavailable(val selectedFields: List<ProfileField>, val reason: String?) : QuickSharePreview

    companion object {
        fun from(profile: Profile, selection: Set<ProfileField>): QuickSharePreview {
            if (profile.values[ProfileField.FullName].isNullOrBlank()) return MissingFullName
            if (!QuickShareSelection.canContinue(profile.values, selection)) return NoSelection
            val selected = QuickShareSelection.selectableFields.filter { it in selection }
            val export = try {
                QuickShareContactExport.from(profile, selection)
            } catch (error: Exception) {
                return Unavailable(selected, error.message)
            }
            val fullName = export.rows
                .firstOrNull { it.fields == listOf(ProfileField.FullName) }
                ?.value
                ?.takeIf { it.isNotBlank() }
                ?: return Unavailable(selected, null)
            val rows = export.rows.mapNotNull { row ->
                val field = row.fields.singleOrNull()
                if (field != null && field in QuickShareSelection.selectableFields && field in selection &&
                    row.value.isNotBlank()
                ) {
                    QuickSharePreviewRow(field, row.value)
                } else {
                    null
                }
            }
            return Ready(fullName, rows)
        }
    }
}

data class QuickShareFieldChoice(
    val field: ProfileField,
    val value: String?,
    val selected: Boolean,
) {
    val available: Boolean get() = value != null
}

object QuickShareFieldChoices {
    fun from(values: Map<ProfileField, String>, selection: Set<ProfileField>): List<QuickShareFieldChoice> =
        QuickShareSelection.selectableFields.map { field ->
            val value = values[field]?.takeIf { it.isNotBlank() }
            QuickShareFieldChoice(field, value, selected = value != null && field in selection)
        }

    fun selectedCount(values: Map<ProfileField, String>, selection: Set<ProfileField>): Int =
        from(values, selection).count { it.selected }

    val optionalFieldCount: Int get() = QuickShareSelection.selectableFields.size
}
