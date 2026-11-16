package com.mackseoul.vcardnative.ui.profile

import android.test.InstrumentationTestCase
import androidx.lifecycle.ViewModelStore
import com.mackseoul.vcardnative.data.profile.ProfileRead
import com.mackseoul.vcardnative.data.profile.ProfileRepository
import com.mackseoul.vcardnative.data.profile.SavedProfile
import com.mackseoul.vcardnative.data.sharing.QuickShareQrGenerator
import com.mackseoul.vcardnative.data.sharing.QuickShareQrResult
import com.mackseoul.vcardnative.data.sharing.QuickShareSelectionStore
import com.mackseoul.vcardnative.domain.images.ImageException
import com.mackseoul.vcardnative.domain.profile.Profile
import com.mackseoul.vcardnative.domain.profile.ProfileDraft
import com.mackseoul.vcardnative.domain.profile.ProfileField
import com.mackseoul.vcardnative.domain.profile.ProfileValidator
import com.mackseoul.vcardnative.domain.profile.Visibility
import com.mackseoul.vcardnative.domain.profile.structuredNameFields
import com.mackseoul.vcardnative.domain.profile.toDraft
import com.mackseoul.vcardnative.domain.sharing.ContactExportException
import com.mackseoul.vcardnative.domain.sharing.QuickShareContactExport
import com.mackseoul.vcardnative.domain.sharing.QuickSharePreview
import com.mackseoul.vcardnative.domain.sharing.QuickShareSelection
import com.mackseoul.vcardnative.domain.sharing.QrFailure
import com.mackseoul.vcardnative.platform.storage.AndroidStoreFiles
import java.io.File
import java.util.UUID

@Suppress("DEPRECATION")
class QuickShareViewModelTest : InstrumentationTestCase() {
    private class MemorySelectionStore : QuickShareSelectionStore {
        @Volatile
        var confirmedKeys: Set<String> = emptySet()

        @Volatile
        var writeCount: Int = 0
            private set

        @Volatile
        var failWrites: Boolean = false

        override fun readConfirmedKeys(): Set<String> = confirmedKeys.toSet()

        override fun replaceConfirmedKeys(keys: Set<String>) {
            if (failWrites) throw IllegalStateException("Synthetic selection-store write failure")
            val allowed = QuickShareSelection.selectableFields.map { it.key }.toSet()
            confirmedKeys = keys.filterTo(linkedSetOf()) { it in allowed }
            writeCount++
        }

        fun reset() {
            confirmedKeys = emptySet()
            failWrites = false
            writeCount = 0
        }
    }

    private lateinit var root: File
    private lateinit var repository: ProfileRepository
    private lateinit var model: ProfileViewModel
    private val selectionStore = MemorySelectionStore()
    private val viewModels = ViewModelStore()
    private var qrGenerator: ((Profile, Set<ProfileField>) -> QuickShareQrResult)? = null

    override fun setUp() {
        super.setUp()
        selectionStore.reset()
        root = File(instrumentation.targetContext.noBackupFilesDir, "quick-share-${UUID.randomUUID()}")
        repository = ProfileRepository(AndroidStoreFiles(root))
        repository.save(profileDraft(), null)
        createModel()
    }

    private fun profileDraft(fullName: String = "Saved Quick Person"): ProfileDraft {
        val values = mapOf(
            ProfileField.FullName to fullName,
            ProfileField.FirstName to "PRIVATE_GIVEN_NAME",
            ProfileField.LastName to "PRIVATE_FAMILY_NAME",
            ProfileField.ProfessionalTitle to "Saved Engineer",
            ProfileField.Organization to "Saved Studio",
            ProfileField.Phone to "+1 202 555 0142",
            ProfileField.Email to "saved@example.invalid",
            ProfileField.Website to "https://example.invalid/ordinary-site",
            ProfileField.LinkedIn to "https://linkedin.example.invalid/saved-profile",
            ProfileField.GitHub to "https://github.example.invalid/saved-profile",
        )
        val defaults = ProfileDraft.empty().visibility
        val visibility = defaults.mapValues { (field, preference) ->
            when {
                field == ProfileField.FullName || field in structuredNameFields -> preference
                preference.includeInContactExport == null -> preference
                else -> Visibility(showOnCard = false, includeInContactExport = false)
            }
        }.toMutableMap().apply {
            this[ProfileField.FirstName] = Visibility(showOnCard = null, includeInContactExport = true)
            this[ProfileField.LastName] = Visibility(showOnCard = null, includeInContactExport = true)
            this[ProfileField.Website] = Visibility(showOnCard = false, includeInContactExport = true)
            this[ProfileField.LinkedIn] = Visibility(showOnCard = false, includeInContactExport = true)
            this[ProfileField.GitHub] = Visibility(showOnCard = false, includeInContactExport = true)
        }
        return ProfileDraft.empty().copy(values = values, visibility = visibility)
    }

