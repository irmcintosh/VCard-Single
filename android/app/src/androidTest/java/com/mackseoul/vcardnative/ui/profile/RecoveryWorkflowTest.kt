package com.mackseoul.vcardnative.ui.profile

import android.net.Uri
import android.graphics.Bitmap
import android.test.InstrumentationTestCase
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import com.mackseoul.vcardnative.MainActivity
import com.mackseoul.vcardnative.data.backup.BackupArchive
import com.mackseoul.vcardnative.data.backup.BackupStaging
import com.mackseoul.vcardnative.data.profile.ProfileRead
import com.mackseoul.vcardnative.data.profile.ProfileRepository
import com.mackseoul.vcardnative.data.profile.StoreFiles
import com.mackseoul.vcardnative.domain.images.ImageException
import com.mackseoul.vcardnative.domain.profile.ProfileDraft
import com.mackseoul.vcardnative.domain.profile.ProfileField
import com.mackseoul.vcardnative.platform.storage.AndroidStoreFiles
import com.mackseoul.vcardnative.ui.theme.VCardTheme
import java.io.File
import java.util.UUID

@Suppress("DEPRECATION")
class RecoveryWorkflowTest : InstrumentationTestCase() {
    private lateinit var root: File
    private lateinit var repository: ProfileRepository
    private lateinit var staging: BackupStaging
    private lateinit var model: ProfileViewModel
    private val models = ViewModelStore()
    private var input = ByteArray(0)
    private val uri = Uri.parse("content://test/recovery")

    override fun setUp() {
        super.setUp()
        root = File(instrumentation.targetContext.noBackupFilesDir, "recovery-workflow-${UUID.randomUUID()}")
        repository = ProfileRepository.managed(AndroidStoreFiles(root))
        staging = BackupStaging(root.resolve("backup-work"))
        repository.save(draft("Unavailable Original"), null)
        root.resolve("store-v1").resolve("PENDING").writeText("interrupted")
        createModel()
    }

    override fun tearDown() {
        main { models.clear() }
        root.deleteRecursively()
        super.tearDown()
    }

    private fun draft(name: String) = ProfileDraft.empty().copy(
        values = mapOf(ProfileField.FullName to name),
    )

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun await(condition: (ProfileScreenState) -> Boolean) {
        val deadline = System.nanoTime() + 60_000_000_000L
        while (!condition(model.state.value)) {
            if (System.nanoTime() > deadline) fail("Recovery did not settle: ${model.state.value.backupError}")
            Thread.sleep(20)
        }
    }

    private fun createModel() {
        main {
            model = ProfileViewModel(
                { repository }, { _, _ -> throw ImageException("Unused test reader") }, {},
                backupStaging = staging,
                readSelectedBackup = { input.inputStream().use(staging::stage) },
            )
            models.put("recovery", model)
        }
        await { !it.loading }
    }

    private fun backup(): ByteArray {
        val source = ProfileRepository(AndroidStoreFiles(root.resolve("test-source")))
        if (source.load() == ProfileRead.Empty) source.save(draft("Verified Replacement"), null)
        return BackupArchive.create(source.backupSnapshot())
    }

    private fun preview(bytes: ByteArray) {
        input = bytes
        main {
            model.openBackup()
            assertTrue(model.startRestorePicker())
            model.restoreSelectionResult(uri)
        }
        await { !it.busy }
    }

