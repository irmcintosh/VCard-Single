package com.mackseoul.vcardnative.ui.profile

import android.net.Uri
import android.test.InstrumentationTestCase
import androidx.lifecycle.ViewModelStore
import com.mackseoul.vcardnative.data.profile.ProfileRead
import com.mackseoul.vcardnative.data.profile.ProfileRepository
import com.mackseoul.vcardnative.data.profile.StoreFiles
import com.mackseoul.vcardnative.domain.images.ImageException
import com.mackseoul.vcardnative.domain.profile.DraftImage
import com.mackseoul.vcardnative.domain.profile.Accent
import com.mackseoul.vcardnative.domain.profile.Appearance
import com.mackseoul.vcardnative.domain.profile.CardStyle
import com.mackseoul.vcardnative.domain.profile.MinimalItem
import com.mackseoul.vcardnative.domain.profile.ProfileDraft
import com.mackseoul.vcardnative.domain.profile.ProfileField
import com.mackseoul.vcardnative.domain.profile.PhoneNumbers
import com.mackseoul.vcardnative.domain.profile.Theme
import com.mackseoul.vcardnative.domain.profile.Visibility
import com.mackseoul.vcardnative.platform.storage.AndroidStoreFiles
import com.mackseoul.vcardnative.R
import java.io.File
import java.io.IOException
import java.util.UUID

@Suppress("DEPRECATION")
class ProfileSetupWorkflowTest : InstrumentationTestCase() {
    private lateinit var root: File
    private lateinit var repository: ProfileRepository
    private lateinit var model: ProfileViewModel
    private var models = ViewModelStore()

    override fun setUp() {
        super.setUp()
        root = File(instrumentation.targetContext.noBackupFilesDir, "setup-workflow-${UUID.randomUUID()}")
        repository = ProfileRepository(AndroidStoreFiles(root.resolve("store")))
        createModel(repository)
    }

    override fun tearDown() {
        main { models.clear() }
        root.deleteRecursively()
        super.tearDown()
    }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun createModel(source: ProfileRepository) {
        main {
            model = ProfileViewModel(
                createRepository = { source },
                readSelectedImage = { _: Uri, _ -> throw ImageException("Unused test reader") },
                cleanupContactFiles = {},
            )
            models.put("setup", model)
        }
        await { !it.loading }
    }