    private fun createModel(
        qr: ((Profile, Set<ProfileField>) -> QuickShareQrResult)? = qrGenerator,
    ) {
        onMain { viewModels.clear() }
        onMain {
            model = ProfileViewModel(
                createRepository = { repository },
                readSelectedImage = { _, _ -> throw ImageException("Unused Quick Share test image reader") },
                cleanupContactFiles = {},
                quickShareSelectionStore = selectionStore,
                generateQuickShareQr = qr ?: { profile, selection ->
                    QuickShareQrGenerator.generate(profile, selection)
                },
            )
            viewModels.put("quick-share", model)
        }
        await { !it.loading }
    }

    override fun tearDown() {
        onMain { viewModels.clear() }
        root.deleteRecursively()
        super.tearDown()
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun await(condition: (ProfileScreenState) -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition(model.state.value)) {
            if (System.nanoTime() > deadline) fail("Quick Share ViewModel state did not settle")
            Thread.sleep(20)
        }
    }

    private fun enterQuickShare() {
        onMain {
            model.select(ProfileDestination.MyCard)
            model.startQuickShare()
        }
        await { !it.exporting && it.quickShareSession != null }
    }

    private fun selectFields(vararg fields: ProfileField) {
        onMain { fields.forEach(model::toggleQuickShareField) }
    }

    private fun continueToReview(vararg fields: ProfileField) {
        selectFields(*fields)
        onMain { model.continueQuickShareToReview() }
    }
    fun testEntryUsesTheSavedSnapshotNotAnUnsavedEditorDraft() {
        onMain {
            model.select(ProfileDestination.MyCard)
            model.select(ProfileDestination.Editor)
            model.edit(ProfileField.ProfessionalTitle, "UNSAVED_TITLE_MARKER")
            model.requestBack {}
            model.leaveEditor {}
        }

        enterQuickShare()

        val session = requireNotNull(model.state.value.quickShareSession)
        assertEquals("Saved Engineer", session.savedProfile.data.value(ProfileField.ProfessionalTitle))
        assertFalse(session.selectedValues.values.any { it.contains("UNSAVED_TITLE_MARKER") })
        assertEquals(0, selectionStore.writeCount)
    }

    fun testFirstUseStartsWithNoAdditionalFieldsSelected() {
        enterQuickShare()

        assertTrue(model.state.value.quickShareSession!!.selectedFields.isEmpty())
        assertEquals(0, selectionStore.writeCount)
    }

    fun testEntryRestoresOnlyPreviouslyConfirmedPopulatedAdditionalKeys() {
        selectionStore.confirmedKeys = setOf(
            ProfileField.ProfessionalTitle.key,
            ProfileField.Email.key,
            ProfileField.Website.key,
        )

        enterQuickShare()

        val session = model.state.value.quickShareSession!!
        assertEquals(setOf(ProfileField.ProfessionalTitle, ProfileField.Email), session.selectedFields)
        assertFalse(ProfileField.Website in session.availableFields)
        assertEquals(0, selectionStore.writeCount)
    }

    fun testSessionIncludesSavedNameAndExactlyFourAvailableAdditionalFields() {
        enterQuickShare()

        val session = model.state.value.quickShareSession!!
        assertEquals("Saved Quick Person", session.fullName)
        assertEquals(
            listOf(ProfileField.ProfessionalTitle, ProfileField.Organization, ProfileField.Phone, ProfileField.Email),
            session.availableFields,
        )
    }

    fun testFullNameAndWebsiteCannotBeToggled() {
        enterQuickShare()
        onMain {
            model.toggleQuickShareField(ProfileField.FullName)
            model.toggleQuickShareField(ProfileField.Website)
            model.toggleQuickShareField(ProfileField.ProfessionalTitle)
        }

        assertEquals(setOf(ProfileField.ProfessionalTitle), model.state.value.quickShareSession!!.selectedFields)
        assertEquals(0, selectionStore.writeCount)
    }