    fun testUnavailableIsNotEmptyValidRecoveryNeedsConfirmationAndRefreshesSavedState() {
        assertFalse(model.state.value.firstRun)
        assertNull(model.state.value.saved)
        assertNotNull(model.state.value.unavailable)
        assertNotNull(model.state.value.recoveryToken)
        val original = snapshot(root.resolve("store-v1"))
        preview(backup())
        val review = model.state.value.backupReview!!
        assertNotNull(review.recoveryToken)
        assertNull(review.expected)
        assertTrue(repository.load() is ProfileRead.Unavailable)
        assertSnapshots(original, snapshot(root.resolve("store-v1")))

        main { model.confirmRestore() }
        await { !it.busy }
        assertNull(model.state.value.backupError)
        assertNull(model.state.value.unavailable)
        assertEquals("Verified Replacement", model.state.value.saved!!.data.value(ProfileField.FullName))
        assertEquals(1L, model.state.value.saved!!.token.revision)
        assertTrue(model.state.value.backupNotice!!.contains("retained privately"))
        val evidence = root.resolve("profile-operations").walkTopDown()
            .filter { it.isDirectory && it.resolve("INIT").isFile && it.resolve("PENDING").isFile }.toList()
        assertEquals(1, evidence.size)
        assertSnapshots(original, snapshot(evidence.single()))
        main { models.clear() }
        createModel()
        assertEquals("Verified Replacement", model.state.value.saved!!.data.value(ProfileField.FullName))
    }

    fun testInvalidCancelledAndModifiedBackupPreserveUnavailableBytesAndPermitRetry() {
        val original = snapshot(root.resolve("store-v1"))
        preview("invalid".toByteArray())
        assertNotNull(model.state.value.backupError)
        assertNull(model.state.value.backupReview)
        assertSnapshots(original, snapshot(root.resolve("store-v1")))

        preview(backup())
        main { model.dismissRestoreReview() }
        await { !it.busy }
        assertNull(model.state.value.backupReview)
        assertSnapshots(original, snapshot(root.resolve("store-v1")))

        preview(backup())
        val stage = model.state.value.backupReview!!.stage
        root.resolve("backup-work").resolve("${stage.id}.archive").writeBytes(byteArrayOf(1))
        main { model.confirmRestore() }
        await { !it.busy }
        assertNotNull(model.state.value.backupError)
        assertTrue(repository.load() is ProfileRead.Unavailable)
        assertSnapshots(original, snapshot(root.resolve("store-v1")))

        main { assertTrue(model.startRestorePicker()); model.restoreSelectionResult(null) }
        assertNull(model.state.value.backupReview)
        assertSnapshots(original, snapshot(root.resolve("store-v1")))
    }

    fun testPendingDeletionBlocksProfileAndRetryCompletesTheConfirmedOperation() {
        main { models.clear() }
        val store = AndroidStoreFiles(root.resolve("delete-test"))
        var denyDeletion = true
        val failing = object : StoreFiles by store {
            override fun delete(path: String) {
                if (denyDeletion && path.startsWith("profile-operations\\trash\\")) throw java.io.IOException("Injected deletion denial")
                store.delete(path)
            }
        }
        repository = ProfileRepository.managed(failing)
        repository.save(draft("Delete Fixture"), null)
        createModel()
        main {
            model.select(ProfileDestination.Profile)
            model.requestDeleteProfile()
        }
        assertTrue(model.state.value.confirmDeleteProfile)
        main { model.cancelDeleteProfile() }
        assertTrue(repository.load() is ProfileRead.Ready)
        main { model.requestDeleteProfile(); model.deleteProfile() }
        await { !it.deletingProfile && it.pendingOperation != null }
        assertEquals("delete", model.state.value.pendingOperation)
        assertFalse(model.state.value.firstRun)
        assertNull(model.state.value.saved)
        assertNull(model.state.value.draft)
        main { model.openBackup() }
        assertEquals(ProfileDestination.Profile, model.state.value.destination)
        assertFalse(model.startRestorePicker())
        renderAtLargeFontScale("Retry operation", "Cancel / Leave", "pending-deletion")
        denyDeletion = false
        main { model.reload() }
        await { !it.loading }
        assertTrue(model.state.value.firstRun)
        assertEquals(ProfileDestination.Welcome, model.state.value.destination)
        assertEquals(ProfileRead.Empty, repository.load())
    }