    private fun await(condition: (ProfileScreenState) -> Boolean) {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!condition(model.state.value)) {
            if (System.nanoTime() > deadline) fail("Profile setup did not settle: ${model.state.value.saveError}")
            Thread.sleep(20)
        }
    }

    fun testFirstTimeSetupAndSavedProfileRestartUsesShareDefault() {
        assertTrue(model.state.value.firstRun)
        assertEquals(ProfileDestination.Welcome, model.state.value.destination)
        assertEquals(ProfileRead.Empty, repository.load())
        assertEquals(PhoneNumbers.defaultRegion(), model.state.value.draft!!.phoneRegion)

        main {
            model.beginProfileSetup()
            model.advanceProfileSetup()
        }
        assertEquals(ProfileDestination.SetupDetails, model.state.value.destination)
        assertTrue(model.state.value.errors.containsKey(ProfileField.FirstName))
        assertEquals(ProfileRead.Empty, repository.load())

        main {
            model.edit(ProfileField.FirstName, "Setup")
            model.edit(ProfileField.MiddleName, "Synthetic")
            model.edit(ProfileField.LastName, "Fixture")
            model.edit(ProfileField.ProfessionalTitle, "Product Designer")
            model.edit(ProfileField.Organization, "Fixture Studio")
            model.advanceProfileSetup()
            model.edit(ProfileField.Email, "not-an-email")
            model.advanceProfileSetup()
        }
        assertEquals(ProfileDestination.SetupReview, model.state.value.destination)
        assertEquals(ProfileRead.Empty, repository.load())

        main { model.save() }
        assertEquals(ProfileDestination.SetupContact, model.state.value.destination)
        assertTrue(model.state.value.errors.containsKey(ProfileField.Email))
        assertEquals(ProfileRead.Empty, repository.load())

        main {
            model.edit(ProfileField.Email, "fixture@example.test")
            model.advanceProfileSetup()
            model.save()
        }
        await { !it.saving }
        assertEquals(ProfileDestination.Home, model.state.value.destination)
        val persisted = (repository.load() as ProfileRead.Ready).saved.data
        assertEquals("Setup Synthetic Fixture", persisted.value(ProfileField.FullName))
        assertEquals("Setup", persisted.value(ProfileField.FirstName))
        assertEquals("Synthetic", persisted.value(ProfileField.MiddleName))
        assertEquals("Fixture", persisted.value(ProfileField.LastName))
        assertEquals("Product Designer", persisted.value(ProfileField.ProfessionalTitle))
        assertEquals("Fixture Studio", persisted.value(ProfileField.Organization))
        assertEquals("fixture@example.test", persisted.value(ProfileField.Email))
        assertTrue(persisted.profile.includeCountryCodeWhenSharing)

        main { models.clear() }
        models = ViewModelStore()
        createModel(repository)
        assertFalse(model.state.value.firstRun)
        assertEquals(ProfileDestination.Home, model.state.value.destination)
        assertEquals("Synthetic", model.state.value.saved!!.data.value(ProfileField.MiddleName))
    }

    fun testBackAndSkipPreserveSetupDraftAndDoNotPersist() {
        main {
            model.beginProfileSetup()
            model.edit(ProfileField.FirstName, "Draft")
            model.edit(ProfileField.Organization, "Draft Organization")
            model.advanceProfileSetup()
            model.selectPhoneRegion("GB")
            model.edit(ProfileField.Phone, "020 7031 3000")
            model.edit(ProfileField.Email, "draft@example.test")
            model.edit(ProfileField.Website, "https://example.test")
            model.previousProfileSetup()
        }
        assertEquals(ProfileDestination.SetupDetails, model.state.value.destination)
        assertEquals("Draft", model.state.value.fullNamePreview)
        assertEquals("Draft Organization", model.state.value.draft!!.values[ProfileField.Organization])

        main {
            model.advanceProfileSetup()
            model.skipProfileContact()
        }
        assertEquals(ProfileDestination.SetupReview, model.state.value.destination)
        assertEquals("020 7031 3000", model.state.value.draft!!.values[ProfileField.Phone])
        assertEquals("GB", model.state.value.draft!!.phoneRegion)
        assertEquals("draft@example.test", model.state.value.draft!!.values[ProfileField.Email])
        assertEquals("https://example.test", model.state.value.draft!!.values[ProfileField.Website])
        assertEquals(ProfileRead.Empty, repository.load())
    }

    fun testAppearanceBackRequiresDiscardAndLeavesSavedValuesUnchanged() {
        repository.save(
            ProfileDraft.empty().copy(values = mapOf(ProfileField.FullName to "Appearance Fixture")),
            null,
        )
        main {
            models.clear()
            models = ViewModelStore()
        }
        createModel(repository)
        val savedAppearance = requireNotNull(model.state.value.saved).data.appearance
        assertEquals(ProfileDestination.Home, model.state.value.destination)

        main {
            model.select(ProfileDestination.Appearance)
            model.appearance(Appearance(Theme.light, Accent.jade))
            model.requestBack {}
        }
        assertTrue(model.state.value.confirmDiscard)
        assertTrue(model.state.value.dirty)

        main { model.keepEditing() }
        assertFalse(model.state.value.confirmDiscard)
        assertTrue(model.state.value.dirty)
        assertEquals(savedAppearance, model.state.value.saved?.data?.appearance)

        main {
            model.requestBack {}
            model.leaveEditor {}
        }
        assertEquals(ProfileDestination.Profile, model.state.value.destination)
        assertFalse(model.state.value.dirty)
        assertEquals(savedAppearance, (repository.load() as ProfileRead.Ready).saved.data.appearance)
    }

    fun testSavingAppearanceExplicitlyPersistsDraftSelection() {
        repository.save(
            ProfileDraft.empty().copy(values = mapOf(ProfileField.FullName to "Appearance Save Fixture")),
            null,
        )
        main {
            models.clear()
            models = ViewModelStore()
        }
        createModel(repository)
        main {
            model.select(ProfileDestination.Appearance)
            model.appearance(Appearance(Theme.light, Accent.amethyst))
            model.saveAppearance()
        }
        await { !it.saving }

        assertEquals(ProfileDestination.Profile, model.state.value.destination)
        assertEquals(
            Appearance(Theme.light, Accent.amethyst),
            (repository.load() as ProfileRead.Ready).saved.data.appearance,
        )
    }

    private fun loadAppearanceFixture(name: String) {
        repository.save(
            ProfileDraft.empty().copy(
                values = mapOf(ProfileField.FullName to name, ProfileField.Email to "fixture@example.test"),
            ),
            null,
        )
        main {
            models.clear()
            models = ViewModelStore()
        }
        createModel(repository)
        main { model.select(ProfileDestination.Appearance) }
    }

    fun testCardStyleAndMinimalSelectionsPersistAndPreserveProfileValues() {
        loadAppearanceFixture("Style Save Fixture")
        val before = requireNotNull(model.state.value.saved).data.profile.values
        main {
            model.selectCardStyle(CardStyle.Minimal)
            model.toggleMinimalItem(MinimalItem.Email)
            model.toggleMinimalItem(MinimalItem.Website)
            model.selectCardStyle(CardStyle.Horizon)
            model.selectCardStyle(CardStyle.Minimal)
        }
        assertEquals(setOf(MinimalItem.Email, MinimalItem.Website), model.state.value.appearanceDraft?.minimalItems)
        assertEquals(Appearance(), (repository.load() as ProfileRead.Ready).saved.data.appearance)
        main { model.saveAppearance() }
        await { !it.saving }

        val saved = (repository.load() as ProfileRead.Ready).saved.data
        assertEquals(CardStyle.Minimal, saved.appearance.cardStyle)
        assertEquals(setOf(MinimalItem.Email, MinimalItem.Website), saved.appearance.minimalItems)
        assertEquals(before, saved.profile.values)
    }

    fun testFifthMinimalItemIsRefused() {
        loadAppearanceFixture("Minimal Limit Fixture")
        main {
            model.selectCardStyle(CardStyle.Minimal)
            MinimalItem.entries.forEach { model.toggleMinimalItem(it) }
        }
        assertEquals(MinimalItem.entries.take(4).toSet(), model.state.value.appearanceDraft?.minimalItems)
    }

    fun testRestoreDefaultChangesDraftOnlyUntilSave() {
        loadAppearanceFixture("Restore Fixture")
        main {
            model.appearance(Appearance(Theme.light, Accent.jade, CardStyle.Editorial, setOf(MinimalItem.Phone)))
            model.saveAppearance()
        }
        await { !it.saving }
        val custom = (repository.load() as ProfileRead.Ready).saved.data.appearance
        main {
            model.select(ProfileDestination.Appearance)
            model.restoreDefaultAppearance()
        }
        assertEquals(Appearance(), model.state.value.appearanceDraft)
        assertTrue(model.state.value.dirty)
        assertEquals(custom, (repository.load() as ProfileRead.Ready).saved.data.appearance)

        main { model.requestBack {} }
        assertTrue(model.state.value.confirmDiscard)
        main { model.keepEditing() }
        main { model.saveAppearance() }
        await { !it.saving }
        assertEquals(Appearance(), (repository.load() as ProfileRead.Ready).saved.data.appearance)
    }

    fun testCancelDiscardsAppearanceDraftWithoutSaving() {
        loadAppearanceFixture("Cancel Fixture")
        main {
            model.selectCardStyle(CardStyle.Modern)
            model.appearance(requireNotNull(model.state.value.appearanceDraft).copy(theme = Theme.light))
            model.cancelAppearance()
        }
        assertEquals(ProfileDestination.Profile, model.state.value.destination)
        assertFalse(model.state.value.confirmDiscard)
        assertFalse(model.state.value.dirty)
        assertEquals(Appearance(), model.state.value.appearanceDraft)
        assertEquals(Appearance(), (repository.load() as ProfileRead.Ready).saved.data.appearance)
    }

    fun testVisibilitySettingsRemainInDraftUntilProfileSave() {
        repository.save(
            ProfileDraft.empty().copy(values = mapOf(ProfileField.FullName to "Saved Fixture")),
            null,
        )
        main { models.clear() }
        models = ViewModelStore()
        createModel(repository)
        val before = (repository.load() as ProfileRead.Ready).saved

        main {
            model.select(ProfileDestination.Profile)
            model.openEditorSettings(ProfileDestination.Visibility)
        }
        assertEquals(ProfileDestination.Visibility, model.state.value.destination)
        main {
            model.privacy(ProfileField.Email, Visibility(showOnCard = false, includeInContactExport = true))
            model.returnToEditor()
        }
        assertTrue(model.state.value.dirty)
        assertEquals(before, (repository.load() as ProfileRead.Ready).saved)

        main { model.save() }
        await { !it.saving }
        val after = (repository.load() as ProfileRead.Ready).saved
        assertEquals(ProfileDestination.Profile, model.state.value.destination)
        assertEquals(Visibility(false, true), after.data.visibility(ProfileField.Email))
    }

    fun testFailedFirstSaveKeepsDraftAndNeverPublishesPartialProfile() {
        val failing = ProfileRepository(FailingStoreFiles(AndroidStoreFiles(root.resolve("failing-store"))))
        main { models.clear() }
        models = ViewModelStore()
        createModel(failing)
        main {
            model.beginProfileSetup()
            model.edit(ProfileField.FirstName, "Not Yet Saved")
            model.advanceProfileSetup()
            model.skipProfileContact()
            model.save()
        }
        await { !it.saving && it.saveError != null }
        assertEquals(ProfileDestination.SetupReview, model.state.value.destination)
        assertEquals("Not Yet Saved", model.state.value.fullNamePreview)
        assertNull(model.state.value.saved)
        assertFalse(failing.load() is ProfileRead.Ready)
    }

    fun testProfileDeletionRequiresConfirmationAndDoesNotResurrectAfterReload() {
        assertEquals("Delete Profile?", instrumentation.targetContext.getString(R.string.delete_profile_title))
        assertEquals("Cancel", instrumentation.targetContext.getString(R.string.cancel))
        assertEquals("Delete Profile", instrumentation.targetContext.getString(R.string.delete_profile))
        assertTrue(
            instrumentation.targetContext.getString(R.string.delete_profile_confirmation)
                .contains("permanently removes the saved profile and associated local profile data"),
        )
        assertTrue(
            instrumentation.targetContext.getString(R.string.delete_profile_confirmation)
                .contains("External backup files are not deleted."),
        )
        repository.save(
            ProfileDraft.empty().copy(values = mapOf(ProfileField.FullName to "Delete Fixture")),
            null,
        )
        main { models.clear() }
        models = ViewModelStore()
        createModel(repository)

        main { model.requestDeleteProfile() }
        assertFalse(model.state.value.confirmDeleteProfile)
        main {
            model.select(ProfileDestination.Profile)
            model.requestDeleteProfile()
        }
        assertTrue(model.state.value.confirmDeleteProfile)
        assertTrue(repository.load() is ProfileRead.Ready)

        main { model.cancelDeleteProfile() }
        assertFalse(model.state.value.confirmDeleteProfile)
        assertTrue(repository.load() is ProfileRead.Ready)

        main {
            model.requestDeleteProfile()
            model.deleteProfile()
        }
        await { !it.deletingProfile && it.destination == ProfileDestination.Welcome }
        assertEquals(ProfileRead.Empty, repository.load())

        main { models.clear() }
        models = ViewModelStore()
        createModel(repository)
        assertTrue(model.state.value.firstRun)
        assertEquals(ProfileDestination.Welcome, model.state.value.destination)
        assertEquals(ProfileRead.Empty, repository.load())
    }

    fun testFailedProfileDeletionKeepsProfileAndDoesNotNavigateToOnboarding() {
        val storeFiles = AndroidStoreFiles(root.resolve("delete-failure"))
        ProfileRepository(storeFiles).save(
            ProfileDraft.empty().copy(values = mapOf(ProfileField.FullName to "Preserved Fixture")),
            null,
        )
        val failingRepository = ProfileRepository(DeleteFailingStoreFiles(storeFiles))
        main { models.clear() }
        models = ViewModelStore()
        createModel(failingRepository)

        main {
            model.select(ProfileDestination.Profile)
            model.requestDeleteProfile()
            model.deleteProfile()
        }
        await { !it.deletingProfile && it.deleteError != null }

        assertEquals(ProfileDestination.Profile, model.state.value.destination)
        assertFalse(model.state.value.firstRun)
        assertEquals(
            "Preserved Fixture",
            (failingRepository.load() as ProfileRead.Ready).saved.data.value(ProfileField.FullName),
        )
    }

    private class FailingStoreFiles(private val delegate: StoreFiles) : StoreFiles by delegate {
        override fun writeNew(path: String, bytes: ByteArray) {
            throw IOException("Injected storage failure")
        }
    }

    private class DeleteFailingStoreFiles(private val delegate: StoreFiles) : StoreFiles by delegate {
        override fun delete(path: String) {
            throw IOException("Injected deletion failure")
        }
    }
}