    fun testSelectAllAndClearAllAffectOnlyAvailableAdditionalFields() {
        enterQuickShare()

        onMain { model.selectAllQuickShareFields() }
        assertEquals(
            setOf(ProfileField.ProfessionalTitle, ProfileField.Organization, ProfileField.Phone, ProfileField.Email),
            model.state.value.quickShareSession!!.selectedFields,
        )
        assertEquals(0, selectionStore.writeCount)

        onMain { model.clearQuickShareFields() }
        assertTrue(model.state.value.quickShareSession!!.selectedFields.isEmpty())
        assertEquals(0, selectionStore.writeCount)
    }

    fun testContinueRequiresAValidSelectionAndDoesNotPersist() {
        enterQuickShare()

        onMain { model.continueQuickShareToReview() }
        assertEquals(ProfileDestination.QuickShareSelectInformation, model.state.value.destination)
        assertNotNull(model.state.value.quickShareSession!!.error)
        assertEquals(0, selectionStore.writeCount)

        continueToReview(ProfileField.ProfessionalTitle)
        assertEquals(ProfileDestination.QuickShareReview, model.state.value.destination)
        assertEquals(0, selectionStore.writeCount)
    }

    fun testReviewContainsOnlySavedSelectedAdditionalValues() {
        enterQuickShare()

        continueToReview(ProfileField.ProfessionalTitle, ProfileField.Email)

        val session = model.state.value.quickShareSession!!
        assertEquals("Saved Quick Person", session.fullName)
        assertEquals(
            mapOf(
                ProfileField.ProfessionalTitle to "Saved Engineer",
                ProfileField.Email to "saved@example.invalid",
            ),
            session.selectedValues,
        )
        assertFalse(session.selectedValues.values.any { it.contains("ordinary-site") || it.contains("linkedin") })
        assertEquals(0, selectionStore.writeCount)
    }

    fun testBackAndCancelDoNotPersistDraftChanges() {
        enterQuickShare()
        selectFields(ProfileField.Email)
        onMain { model.requestBack {} }

        assertEquals(ProfileDestination.MyCard, model.state.value.destination)
        assertNull(model.state.value.quickShareSession)
        assertEquals(0, selectionStore.writeCount)
    }

    fun testBackFromReviewReturnsToSelectionWithoutPersisting() {
        enterQuickShare()
        continueToReview(ProfileField.Email)

        onMain { model.requestBack {} }

        assertEquals(ProfileDestination.QuickShareSelectInformation, model.state.value.destination)
        assertEquals(setOf(ProfileField.Email), model.state.value.quickShareSession!!.selectedFields)
        assertEquals(0, selectionStore.writeCount)
    }

    fun testValidQrInitiationPersistsSelectionAndNavigatesToQuickShareQr() {
        enterQuickShare()
        continueToReview(ProfileField.Email)

        onMain { model.generateQuickShareQr() }
        await { !it.exporting && it.destination == ProfileDestination.QuickShareQr }

        val result = model.state.value.quickShareSession!!.qrResult
        assertTrue(result is QuickShareQrResult.QrAvailable)
        assertEquals(setOf(ProfileField.Email.key), selectionStore.confirmedKeys)
        assertEquals(1, selectionStore.writeCount)
    }

    fun testExportUnavailableDoesNotPersistSelectionOrNavigateToQr() {
        qrGenerator = { _, _ ->
            QuickShareQrResult.ExportUnavailable(ContactExportException("Synthetic filtered export failure"))
        }
        createModel()
        enterQuickShare()
        continueToReview(ProfileField.Email)

        onMain { model.generateQuickShareQr() }
        await { !it.exporting && it.quickShareSession?.qrResult is QuickShareQrResult.ExportUnavailable }

        assertEquals(ProfileDestination.QuickShareReview, model.state.value.destination)
        assertEquals(0, selectionStore.writeCount)
        assertEquals("Synthetic filtered export failure", model.state.value.quickShareSession!!.error)
    }

    fun testQrUnavailableRetainsVCardPersistsValidSelectionAndStaysOnReview() {
        qrGenerator = { profile, selection ->
            QuickShareQrResult.QrUnavailable(
                QuickShareContactExport.from(profile, selection),
                QrFailure.EncodingCapacity,
            )
        }
        createModel()
        enterQuickShare()
        continueToReview(ProfileField.Email)

        onMain { model.generateQuickShareQr() }
        await { !it.exporting && it.quickShareSession?.qrResult is QuickShareQrResult.QrUnavailable }

        val result = model.state.value.quickShareSession!!.qrResult as QuickShareQrResult.QrUnavailable
        assertTrue(String(result.export.bytes, Charsets.UTF_8).contains("EMAIL;TYPE=INTERNET:saved@example.invalid"))
        assertEquals(ProfileDestination.QuickShareReview, model.state.value.destination)
        assertEquals(setOf(ProfileField.Email.key), selectionStore.confirmedKeys)
    }

