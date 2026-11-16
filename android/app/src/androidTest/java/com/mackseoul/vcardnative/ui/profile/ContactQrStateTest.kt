package com.mackseoul.vcardnative.ui.profile

import android.test.InstrumentationTestCase
import androidx.lifecycle.ViewModelStore
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.Decoder
import com.mackseoul.vcardnative.data.profile.ProfileRepository
import com.mackseoul.vcardnative.domain.images.ImageException
import com.mackseoul.vcardnative.domain.profile.*
import com.mackseoul.vcardnative.domain.sharing.QrFailure
import com.mackseoul.vcardnative.platform.storage.AndroidStoreFiles
import java.io.File
import java.util.UUID

@Suppress("DEPRECATION")
class ContactQrStateTest : InstrumentationTestCase() {
    private lateinit var root: File
    private lateinit var repository: ProfileRepository
    private lateinit var model: ProfileViewModel
    private val store = ViewModelStore()
    private val draft = ProfileDraft.empty().copy(
        values = mapOf(ProfileField.FullName to "Saved QR", ProfileField.Email to "private@example.invalid"),
    )

    override fun setUp() {
        super.setUp()
        root = File(instrumentation.targetContext.noBackupFilesDir, "qr-test-${UUID.randomUUID()}")
        repository = ProfileRepository(AndroidStoreFiles(root))
        repository.save(draft, null)
        createModel()
    }

    private fun createModel() {
        run {
            model = ProfileViewModel({ repository }, { _, _ -> throw ImageException("Unused test reader") }, {})
            store.put("qr", model)
        }
        await { !it.loading }
    }

    override fun tearDown() {
        run { store.clear() }
        root.deleteRecursively()
        super.tearDown()
    }

    private fun run(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(condition: (ProfileScreenState) -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition(model.state.value)) {
            if (System.nanoTime() > deadline) fail("Saved QR state did not settle")
            Thread.sleep(20)
        }
    }
    private fun openHome() {
        run { model.select(ProfileDestination.Home) }
        await { !it.qrLoading }
    }
    private fun decoded(): String {
        val qr = requireNotNull(model.state.value.contactQr)
        return Decoder().decode(BitMatrix(qr.side).apply {
            for (y in 0 until qr.side) for (x in 0 until qr.side) if (qr.isBlack(x, y)) set(x, y)
        }).text
    }

    fun testImmediateQrNoReviewPrivacyUnsavedDiscardAndSavedRefresh() {
        assertEquals(ProfileDestination.Home, model.state.value.destination)
        openHome()
        assertNull(model.state.value.contactReview)
        assertTrue(decoded().contains("FN:Saved QR"))
        assertFalse(decoded().contains("private@example.invalid"))
        run {
            model.editExportSelections()
            model.edit(ProfileField.FullName, "Unsaved Secret")
            model.privacy(ProfileField.Email, Visibility(false, true))
            model.requestBack {}
            model.leaveEditor {}
        }
        await { !it.qrLoading }
        assertEquals(ProfileDestination.Home, model.state.value.destination)
        assertFalse(decoded().contains("Unsaved") || decoded().contains("private@example.invalid"))
        run {
            model.editExportSelections()
            model.edit(ProfileField.FullName, "Zoë 😀 Saved")
            model.privacy(ProfileField.Email, Visibility(false, true))
            model.save()
        }
        await { !it.saving && !it.qrLoading && it.destination == ProfileDestination.Home }
        assertTrue(decoded().contains("FN:Zoë 😀 Saved"))
        assertTrue(decoded().contains("EMAIL;TYPE=INTERNET:private@example.invalid"))
        assertNull(model.state.value.contactReview)
    }

    fun testMyCardShowQrUsesSeparatePresentationAndDoneReturnsToMyCard() {
        val savedBefore = model.state.value.saved
        run {
            model.select(ProfileDestination.MyCard)
            model.showCardQr()
        }
        await { it.destination == ProfileDestination.QrPresentation && !it.qrLoading }
        assertTrue(decoded().contains("FN:Saved QR"))
        assertEquals(savedBefore, model.state.value.saved)

        run { model.requestBack { fail("Normal in-app QR must not exit the activity") } }
        assertEquals(ProfileDestination.MyCard, model.state.value.destination)
        run {
            model.showCardQr()
            model.closeQrPresentation { fail("Normal in-app QR Done must return to My Card") }
        }
        assertEquals(ProfileDestination.MyCard, model.state.value.destination)
        assertEquals(savedBefore, model.state.value.saved)
    }

