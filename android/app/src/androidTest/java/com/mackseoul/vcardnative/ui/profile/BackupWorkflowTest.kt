package com.mackseoul.vcardnative.ui.profile

import android.graphics.Bitmap
import android.net.Uri
import android.test.InstrumentationTestCase
import androidx.lifecycle.ViewModelStore
import com.mackseoul.vcardnative.data.backup.BackupArchive
import com.mackseoul.vcardnative.data.backup.BackupStaging
import com.mackseoul.vcardnative.data.keys.PublicKeyValidator
import com.mackseoul.vcardnative.data.profile.ProfileRead
import com.mackseoul.vcardnative.data.profile.ProfileRepository
import com.mackseoul.vcardnative.domain.images.ImageException
import com.mackseoul.vcardnative.domain.images.ImageStructure
import com.mackseoul.vcardnative.domain.profile.*
import com.mackseoul.vcardnative.domain.sharing.ContactExport
import com.mackseoul.vcardnative.platform.storage.AndroidStoreFiles
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@Suppress("DEPRECATION")
class BackupWorkflowTest : InstrumentationTestCase() {
    private lateinit var root: File
    private lateinit var repository: ProfileRepository
    private lateinit var staging: BackupStaging
    private lateinit var model: ProfileViewModel
    private val models = ViewModelStore()
    private var input = ByteArray(0)
    private var output: ByteArray? = null
    private val uri = Uri.parse("content://test/backup")

    override fun setUp() {
        super.setUp()
        root = File(instrumentation.targetContext.noBackupFilesDir, "backup-workflow-test-${UUID.randomUUID()}")
        repository = ProfileRepository(AndroidStoreFiles(root.resolve("store")))
        staging = BackupStaging(root.resolve("stage"))
        repository.save(plain("Local Original"), null)
        createModel()
    }
    private fun plain(name: String) = ProfileDraft.empty().copy(values = mapOf(ProfileField.FullName to name))
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun createModel() {
        main {
            model = ProfileViewModel({ repository }, { _, _ -> throw ImageException("Unused test reader") }, {},
                backupStaging = staging,
                readSelectedBackup = { input.inputStream().use(staging::stage) },
                writeSelectedBackup = { _, stage -> output = staging.read(stage) })
            models.put("backup", model)
        }
        await { !it.loading }
    }
    private fun await(condition: (ProfileScreenState) -> Boolean) {
        val deadline = System.nanoTime() + 60_000_000_000L
        while (!condition(model.state.value)) {
            if (System.nanoTime() > deadline) fail("Backup workflow did not settle: ${model.state.value.backupError}")
            Thread.sleep(20)
        }
    }
    private fun fullBackup(): ByteArray {
        val source = ProfileRepository(AndroidStoreFiles(root.resolve("source-${UUID.randomUUID()}")))
        val bitmap = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        val bytes = ByteArrayOutputStream().use { out ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            bitmap.recycle()
            out.toByteArray()
        }
        val portrait = DraftImage(ImageAsset(UUID.randomUUID(), "image/png", bytes.size.toLong(), 3, 3,
            ImageStructure.digest(bytes)), bytes)
        val cover = DraftImage(portrait.asset.copy(assetId = UUID.randomUUID()), bytes)
        val key = PublicKeyValidator.import(instrumentation.context.assets.open("openpgp/valid-public.asc").use { it.readBytes() })
        source.save(plain("Complete Imported").copy(
            values = mapOf(ProfileField.FullName to "Complete Imported", ProfileField.Biography to "Hidden backup value"),
            visibility = ProfileDraft.empty().visibility + (ProfileField.Biography to Visibility(false, false)) +
                (ProfileField.PublicKey to Visibility(false, null)),
            profileImage = portrait.asset, coverImage = cover.asset,
            pendingImages = mapOf(ImageRole.Portrait to portrait, ImageRole.Cover to cover),
            publicKey = key.asset, pendingPublicKey = key, appearance = Appearance(Theme.light, Accent.jade),
        ), null)
        return BackupArchive.create(source.backupSnapshot())
    }
    private fun openBackup() = main { model.select(ProfileDestination.Profile); model.openBackup() }
    private fun preview(bytes: ByteArray = fullBackup()) {
        input = bytes
        main { assertTrue(model.startRestorePicker()); model.restoreSelectionResult(uri) }
        await { !it.busy }
    }
    override fun tearDown() {
        main { models.clear() }
        root.deleteRecursively()
        super.tearDown()
    }

    fun testCompletePreviewExplicitReplacementFullAssetsReloadAndContactPrivacy() {
        val original = model.state.value.saved!!
        openBackup()
        val archive = fullBackup()
        preview(archive)
        assertNull(model.state.value.backupError)
        val candidate = model.state.value.backupReview!!.candidate
        assertEquals(original, (repository.load() as ProfileRead.Ready).saved)
        assertEquals("Local Original", model.state.value.backupReview!!.replacingProfileName)
        main { model.confirmRestore() }
        await { !it.busy }
        assertNull(model.state.value.backupError)
        val restored = model.state.value.saved!!
        assertEquals(ProfileDestination.Home, model.state.value.destination)
        assertEquals(candidate.data, restored.data)
        assertEquals(1L, restored.token.revision)
        assertFalse(original.token.generationId == restored.token.generationId)
        assertEquals(2, model.state.value.savedImages.size)
        assertTrue(model.state.value.keyStatus!!.usable)
        assertNull(restored.data.toMyCard(model.state.value.keyStatus).profile!!.publicKey)
        assertFalse(ContactExport.from(restored.data.profile).bytes.toString(Charsets.UTF_8).contains("Hidden backup value"))
        assertTrue(candidate.publicKey!!.contentEquals(repository.readPublicKey(restored.data.profile.publicKey!!)))
        assertTrue(root.resolve("stage").listFiles()!!.isEmpty())
        main { models.clear() }
        createModel()
        assertEquals(restored, model.state.value.saved)
        assertEquals(2, model.state.value.savedImages.size)
    }