    fun testStaleSavedSnapshotCannotGenerateOrPersistQuickShareOutput() {
        enterQuickShare()
        continueToReview(ProfileField.Email)
        val oldSaved = model.state.value.saved!!
        repository.save(
            oldSaved.data.toDraft().copy(
                values = oldSaved.data.profile.values + (ProfileField.ProfessionalTitle to "Changed outside session"),
            ),
            oldSaved.token,
        )

        onMain { model.generateQuickShareQr() }
        await { !it.exporting && it.quickShareSession?.error != null }

        assertNull(model.state.value.quickShareSession!!.qrResult)
        assertEquals(0, selectionStore.writeCount)
        assertEquals(ProfileDestination.QuickShareReview, model.state.value.destination)
    }

    fun testVCardInitiationCreatesFilteredOneTimeHandoffAndPersistsSelection() {
        enterQuickShare()
        continueToReview(ProfileField.ProfessionalTitle, ProfileField.Email)

        onMain { model.initiateQuickShareContactShare() }
        await { !it.exporting && it.quickShareSession?.pendingContactHandoff != null }

        val handoff = onMainResult { model.takeQuickShareContactHandoff() }
        assertNotNull(handoff)
        val text = String(handoff!!.export.bytes, Charsets.UTF_8)
        assertTrue(text.contains("FN:Saved Quick Person"))
        assertTrue(text.contains("TITLE:Saved Engineer"))
        assertTrue(text.contains("EMAIL;TYPE=INTERNET:saved@example.invalid"))
        assertFalse(text.contains("URL:"))
        assertFalse(text.contains("linkedin"))
        assertEquals(setOf(ProfileField.ProfessionalTitle.key, ProfileField.Email.key), selectionStore.confirmedKeys)
        assertEquals(1, selectionStore.writeCount)
    }

    fun testHandoffIsConsumedOnlyOnceAndDuplicateActionDoesNotWriteTwice() {
        enterQuickShare()
        continueToReview(ProfileField.Email)

        onMain {
            model.initiateQuickShareContactShare()
            model.initiateQuickShareContactShare()
        }
        await { !it.exporting && it.quickShareSession?.pendingContactHandoff != null }

        assertEquals(1, selectionStore.writeCount)
        assertNotNull(onMainResult { model.takeQuickShareContactHandoff() })
        assertNull(onMainResult { model.takeQuickShareContactHandoff() })
    }

    fun testFailedShareHandoffIsReportedAndCanBeRetried() {
        enterQuickShare()
        continueToReview(ProfileField.Email)
        onMain { model.initiateQuickShareContactShare() }
        await { !it.exporting && it.quickShareSession?.pendingContactHandoff != null }

        val handoff = onMainResult { model.takeQuickShareContactHandoff() }
        assertNotNull(handoff)
        onMain { model.quickShareContactHandoffFailed("Synthetic chooser launch failure") }

        assertNull(model.state.value.quickShareSession!!.pendingContactHandoff)
        assertEquals("Synthetic chooser launch failure", model.state.value.quickShareSession!!.error)
        onMain { model.initiateQuickShareContactShare() }
        await { !it.exporting && it.quickShareSession?.pendingContactHandoff != null }
        assertNotNull(onMainResult { model.takeQuickShareContactHandoff() })
        assertEquals(2, selectionStore.writeCount)
    }

    fun testPrimaryQrNavigationAndPayloadRemainUnchanged() {
        onMain {
            model.select(ProfileDestination.MyCard)
            model.showCardQr()
        }
        await { it.destination == ProfileDestination.QrPresentation && !it.qrLoading }

        val payload = String(model.state.value.contactExport!!.bytes, Charsets.UTF_8)
        assertTrue(payload.contains("URL:https://example.invalid/ordinary-site"))
        assertTrue(payload.contains("URL:https://linkedin.example.invalid/saved-profile"))
        assertNull(model.state.value.quickShareSession)
        assertEquals(0, selectionStore.writeCount)
    }

    fun testDoneClearsSessionAndReturnsToMyCard() {
        enterQuickShare()
        continueToReview(ProfileField.Email)

        onMain { model.doneQuickShare() }

        assertEquals(ProfileDestination.MyCard, model.state.value.destination)
        assertNull(model.state.value.quickShareSession)
        assertEquals(0, selectionStore.writeCount)
    }

