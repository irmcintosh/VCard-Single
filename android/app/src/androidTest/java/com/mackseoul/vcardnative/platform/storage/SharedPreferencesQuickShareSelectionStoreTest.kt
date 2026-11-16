package com.mackseoul.vcardnative.platform.storage

import android.content.Context
import android.content.SharedPreferences
import android.test.InstrumentationTestCase
import com.mackseoul.vcardnative.domain.profile.Profile
import com.mackseoul.vcardnative.domain.profile.ProfileDraft
import com.mackseoul.vcardnative.domain.profile.ProfileField
import com.mackseoul.vcardnative.domain.profile.Visibility
import com.mackseoul.vcardnative.domain.sharing.QuickShareSelection
import java.util.UUID

@Suppress("DEPRECATION")
class SharedPreferencesQuickShareSelectionStoreTest : InstrumentationTestCase() {
    private lateinit var preferences: SharedPreferences
    private lateinit var store: SharedPreferencesQuickShareSelectionStore

    override fun setUp() {
        super.setUp()
        preferences = instrumentation.targetContext.getSharedPreferences(TEST_PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        store = SharedPreferencesQuickShareSelectionStore(preferences)
    }

    override fun tearDown() {
        preferences.edit().clear().commit()
        super.tearDown()
    }

    fun testFirstUseReturnsEmptyAndConfirmedKeysPersistAcrossStoreInstances() {
        assertTrue(store.readConfirmedKeys().isEmpty())

        store.replaceConfirmedKeys(setOf(ProfileField.ProfessionalTitle.key, ProfileField.Phone.key))

        assertEquals(
            setOf(ProfileField.ProfessionalTitle.key, ProfileField.Phone.key),
            SharedPreferencesQuickShareSelectionStore(preferences).readConfirmedKeys(),
        )
    }

    fun testReplacementAndEmptySelectionRemovePreviouslySavedKeys() {
        store.replaceConfirmedKeys(setOf(ProfileField.ProfessionalTitle.key, ProfileField.Phone.key))

        store.replaceConfirmedKeys(setOf(ProfileField.Email.key))
        assertEquals(setOf(ProfileField.Email.key), store.readConfirmedKeys())

        store.replaceConfirmedKeys(emptySet())
        assertTrue(store.readConfirmedKeys().isEmpty())
    }

    fun testUnknownAndFullNameKeysAreIgnoredOnReadAndNotWritten() {
        preferences.edit().putStringSet(
            TEST_SELECTION_KEY,
            setOf(
                ProfileField.ProfessionalTitle.key,
                ProfileField.FullName.key,
                ProfileField.Website.key,
                "unsupported",
            ),
        ).commit()

        assertEquals(setOf(ProfileField.ProfessionalTitle.key), store.readConfirmedKeys())

        store.replaceConfirmedKeys(
            setOf(ProfileField.Email.key, ProfileField.FullName.key, ProfileField.Website.key, "unsupported"),
        )

        assertEquals(
            setOf(ProfileField.Email.key),
            preferences.getStringSet(TEST_SELECTION_KEY, emptySet()),
        )
    }

    fun testMutatingReturnedSetCannotModifyPersistedSelection() {
        store.replaceConfirmedKeys(setOf(ProfileField.ProfessionalTitle.key))
        val returned = store.readConfirmedKeys().toMutableSet()

        returned.clear()

        assertEquals(setOf(ProfileField.ProfessionalTitle.key), store.readConfirmedKeys())
    }

    fun testPreviouslySavedWebsiteKeyIsIgnoredAndNeverWrittenAgain() {
        preferences.edit().putStringSet(
            TEST_SELECTION_KEY,
            setOf(ProfileField.Website.key, ProfileField.ProfessionalTitle.key),
        ).commit()

        assertEquals(setOf(ProfileField.ProfessionalTitle.key), store.readConfirmedKeys())

        store.replaceConfirmedKeys(setOf(ProfileField.Website.key, ProfileField.Organization.key))

        assertEquals(setOf(ProfileField.Organization.key), store.readConfirmedKeys())
        assertEquals(
            setOf(ProfileField.Organization.key),
            preferences.getStringSet(TEST_SELECTION_KEY, emptySet()),
        )
    }

    fun testRestoreFiltersAgainstCurrentValuesWithoutAutoSelectingNewFields() {
        store.replaceConfirmedKeys(setOf(ProfileField.ProfessionalTitle.key, ProfileField.Email.key))
        val values = mapOf(
            ProfileField.ProfessionalTitle to "Engineer",
            ProfileField.Email to " ",
            ProfileField.Website to "https://example.invalid/person",
        )

        assertEquals(
            setOf(ProfileField.ProfessionalTitle),
            QuickShareSelection.restoreSelection(values, store.readConfirmedKeys()),
        )
        assertEquals(
            setOf(ProfileField.ProfessionalTitle.key, ProfileField.Email.key),
            store.readConfirmedKeys(),
        )
    }

    fun testOrdinaryExportVisibilityDoesNotAffectSelectionPersistenceOrRestoration() {
        val values = mapOf(
            ProfileField.ProfessionalTitle to "Engineer",
            ProfileField.Email to "person@example.invalid",
        )
        val defaultVisibility = ProfileDraft.empty().visibility
        val exportEnabled = defaultVisibility.mapValues { (field, visibility) ->
            if (field == ProfileField.FullName || visibility.includeInContactExport == null) visibility
            else visibility.copy(includeInContactExport = true)
        }
        val exportDisabled = defaultVisibility.mapValues { (field, visibility) ->
            if (field == ProfileField.FullName || visibility.includeInContactExport == null) visibility
            else visibility.copy(includeInContactExport = false)
        }
        val profiles = listOf(
            Profile(UUID.randomUUID(), values, exportEnabled),
            Profile(UUID.randomUUID(), values, exportDisabled),
        )
        store.replaceConfirmedKeys(setOf(ProfileField.ProfessionalTitle.key, ProfileField.Email.key))

        val restored = profiles.map { profile ->
            QuickShareSelection.restoreSelection(profile.values, store.readConfirmedKeys())
        }

        assertEquals(restored[0], restored[1])
        assertEquals(setOf(ProfileField.ProfessionalTitle, ProfileField.Email), restored[0])
    }

    fun testReadOnlyRestorationDoesNotWritePreferences() {
        store.replaceConfirmedKeys(setOf(ProfileField.ProfessionalTitle.key))
        var writes = 0
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == TEST_SELECTION_KEY) writes++
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        try {
            QuickShareSelection.restoreSelection(
                mapOf(ProfileField.ProfessionalTitle to "Engineer"),
                store.readConfirmedKeys(),
            )
            assertEquals(0, writes)
        } finally {
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    private companion object {
        const val TEST_PREFERENCES_NAME = "quick_share_selection_store_test"
        const val TEST_SELECTION_KEY = "confirmed_additional_field_keys"
    }
}