    fun testPreviewCancelAndPickerCancelLeaveOriginalUnchanged() {
        val original = model.state.value.saved!!
        openBackup()
        main { assertTrue(model.startRestorePicker()); model.restoreSelectionResult(null) }
        assertNull(model.state.value.backupReview)
        preview()
        main { model.requestBack {} }
        await { !it.busy }
        assertNull(model.state.value.backupReview)
        assertEquals(original, (repository.load() as ProfileRead.Ready).saved)
        assertEquals(ProfileDestination.Backup, model.state.value.destination)
        assertTrue(root.resolve("stage").listFiles()!!.isEmpty())
    }

    fun testStaleConfirmationRefreshesContextAndRequiresAnotherExplicitConfirmation() {
        openBackup()
        preview()
        val original = model.state.value.saved!!
        val newer = repository.save(plain("Local Changed Since Review"), original.token)
        main { model.confirmRestore() }
        await { !it.busy }
        assertEquals(newer, (repository.load() as ProfileRead.Ready).saved)
        assertNotNull(model.state.value.backupReview)
        assertEquals(newer.token, model.state.value.backupReview!!.expected)
        assertEquals("Local Changed Since Review", model.state.value.backupReview!!.replacingProfileName)
        assertNotNull(model.state.value.backupError)
        main { model.confirmRestore() }
        await { !it.busy }
        assertNull(model.state.value.backupReview)
        assertEquals("Complete Imported", model.state.value.saved!!.data.value(ProfileField.FullName))
    }

    fun testMalformedOversizedAndChangedStagingNeverReplaceOriginal() {
        val original = model.state.value.saved!!
        openBackup()
        preview("not a ZIP".toByteArray())
        assertNotNull(model.state.value.backupError)
        assertNull(model.state.value.backupReview)
        preview(ByteArray(BackupStaging.MAX_BYTES.toInt() + 1))
        assertNotNull(model.state.value.backupError)
        assertNull(model.state.value.backupReview)
        preview()
        val stage = model.state.value.backupReview!!.stage
        root.resolve("stage").resolve("${stage.id}.archive").writeBytes(byteArrayOf(1))
        main { model.confirmRestore() }
        await { !it.busy }
        assertNotNull(model.state.value.backupError)
        assertEquals(original, (repository.load() as ProfileRead.Ready).saved)
    }

    fun testBackupSnapshotOutputAndCancelledDestinationNeverIncludeDiscardedDraft() {
        val original = model.state.value.saved!!
        main {
            model.select(ProfileDestination.Profile)
            model.select(ProfileDestination.Editor)
            model.edit(ProfileField.FullName, "Unsaved Must Not Export")
            model.requestBack {}
            model.leaveEditor {}
            model.openBackup()
            model.createBackup()
        }
        await { it.backupOperation == BackupOperation.ReadyForDestination }
        main { assertTrue(model.consumeBackupDestination()!!.endsWith(".vcardnative.zip")); model.backupDestinationResult(null) }
        await { !it.busy }
        assertNull(output)
        main { model.createBackup() }
        await { it.backupOperation == BackupOperation.ReadyForDestination }
        main { model.consumeBackupDestination(); model.backupDestinationResult(uri) }
        await { !it.busy }
        assertNull(model.state.value.backupError)
        assertEquals(original.data, BackupArchive.validate(requireNotNull(output)).data)
        assertEquals(original, (repository.load() as ProfileRead.Ready).saved)
    }

    fun testFirstRunRestoreRoutePreservesDraftAndValidRestoreOpensMyCard() {
        main { models.clear() }
        repository = ProfileRepository(AndroidStoreFiles(root.resolve("first-run")))
        createModel()
        assertTrue(model.state.value.firstRun)
        assertEquals(ProfileDestination.Welcome, model.state.value.destination)
        main {
            model.beginProfileSetup()
            model.edit(ProfileField.FullName, "Unsaved first run")
            model.advanceProfileSetup()
            model.advanceProfileSetup()
            model.previousProfileSetup()
            model.previousProfileSetup()
            model.previousProfileSetup()
        }
        assertEquals(ProfileDestination.Welcome, model.state.value.destination)
        assertEquals("Unsaved first run", model.state.value.draft!!.values[ProfileField.FullName])
        main { model.openBackup() }
        assertEquals(ProfileDestination.Backup, model.state.value.destination)
        assertEquals(ProfileRead.Empty, repository.load())
        main { model.requestBack {} }
        assertEquals(ProfileDestination.Welcome, model.state.value.destination)
        assertEquals("Unsaved first run", model.state.value.draft!!.values[ProfileField.FullName])
        main { model.openBackup() }
        preview()
        main { model.confirmRestore() }
        await { !it.busy }
        assertEquals(ProfileDestination.Home, model.state.value.destination)
        assertEquals("Complete Imported", model.state.value.saved!!.data.value(ProfileField.FullName))
    }

    fun testRuntimeFixtureHasCompleteValidatedAssetInventory() {
        val bytes = fullBackup()
        val validated = BackupArchive.validate(bytes)
        assertEquals(2, validated.images.size)
        assertNotNull(validated.publicKey)
        instrumentation.targetContext.cacheDir.resolve("backup-runtime-fixture.vcardnative.zip").writeBytes(bytes)
    }
}
