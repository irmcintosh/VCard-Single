package com.mackseoul.vcardnative.ui.profile

import android.os.Looper
import android.test.InstrumentationTestCase
import androidx.lifecycle.ViewModelStore
import com.mackseoul.vcardnative.data.profile.ProfileRead
import com.mackseoul.vcardnative.data.profile.ProfileRepository
import com.mackseoul.vcardnative.domain.images.ImageException
import com.mackseoul.vcardnative.domain.profile.*
import com.mackseoul.vcardnative.domain.sharing.ContactExport
import com.mackseoul.vcardnative.domain.sharing.ContactExportException
import com.mackseoul.vcardnative.platform.storage.AndroidStoreFiles
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@Suppress("DEPRECATION")
class ContactReviewStateTest : InstrumentationTestCase() {
    private lateinit var root: File
    private lateinit var repository: ProfileRepository
    private lateinit var model: ProfileViewModel
    private val viewModels = ViewModelStore()
    private val savedDraft = ProfileDraft.empty().copy(
        values = mapOf(
            ProfileField.FullName to "Saved Integration",
            ProfileField.ProfessionalTitle to "Product Designer",
            ProfileField.Organization to "Example Studio",
            ProfileField.Phone to "+1 650-253-0000",
            ProfileField.Email to "${"a".repeat(55)}@example.invalid",
            ProfileField.Website to "https://example.invalid/${"long-path-segment-".repeat(45)}",
            ProfileField.LinkedIn to "https://linkedin.example.invalid/in/saved-integration",
            ProfileField.Location to "Portland, Oregon",
            ProfileField.Biography to "A long but readable profile summary. ".repeat(9).trim(),
            ProfileField.OtherProfessional to "https://private.example.invalid/not-exported",
        ),
        phoneRegion = "US",
        visibility = ProfileDraft.empty().visibility + mapOf(
            ProfileField.ProfessionalTitle to Visibility(false, true),
            ProfileField.Organization to Visibility(false, true),
            ProfileField.Phone to Visibility(false, true),
            ProfileField.Email to Visibility(false, true),
            ProfileField.Website to Visibility(false, true),
            ProfileField.LinkedIn to Visibility(false, true),
            ProfileField.Location to Visibility(false, true),
            ProfileField.Biography to Visibility(false, true),
            ProfileField.OtherProfessional to Visibility(true, false),
        ),
    )

    override fun setUp() {
        super.setUp()
        root = File(instrumentation.targetContext.noBackupFilesDir, "contact-test-${UUID.randomUUID()}")
        repository = ProfileRepository(AndroidStoreFiles(root))
        repository.save(savedDraft, null)
        instrumentation.runOnMainSync {
            model = ProfileViewModel({ repository }, { _, _ -> throw ImageException("Unused test image reader") }, {})
            viewModels.put("contact", model)
        }
        await { !it.loading && !it.qrLoading }
    }

    override fun tearDown() {
        instrumentation.runOnMainSync { viewModels.clear() }
        root.deleteRecursively()
        super.tearDown()
    }