    fun testInterruptedSaveClearsStaleEditorAndExposesExplicitRecovery() {
        main { models.clear() }
        val store = AndroidStoreFiles(root.resolve("save-test"))
        var interruptSave = false
        val failing = object : StoreFiles by store {
            override fun syncFile(path: String) {
                store.syncFile(path)
                if (interruptSave && path == "store-v1\\ptr\\slot-1") {
                    throw java.io.IOException("Injected pointer sync interruption")
                }
            }
        }
        repository = ProfileRepository.managed(failing)
        repository.save(draft("Saved Original"), null)
        createModel()
        main {
            model.select(ProfileDestination.Profile)
            model.select(ProfileDestination.Editor)
            model.edit(ProfileField.FullName, "Interrupted Edit")
        }
        interruptSave = true
        main { model.save() }
        await { !it.saving }
        assertNotNull(model.state.value.unavailable)
        assertNotNull(model.state.value.recoveryToken)
        assertEquals("Saved Original", model.state.value.saved!!.data.value(ProfileField.FullName))
        assertEquals("Interrupted Edit", model.state.value.draft!!.values[ProfileField.FullName])
        assertEquals(ProfileDestination.Profile, model.state.value.destination)
        assertFalse(model.state.value.firstRun)
    }

    fun testCorruptedManifestOffersRecoveryAndPreservesExactEvidence() {
        main { models.clear() }
        val live = root.resolve("store-v1")
        assertTrue(live.resolve("PENDING").delete())
        val manifest = live.resolve("gen").walkTopDown().first { it.isFile }
        manifest.writeBytes(byteArrayOf(1))
        val damaged = snapshot(live)
        createModel()
        assertNotNull(model.state.value.recoveryToken)
        preview(backup())
        main { model.confirmRestore() }
        await { !it.busy }
        assertNull(model.state.value.backupError)
        assertEquals("Verified Replacement", model.state.value.saved!!.data.value(ProfileField.FullName))
        val evidence = root.resolve("profile-operations").walkTopDown()
            .filter { it.isDirectory && it.resolve("INIT").isFile && it.resolve("gen").isDirectory }.toList()
        assertEquals(1, evidence.size)
        assertSnapshots(damaged, snapshot(evidence.single()))
    }

    fun testRecoveryConfirmationAndCancelAreReachableAtTwoHundredPercentFontScale() {
        renderAtLargeFontScale("Restore application backup", "Cancel / Leave", "unavailable")
        preview(backup())
        renderAtLargeFontScale("Recover from this backup", "Cancel", "recovery")
    }

    private fun renderAtLargeFontScale(primary: String, secondary: String, label: String) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        try {
            main {
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
                activity.setContent {
                    val state by model.state.collectAsState()
                    val density = LocalDensity.current.density
                    CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                        VCardTheme {
                            ProfileApplication(state, model, {}, {}, {}, { _, _ -> }, {})
                        }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            assertReachable(primary)
            assertReachable(secondary)
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            assertNotNull(screenshot)
            try {
                instrumentation.targetContext.cacheDir.resolve("m111-$label-200-percent.png").outputStream().use {
                    assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { screenshot.recycle() }
        } finally { main { activity.finish() } }
    }

    private fun assertReachable(text: String) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            val window = instrumentation.uiAutomation.rootInActiveWindow
            if (window != null) {
                if (hasVisibleText(window, text)) return
                scroll(window)
            }
            Thread.sleep(200)
        }
        val window = instrumentation.uiAutomation.rootInActiveWindow
        fail("$text is unreachable at 200% font scale. Window: ${window?.packageName}; nodes: ${window?.let(::nodeText)}")
    }

    private fun nodeText(node: AccessibilityNodeInfo): String = buildString {
        append(node.text); append(' ')
        for (index in 0 until node.childCount) node.getChild(index)?.let { append(nodeText(it)) }
    }

    private fun hasVisibleText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (node.text?.toString() == text && node.isVisibleToUser) return true
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            if (hasVisibleText(child, text)) return true
        }
        return false
    }

    private fun scroll(node: AccessibilityNodeInfo): Boolean {
        if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            if (scroll(child)) return true
        }
        return false
    }

    private fun snapshot(directory: File) = directory.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(directory).path to it.readBytes()
    }

    private fun assertSnapshots(expected: Map<String, ByteArray>, actual: Map<String, ByteArray>) {
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (path, bytes) -> assertTrue(bytes.contentEquals(actual.getValue(path))) }
    }
}
