package com.mackseoul.vcardnative.data.sharing

import com.mackseoul.vcardnative.domain.profile.ProfileField
import com.mackseoul.vcardnative.domain.sharing.QuickShareSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickShareSelectionStoreTest {
    private class MemorySelectionStore : QuickShareSelectionStore {
        private var confirmedKeys: Set<String> = emptySet()
        var writes = 0
            private set

        override fun readConfirmedKeys(): Set<String> = confirmedKeys.toSet()

        override fun replaceConfirmedKeys(keys: Set<String>) {
            confirmedKeys = keys.toSet()
            writes++
        }
    }

    @Test
    fun firstUseReturnsEmptyConfirmedKeys() {
        assertTrue(MemorySelectionStore().readConfirmedKeys().isEmpty())
    }

    @Test
    fun savingConfirmedKeysMakesThemAvailableOnTheNextRead() {
        val store = MemorySelectionStore()
        val selection = setOf(ProfileField.ProfessionalTitle.key, ProfileField.Phone.key)

        store.replaceConfirmedKeys(selection)

        assertEquals(selection, store.readConfirmedKeys())
    }

    @Test
    fun replacingConfirmedKeysRemovesPreviousKeys() {
        val store = MemorySelectionStore()
        store.replaceConfirmedKeys(setOf(ProfileField.ProfessionalTitle.key, ProfileField.Phone.key))

        store.replaceConfirmedKeys(setOf(ProfileField.Email.key))

        assertEquals(setOf(ProfileField.Email.key), store.readConfirmedKeys())
    }

    @Test
    fun replacingWithEmptySelectionClearsPreviousKeys() {
        val store = MemorySelectionStore()
        store.replaceConfirmedKeys(setOf(ProfileField.ProfessionalTitle.key))

        store.replaceConfirmedKeys(emptySet())

        assertTrue(store.readConfirmedKeys().isEmpty())
    }

    @Test
    fun readOnlyRestorationDoesNotWriteConfirmedKeys() {
        val store = MemorySelectionStore()
        store.replaceConfirmedKeys(setOf(ProfileField.ProfessionalTitle.key))
        val writesBeforeRestore = store.writes

        QuickShareSelection.restoreSelection(
            mapOf(
                ProfileField.ProfessionalTitle to "Engineer",
                ProfileField.Website to "https://example.invalid/person",
            ),
            store.readConfirmedKeys(),
        )

        assertEquals(writesBeforeRestore, store.writes)
    }

    @Test
    fun restorationUsesPopulatedValuesWithoutSelectingNewFields() {
        val store = MemorySelectionStore()
        store.replaceConfirmedKeys(setOf(ProfileField.ProfessionalTitle.key))

        val restored = QuickShareSelection.restoreSelection(
            mapOf(
                ProfileField.ProfessionalTitle to "Engineer",
                ProfileField.Website to "https://example.invalid/person",
            ),
            store.readConfirmedKeys(),
        )

        assertEquals(setOf(ProfileField.ProfessionalTitle), restored)
        assertEquals(setOf(ProfileField.ProfessionalTitle.key), store.readConfirmedKeys())
    }
}