    private fun await(condition: (ProfileScreenState) -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition(model.state.value)) {
            if (System.nanoTime() > deadline) fail("Profile state operation did not complete")
            Thread.sleep(20)
        }
    }

    private fun run(action: () -> Unit) = instrumentation.runOnMainSync(action)

    fun testPreviewShowsOnlySavedExportValuesAndDismissalDoesNotEditProfile() {
        run { model.reviewContact() }
        await { it.contactReview != null && !it.exporting }
        val preview = requireNotNull(model.state.value.contactReview)
        assertEquals(
            setOf(
                ProfileField.FullName,
                ProfileField.ProfessionalTitle,
                ProfileField.Organization,
                ProfileField.Phone,
                ProfileField.Email,
                ProfileField.Website,
                ProfileField.LinkedIn,
                ProfileField.Location,
                ProfileField.Biography,
            ),
            preview.export.rows.flatMap { it.fields }.toSet(),
        )
        assertTrue(preview.export.rows.any { it.fields == listOf(ProfileField.FullName) && it.value == "Saved Integration" })
        assertTrue(preview.export.rows.any { it.fields == listOf(ProfileField.Email) && it.value == savedDraft.values[ProfileField.Email] })
        assertTrue(preview.export.rows.any { it.fields == listOf(ProfileField.Phone) && it.value == "+1 650-253-0000" })
        assertTrue(preview.export.rows.any { it.fields == listOf(ProfileField.Website) && it.value == savedDraft.values[ProfileField.Website] })
        assertTrue(preview.export.rows.any { it.fields == listOf(ProfileField.Biography) && it.value == savedDraft.values[ProfileField.Biography] })
        assertTrue(preview.export.rows.none { ProfileField.OtherProfessional in it.fields })
        assertTrue(preview.export.rows.single { ProfileField.Website in it.fields }.value.length > 500)

        run { model.dismissContactReview() }
        assertNull(model.state.value.contactReview)
        assertEquals("Saved Integration", repository.load().let { (it as ProfileRead.Ready).saved.data.value(ProfileField.FullName) })
        assertEquals(
            savedDraft.values[ProfileField.Email],
            repository.load().let { (it as ProfileRead.Ready).saved.data.value(ProfileField.Email) },
        )
    }

    fun testPreviewEditSharingSettingsOpensContactExportFirst() {
        run { model.reviewContact() }
        await { it.contactReview != null && !it.exporting }
        run { model.editExportSelections() }
        assertEquals(ProfileDestination.Visibility, model.state.value.destination)
        assertTrue(model.state.value.contactExportSettingsFirst)
        run { model.returnToEditor() }
        assertFalse(model.state.value.contactExportSettingsFirst)
    }

    fun testCountryAndPhoneExportFormatPersistWithoutChangingVisibility() {
        run {
            model.select(ProfileDestination.Editor)
            model.selectPhoneRegion("GB")
            model.edit(ProfileField.Phone, "020 7031 3000")
            model.privacy(ProfileField.Phone, Visibility(showOnCard = false, includeInContactExport = true))
            model.includeCountryCodeWhenSharing(false)
            model.save()
        }
        await { !it.saving }
        var saved = (repository.load() as ProfileRead.Ready).saved.data
        assertEquals("020 7031 3000", saved.value(ProfileField.Phone))
        assertEquals("+442070313000", saved.profile.phoneNumberE164)
        assertEquals("GB", saved.profile.phoneRegion)
        assertFalse(saved.profile.includeCountryCodeWhenSharing)
        assertEquals(Visibility(false, true), saved.visibility(ProfileField.Phone))
        assertEquals(
            "020 7031 3000",
            ContactExport.from(saved.profile).rows.single { ProfileField.Phone in it.fields }.value,
        )

        run { model.select(ProfileDestination.Editor) }
        assertEquals("020 7031 3000", model.state.value.draft!!.values[ProfileField.Phone])
        assertEquals("GB", model.state.value.draft!!.phoneRegion)
        assertFalse(model.state.value.draft!!.includeCountryCodeWhenSharing)

        run {
            model.includeCountryCodeWhenSharing(true)
            model.save()
        }
        await { !it.saving }
        saved = (repository.load() as ProfileRead.Ready).saved.data
        assertEquals(Visibility(false, true), saved.visibility(ProfileField.Phone))
        assertEquals(
            "+44 20 7031 3000",
            ContactExport.from(saved.profile).rows.single { ProfileField.Phone in it.fields }.value,
        )
    }

    fun testSharingSettingsRemainDraftUntilExplicitSave() {
        run {
            model.reviewContact()
        }
        await { it.contactReview != null && !it.exporting }
        run {
            model.editExportSelections()
            listOf(
                ProfileField.Email,
                ProfileField.ProfessionalTitle,
                ProfileField.Organization,
                ProfileField.Phone,
                ProfileField.Website,
                ProfileField.LinkedIn,
                ProfileField.Location,
                ProfileField.Biography,
                ProfileField.OtherProfessional,
            ).forEach { model.privacy(it, Visibility(false, false)) }
        }
        val beforeSave = (repository.load() as ProfileRead.Ready).saved
        assertEquals(Visibility(false, true), beforeSave.data.visibility(ProfileField.Email))
        run { model.save() }
        await { !it.saving && it.destination == ProfileDestination.Home && !it.qrLoading && it.contactExport != null }
        assertEquals(Visibility(false, false), (repository.load() as ProfileRead.Ready).saved.data.visibility(ProfileField.Email))
        val minimalFile = String(model.state.value.contactExport!!.bytes, Charsets.UTF_8)
        assertTrue(minimalFile.contains("FN:Saved Integration"))
        assertFalse(minimalFile.contains("EMAIL"))
        assertFalse(minimalFile.contains("URL:"))
        run { model.reviewContact() }
        await { it.contactReview != null && !it.exporting }
        assertEquals(
            setOf(ProfileField.FullName),
            model.state.value.contactReview!!.export.rows.flatMap { it.fields }.toSet(),
        )
        run { model.dismissContactReview() }
        assertNull(model.state.value.contactReview)
    }

    fun testDirectShareUsesSavedExportAndInvokesNativeHandoffOnMainThread() {
        val exported = AtomicReference<ContactExport?>()
        run { model.shareContact { export ->
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            exported.set(export)
        } }
        await { !it.exporting && it.handoffNotice }
        val contact = requireNotNull(exported.get())
        val text = String(contact.bytes, Charsets.UTF_8)
        assertTrue(text.contains("Saved Integration"))
        assertTrue(text.contains("EMAIL;TYPE=INTERNET:"))
        assertTrue(
            contact.rows.any {
                ProfileField.Email in it.fields && it.value == savedDraft.values[ProfileField.Email]
            },
        )
        assertFalse(text.contains("Unsaved"))
        assertNull(model.state.value.contactReview)
    }

    fun testStaleSnapshotOrNativeFailureReportsErrorWithoutSuccess() {
        repository.save(
            savedDraft.copy(values = savedDraft.values + (ProfileField.FullName to "Changed Outside")),
            model.state.value.saved!!.token,
        )
        run { model.shareContact { fail("A stale profile must not be handed off") } }
        await { !it.exporting && it.contactError != null }
        assertFalse(model.state.value.handoffNotice)

        run { model.reload() }
        await { !it.loading && !it.qrLoading }
        run { model.shareContact { throw ContactExportException("Android denied contact sharing.") } }
        await { !it.exporting && it.contactError != null }
        assertFalse(model.state.value.handoffNotice)
        assertEquals("Android denied contact sharing.", model.state.value.contactError)
    }
}