    fun testShareOpenContactQrMatchesInlineQrAndBackOrDoneReturnsToShare() {
        val savedBefore = model.state.value.saved
        openHome()
        val inlineQr = decoded()
        run { model.showShareQr() }
        await { it.destination == ProfileDestination.QrPresentation && !it.qrLoading }
        assertTrue(model.state.value.shareQrOrigin)
        assertFalse(model.state.value.shortcutQrOrigin)
        assertEquals(inlineQr, decoded())

        run { model.requestBack { fail("Share-origin QR Back must not exit the activity") } }
        assertEquals(ProfileDestination.Home, model.state.value.destination)
        assertFalse(model.state.value.shareQrOrigin)
        assertEquals(inlineQr, decoded())

        run { model.showShareQr() }
        await { it.destination == ProfileDestination.QrPresentation && !it.qrLoading }
        run { model.closeQrPresentation { fail("Share-origin QR Done must not exit the activity") } }
        await { it.destination == ProfileDestination.Home && !it.qrLoading }
        assertFalse(model.state.value.shareQrOrigin)
        assertEquals(inlineQr, decoded())
        assertEquals(savedBefore, model.state.value.saved)

        run {
            model.showShareQr()
            model.select(ProfileDestination.MyCard)
            model.showCardQr()
        }
        await { it.destination == ProfileDestination.QrPresentation && !it.qrLoading }
        assertFalse(model.state.value.shareQrOrigin)
        run { model.closeQrPresentation { fail("My Card QR Done must not exit the activity") } }
        assertEquals(ProfileDestination.MyCard, model.state.value.destination)
    }

    fun testShareOpenContactQrIgnoredOutsideShareOrWithoutReadyQr() {
        run {
            model.select(ProfileDestination.MyCard)
            model.showShareQr()
        }
        assertEquals(ProfileDestination.MyCard, model.state.value.destination)
        assertFalse(model.state.value.shareQrOrigin)
        run { model.select(ProfileDestination.Profile) }
        run { model.showShareQr() }
        assertEquals(ProfileDestination.Profile, model.state.value.destination)
        assertFalse(model.state.value.shareQrOrigin)

        openHome()
        run {
            model.reviewContact()
            model.showShareQr()
        }
        assertEquals(ProfileDestination.Home, model.state.value.destination)
        assertFalse(model.state.value.shareQrOrigin)
        await { it.contactReview != null && !it.busy }
        run { model.showShareQr() }
        assertEquals(ProfileDestination.Home, model.state.value.destination)
        assertFalse(model.state.value.shareQrOrigin)
        run { model.dismissContactReview() }

        repository = ProfileRepository(AndroidStoreFiles(File(root, "empty")))
        createModel()
        run { model.showShareQr() }
        assertTrue(model.state.value.destination != ProfileDestination.QrPresentation)
        assertFalse(model.state.value.shareQrOrigin)
    }

    fun testShortcutAfterShareQrStillExits() {
        openHome()
        run { model.showShareQr() }
        await { it.destination == ProfileDestination.QrPresentation && !it.qrLoading }
        run { model.requestPrimaryQrFromShortcut() }
        await { it.destination == ProfileDestination.QrPresentation && !it.qrLoading }
        assertTrue(model.state.value.shortcutQrOrigin)
        assertFalse(model.state.value.shareQrOrigin)
        var exited = false
        run { model.requestBack { exited = true } }
        assertTrue(exited)
        exited = false
        run { model.closeQrPresentation { exited = true } }
        assertTrue(exited)
    }

