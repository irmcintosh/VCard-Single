package com.mackseoul.vcardnative.platform.storage

import android.content.Context
import android.content.SharedPreferences
import com.mackseoul.vcardnative.data.sharing.QuickShareSelectionStore
import com.mackseoul.vcardnative.domain.sharing.QuickShareSelection

class SharedPreferencesQuickShareSelectionStore internal constructor(
    private val preferences: SharedPreferences,
) : QuickShareSelectionStore {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    override fun readConfirmedKeys(): Set<String> {
        val persistedKeys = preferences.getStringSet(SELECTION_KEY, null)?.toSet().orEmpty()
        return QuickShareSelection.selectableFields
            .asSequence()
            .map { it.key }
            .filter { it in persistedKeys }
            .toCollection(linkedSetOf())
    }

    override fun replaceConfirmedKeys(keys: Set<String>) {
        val requestedKeys = keys.toSet()
        val orderedKeys = QuickShareSelection.selectableFields
            .asSequence()
            .map { it.key }
            .filter { it in requestedKeys }
            .toCollection(linkedSetOf())
        preferences.edit()
            .putStringSet(SELECTION_KEY, LinkedHashSet(orderedKeys))
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "quick_share_preferences"
        const val SELECTION_KEY = "confirmed_additional_field_keys"
    }
}