    fun testNoAdditionalProfileValuesExposeEmptySelectionState() {
        val saved = model.state.value.saved!!
        repository.save(
            ProfileDraft.empty().copy(values = mapOf(ProfileField.FullName to "Name Only")),
            saved.token,
        )
        onMain { model.reload() }
        await { !it.loading && it.destination == ProfileDestination.Home }

        enterQuickShare()

        val session = model.state.value.quickShareSession!!
        assertTrue(session.availableFields.isEmpty())
        assertTrue(session.selectedFields.isEmpty())
        assertEquals(0, selectionStore.writeCount)
    }

    fun testEditingFromQuickShareEmptyStateReturnsToMyCard() {
        val saved = model.state.value.saved!!
        repository.save(
            ProfileDraft.empty().copy(values = mapOf(ProfileField.FullName to "Name Only")),
            saved.token,
        )
        onMain { model.reload() }
        await { !it.loading && it.destination == ProfileDestination.Home }
        enterQuickShare()
        assertTrue(model.state.value.quickShareSession!!.availableFields.isEmpty())

        onMain {
            model.cancelQuickShare()
            model.select(ProfileDestination.Editor)
        }

        assertEquals(ProfileDestination.Editor, model.state.value.destination)
        assertEquals(ProfileDestination.MyCard, model.state.value.editorCaller)
    }

    fun testConfirmedSelectionWriteFailureIsExposedRatherThanClaimedSuccessful() {
        selectionStore.failWrites = true
        enterQuickShare()
        continueToReview(ProfileField.Email)

        onMain { model.generateQuickShareQr() }
        await { !it.exporting && it.destination == ProfileDestination.QuickShareQr }

        assertTrue(model.state.value.quickShareSession!!.qrResult is QuickShareQrResult.QrAvailable)
        assertNotNull(model.state.value.quickShareSession!!.selectionPersistenceError)
        assertTrue(selectionStore.confirmedKeys.isEmpty())
    }

    fun testPreviewTracksToggleSelectAllAndClearAllWithoutPersisting() {
        enterQuickShare()
        assertEquals(QuickSharePreview.NoSelection, model.state.value.quickShareSession!!.preview)

        selectFields(ProfileField.Email)
        val single = model.state.value.quickShareSession!!.preview as QuickSharePreview.Ready
        assertEquals(listOf(ProfileField.Email), single.rows.map { it.field })

        onMain { model.selectAllQuickShareFields() }
        val all = model.state.value.quickShareSession!!.preview as QuickSharePreview.Ready
        assertEquals(
            listOf(ProfileField.Organization, ProfileField.ProfessionalTitle, ProfileField.Phone, ProfileField.Email),
            all.rows.map { it.field },
        )

        onMain { model.clearQuickShareFields() }
        assertEquals(QuickSharePreview.NoSelection, model.state.value.quickShareSession!!.preview)
        assertEquals(0, selectionStore.writeCount)
        assertTrue(selectionStore.confirmedKeys.isEmpty())
    }

    fun testReviewPreviewMatchesExportedRowsWhileSelectedValuesStayRaw() {
        enterQuickShare()
        continueToReview(ProfileField.Phone, ProfileField.ProfessionalTitle, ProfileField.Organization)

        val session = model.state.value.quickShareSession!!
        val export = QuickShareContactExport.from(session.savedProfile.data.profile, session.selectedFields)
        val preview = session.preview as QuickSharePreview.Ready
        assertEquals("Saved Quick Person", preview.fullName)
        assertEquals(
            export.rows.drop(1).map { it.fields.single() to it.value },
            preview.rows.map { it.field to it.value },
        )
        assertEquals("+1 202-555-0142", preview.rows.single { it.field == ProfileField.Phone }.value)
        assertEquals("+1 202 555 0142", session.selectedValues[ProfileField.Phone])
        assertFalse(preview.rows.any { it.value.contains("PRIVATE_") || it.value.contains("ordinary-site") })
        assertEquals(0, selectionStore.writeCount)

        onMain { model.requestBack {} }
        assertEquals(ProfileDestination.QuickShareSelectInformation, model.state.value.destination)
        assertEquals(preview, model.state.value.quickShareSession!!.preview)
        assertEquals(0, selectionStore.writeCount)
    }

    private fun onMainResult(action: () -> QuickShareContactHandoff?): QuickShareContactHandoff? {
        var result: QuickShareContactHandoff? = null
        onMain { result = action() }
        return result
    }
}