    fun testShortcutWaitsForColdLoadDeduplicatesAndMatchesPrimaryQr() {
        openHome()
        val primaryQr = decoded()
        run {
            store.clear()
            model = ProfileViewModel({ repository }, { _, _ -> throw ImageException("Unused test reader") }, {})
            store.put("qr", model)
            model.requestPrimaryQrFromShortcut()
            model.requestPrimaryQrFromShortcut()
        }
        await { !it.loading }
        run { model.resolvePendingShortcutQr() }
        await { it.destination == ProfileDestination.QrPresentation && !it.qrLoading }
        assertTrue(model.state.value.shortcutQrOrigin)
        assertEquals(primaryQr, decoded())
        assertNull(model.state.value.quickShareSession)

        var exited = false
        run { model.requestBack { exited = true } }
        assertTrue(exited)
        exited = false
        run { model.closeQrPresentation { exited = true } }
        assertTrue(exited)
        assertEquals(ProfileDestination.QrPresentation, model.state.value.destination)
    }

    fun testShortcutFromCleanEditorOpensContactQrWithoutConfirmation() {
        val savedBefore = (repository.load() as com.mackseoul.vcardnative.data.profile.ProfileRead.Ready).saved
        run {
            model.select(ProfileDestination.MyCard)
            model.select(ProfileDestination.Editor)
            model.requestPrimaryQrFromShortcut()
        }
        await { it.destination == ProfileDestination.QrPresentation && !it.qrLoading }
        assertFalse(model.state.value.confirmDiscard)
        assertEquals(savedBefore, (repository.load() as com.mackseoul.vcardnative.data.profile.ProfileRead.Ready).saved)
        assertTrue(decoded().contains("FN:Saved QR"))
    }

    fun testShortcutUsesDiscardConfirmationAndNeverEncodesUnsavedDraft() {
        val savedBefore = (repository.load() as com.mackseoul.vcardnative.data.profile.ProfileRead.Ready).saved
        run {
            model.select(ProfileDestination.MyCard)
            model.select(ProfileDestination.Editor)
            model.edit(ProfileField.FullName, "Unsaved Shortcut Name")
            model.requestPrimaryQrFromShortcut()
        }
        assertTrue(model.state.value.confirmDiscard)
        assertEquals(ProfileDestination.Editor, model.state.value.destination)
        assertEquals(savedBefore, (repository.load() as com.mackseoul.vcardnative.data.profile.ProfileRead.Ready).saved)

        run { model.keepEditing() }
        assertEquals("Unsaved Shortcut Name", model.state.value.draft!!.values[ProfileField.FullName])
        assertEquals(ProfileDestination.Editor, model.state.value.destination)
        assertFalse(model.state.value.pendingShortcutQr)

        run {
            model.requestPrimaryQrFromShortcut()
            model.leaveEditor {}
        }
        await { it.destination == ProfileDestination.QrPresentation && !it.qrLoading }
        assertTrue(decoded().contains("FN:Saved QR"))
        assertFalse(decoded().contains("Unsaved Shortcut Name"))
        assertEquals(savedBefore, (repository.load() as com.mackseoul.vcardnative.data.profile.ProfileRead.Ready).saved)
    }

    fun testShortcutConfirmsDirtyNestedEditorsAndDiscardsOnlyTheirDraft() {
        val savedBefore = (repository.load() as com.mackseoul.vcardnative.data.profile.ProfileRead.Ready).saved
        val nestedDestinations = listOf(
            ProfileDestination.HostedProfile,
            ProfileDestination.Visibility,
            ProfileDestination.Security,
            ProfileDestination.ProfileImages,
        )
        for (destination in nestedDestinations) {
            run {
                model.select(ProfileDestination.MyCard)
                model.select(ProfileDestination.Editor)
                model.edit(ProfileField.Organization, "Unsaved ${destination.name}")
                if (destination == ProfileDestination.HostedProfile) {
                    model.edit(ProfileField.HostedProfileUrl, "https://example.invalid/unsaved")
                }
                model.openEditorSettingsFromEditor(destination)
                model.requestPrimaryQrFromShortcut()
            }
            assertTrue("Missing confirmation for $destination", model.state.value.confirmDiscard)
            assertEquals(destination, model.state.value.destination)
            val preservedDraft = model.state.value.draft
            run { model.keepEditing() }
            assertFalse(model.state.value.confirmDiscard)
            assertEquals(destination, model.state.value.destination)
            assertEquals(preservedDraft, model.state.value.draft)
            assertFalse(model.state.value.pendingShortcutQr)

            run {
                model.requestPrimaryQrFromShortcut()
                model.leaveEditor {}
            }
            await { it.destination == ProfileDestination.QrPresentation && !it.qrLoading }
            assertTrue(decoded().contains("FN:Saved QR"))
            assertFalse(decoded().contains("Unsaved ${destination.name}") ||
                decoded().contains("https://example.invalid/unsaved"))
            assertEquals(savedBefore, (repository.load() as com.mackseoul.vcardnative.data.profile.ProfileRead.Ready).saved)
        }
    }

    fun testShortcutKeepsEmptySetupAndRecoveryPrecedence() {
        run { store.clear() }
        val empty = root.resolveSibling("qr-shortcut-empty-${UUID.randomUUID()}")
        try {
            repository = ProfileRepository(AndroidStoreFiles(empty))
            createModel()
            run { model.requestPrimaryQrFromShortcut() }
            assertEquals(ProfileDestination.Welcome, model.state.value.destination)
            assertFalse(model.state.value.pendingShortcutQr)
            assertNull(model.state.value.contactQr)
        } finally {
            run { store.clear() }
            empty.deleteRecursively()
        }

        repository = ProfileRepository(AndroidStoreFiles(root))
        root.resolve("PENDING").writeText("uncertain")
        createModel()
        assertNotNull(model.state.value.unavailable)
        run { model.requestPrimaryQrFromShortcut() }
        assertEquals(ProfileDestination.Profile, model.state.value.destination)
        assertFalse(model.state.value.pendingShortcutQr)
        assertFalse(model.state.value.shortcutQrOrigin)
    }

    fun testOversizedSaveRemovesOldQrButKeepsFullVcfAndIncludedSummary() {
        openHome()
        assertNotNull(model.state.value.contactQr)
        run {
            model.editExportSelections()
            model.edit(ProfileField.Biography, "😀".repeat(1200))
            model.privacy(ProfileField.Biography, Visibility(false, true))
            model.save()
        }
        await { !it.saving && !it.qrLoading && it.destination == ProfileDestination.Home }
        assertNull(model.state.value.contactQr)
        assertEquals(QrFailure.PayloadLimit, model.state.value.qrFailure)
        assertTrue(model.state.value.contactExport!!.bytes.size > 4096)
        assertTrue(model.state.value.contactExport!!.rows.any { ProfileField.Biography in it.fields })
        assertNull(model.state.value.contactError)
    }

    fun testStaleSavedSnapshotAndCorruptStoreCannotShowOldQr() {
        openHome()
        run { model.select(ProfileDestination.MyCard) }
        repository.save(draft.copy(values = draft.values + (ProfileField.FullName to "Changed Outside")),
            model.state.value.saved!!.token)
        openHome()
        assertNull(model.state.value.contactQr)
        assertNull(model.state.value.contactExport)
        assertNotNull(model.state.value.contactError)
        run { model.reload() }
        await { !it.loading && !it.qrLoading }
        assertTrue(decoded().contains("Changed Outside"))
        root.resolve("PENDING").writeText("uncertain")
        run { model.select(ProfileDestination.MyCard) }
        openHome()
        assertNull(model.state.value.contactQr)
        assertNull(model.state.value.contactExport)
        assertNotNull(model.state.value.contactError)
    }

    fun testUninitializedStoreRemainsFirstRunWithNoPayloadOrQr() {
        run { store.clear() }
        val empty = root.resolveSibling("qr-empty-${UUID.randomUUID()}")
        try {
            repository = ProfileRepository(AndroidStoreFiles(empty))
            createModel()
            assertTrue(model.state.value.firstRun)
            assertEquals(ProfileDestination.Welcome, model.state.value.destination)
            assertNull(model.state.value.contactQr)
            assertNull(model.state.value.contactExport)
            run { model.requestPrimaryQrFromShortcut() }
            assertEquals(ProfileDestination.Welcome, model.state.value.destination)
            assertFalse(model.state.value.shortcutQrOrigin)
            assertNull(model.state.value.contactQr)
        } finally {
            run { store.clear() }
            empty.deleteRecursively()
        }
    }
}
