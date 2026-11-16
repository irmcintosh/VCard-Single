package com.mackseoul.vcardnative.ui.profile

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import com.mackseoul.vcardnative.MainActivity
import com.mackseoul.vcardnative.data.profile.ProfileRead
import com.mackseoul.vcardnative.data.profile.ProfileRepository
import com.mackseoul.vcardnative.domain.profile.ProfileField
import com.mackseoul.vcardnative.domain.profile.Visibility
import com.mackseoul.vcardnative.domain.images.ImageException
import com.mackseoul.vcardnative.domain.sharing.ContactExport
import com.mackseoul.vcardnative.platform.storage.AndroidStoreFiles
import com.mackseoul.vcardnative.ui.theme.VCardTheme
import java.io.File
import java.util.UUID

@Suppress("DEPRECATION")
class StructuredNameWorkflowTest : InstrumentationTestCase() {
    private lateinit var root: File
    private lateinit var repository: ProfileRepository
    private lateinit var model: ProfileViewModel
    private val models = ViewModelStore()

    override fun setUp() {
        super.setUp()
        root = File(instrumentation.targetContext.noBackupFilesDir, "structured-name-${UUID.randomUUID()}")
        repository = ProfileRepository.managed(AndroidStoreFiles(root))
        main {
            model = ProfileViewModel(createRepository = { repository },
                readSelectedImage = { _, _ -> throw ImageException("Unused test reader") },
                cleanupContactFiles = {})
            models.put("names", model)
        }
        await()
    }

    override fun tearDown() {
        main { models.clear() }
        root.deleteRecursively()
        super.tearDown()
    }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await() {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (model.state.value.busy || model.state.value.loading) {
            if (System.nanoTime() > deadline) fail("Name workflow did not settle")
            Thread.sleep(20)
        }
    }

    fun testSetupNamesAndSaveAreReachableAtTwoHundredPercentPortraitAndLandscape() {
        main { model.beginProfileSetup() }
        render(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, listOf(
            "Full name", "First name (optional)", "Middle name (optional)", "Last name (optional)", "Continue",
        ))
        render(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, listOf(
            "Full name", "First name (optional)", "Middle name (optional)", "Last name (optional)", "Continue",
        ))
        main {
            model.advanceProfileSetup()
        }
        assertEquals(ProfileDestination.SetupDetails, model.state.value.destination)
        assertTrue(model.state.value.errors.containsKey(ProfileField.FirstName))
        main {
            model.edit(ProfileField.FirstName, "x".repeat(201))
            model.advanceProfileSetup()
        }
        assertTrue(model.state.value.errors.containsKey(ProfileField.FirstName))
        main {
            model.edit(ProfileField.FirstName, "  Zoë,  Ana  ")
            model.edit(ProfileField.MiddleName, "\u2003")
            model.edit(ProfileField.LastName, " O'Neil ")
        }
        assertEquals("Zoë, Ana O'Neil", model.state.value.fullNamePreview)
        render(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, listOf(
            "Full name", "Zoë, Ana O'Neil", "First name (optional)", "Middle name (optional)", "Last name (optional)",
        ), readOnlyPreview = "Zoë, Ana O'Neil")
        main {
            model.advanceProfileSetup()
            model.skipProfileContact()
            model.save()
        }
        await()
        assertNull(model.state.value.saveError)
        assertEquals("Zoë, Ana O'Neil", model.state.value.saved!!.data.value(ProfileField.FullName))
        val restarted = ProfileRepository.managed(AndroidStoreFiles(root)).load() as ProfileRead.Ready
        assertNull(restarted.saved.data.value(ProfileField.MiddleName))
        assertEquals("O'Neil", restarted.saved.data.value(ProfileField.LastName))
        assertEquals(Visibility(null, false), restarted.saved.data.visibility(ProfileField.FirstName))
        main {
            model.select(ProfileDestination.Profile)
            model.select(ProfileDestination.Editor)
        }
        render(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, listOf(
            "Full name", "Zoë, Ana O'Neil", "First name (optional)", "Middle name (optional)", "Last name (optional)", "Save profile",
        ), readOnlyPreview = "Zoë, Ana O'Neil")
        render(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, listOf(
            "Full name", "Zoë, Ana O'Neil", "First name (optional)", "Middle name (optional)", "Last name (optional)", "Save profile",
        ), readOnlyPreview = "Zoë, Ana O'Neil")
    }

    fun testLegacyNameReturnsWhenStructuredEditsAreUndoneAndSurvivesUnrelatedSave() {
        val legacyName = "Legacy Identity"
        repository.save(com.mackseoul.vcardnative.domain.profile.ProfileDraft.empty().copy(
            values = mapOf(
                ProfileField.FullName to legacyName,
                ProfileField.FirstName to "Ada",
                ProfileField.MiddleName to "Marie",
                ProfileField.LastName to "Byron",
            ),
        ), null)
        main { model.reload() }
        await()
        main {
            model.select(ProfileDestination.Profile)
            model.select(ProfileDestination.Editor)
        }
        assertEquals(legacyName, model.state.value.fullNamePreview)

        main {
            model.edit(ProfileField.FirstName, "Zoë")
        }
        assertEquals("Zoë Marie Byron", model.state.value.fullNamePreview)
        main { model.edit(ProfileField.FirstName, "Ada") }
        assertEquals(legacyName, model.state.value.fullNamePreview)

        main {
            model.edit(ProfileField.Organization, "Updated Organization")
            model.save()
        }
        await()
        assertEquals(legacyName, model.state.value.saved!!.data.value(ProfileField.FullName))

        main {
            model.select(ProfileDestination.Profile)
            model.select(ProfileDestination.Editor)
            model.edit(ProfileField.FirstName, "Discarded Name")
            model.requestBack {}
        }
        assertTrue(model.state.value.confirmDiscard)
        main { model.leaveEditor {} }
        assertEquals(legacyName, model.state.value.saved!!.data.value(ProfileField.FullName))

        main {
            model.select(ProfileDestination.Editor)
            model.edit(ProfileField.FirstName, "Zoë")
        }
        assertEquals(legacyName, model.state.value.saved!!.data.value(ProfileField.FullName))
        assertEquals("Zoë Marie Byron", model.state.value.fullNamePreview)
        main { model.save() }
        await()
        assertEquals("Zoë Marie Byron", model.state.value.saved!!.data.value(ProfileField.FullName))
    }

    fun testExportPrivacyAndExactCandidateOrderingThroughSavedProfile() {
        main {
            model.beginProfileSetup()
            model.edit(ProfileField.FirstName, "VCARD")
            model.edit(ProfileField.MiddleName, "TEST")
            model.edit(ProfileField.LastName, "Standard")
            model.advanceProfileSetup()
            model.skipProfileContact()
            model.save()
        }
        await()
        assertEquals("VCARD TEST Standard", model.state.value.saved!!.data.value(ProfileField.FullName))
        assertFalse(ContactExport.from(model.state.value.saved!!.data.profile).bytes.toString(Charsets.UTF_8)
            .contains("N:Standard"))
        main {
            model.select(ProfileDestination.Profile)
            model.select(ProfileDestination.Editor)
            model.privacy(ProfileField.FirstName, Visibility(null, true))
            model.privacy(ProfileField.MiddleName, Visibility(null, true))
            model.privacy(ProfileField.LastName, Visibility(null, true))
            model.save()
        }
        await()
        assertNull(model.state.value.saveError)
        val export = ContactExport.from(model.state.value.saved!!.data.profile)
        assertTrue(export.bytes.toString(Charsets.UTF_8).contains(
            "VERSION:3.0\r\nN:Standard;VCARD;TEST;;\r\nFN:VCARD TEST Standard\r\n",
        ))
        assertEquals(listOf("VCARD TEST Standard", "VCARD", "TEST", "Standard"), export.rows.map { it.value })
    }

    private fun render(
        orientation: Int,
        labels: List<String>,
        readOnlyPreview: String? = null,
    ) {
        var activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            main { activity.requestedOrientation = orientation }
            (monitor.waitForActivityWithTimeout(3_000) as? MainActivity)?.let { activity = it }
            val expectedOrientation = if (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            assertEquals(expectedOrientation, activity.resources.configuration.orientation)
            main {
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
                activity.setContent {
                    val state by model.state.collectAsState()
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                        VCardTheme { ProfileApplication(state, model, {}, {}, {}, { _, _ -> }, {}) }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            for (attempt in 0 until 20) {
                val window = instrumentation.uiAutomation.rootInActiveWindow ?: break
                if (!scroll(window, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) break
                Thread.sleep(100)
            }
            labels.forEach(::assertReachable)
            readOnlyPreview?.let(::assertReadOnlyPreview)
        } finally {
            instrumentation.removeMonitor(monitor)
            main { activity.finish() }
        }
    }

    private fun assertReadOnlyPreview(text: String) {
        val root = instrumentation.uiAutomation.rootInActiveWindow
        val node = root?.let { findTextNode(it, text) }
        assertNotNull("Generated preview is not present", node)
        assertFalse("Generated preview must not be editable", requireNotNull(node).isEditable)
    }

    private fun findTextNode(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.toString() == text) return node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { findTextNode(it, text)?.let { found -> return found } }
        }
        return null
    }

    private fun assertReachable(text: String) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            instrumentation.uiAutomation.rootInActiveWindow?.let {
                if (contains(it, text)) return
                scroll(it)
            }
            Thread.sleep(200)
        }
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        if (screenshot != null) {
            try {
                instrumentation.targetContext.cacheDir.resolve("m111-name-failure.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { screenshot.recycle() }
        }
        val window = instrumentation.uiAutomation.rootInActiveWindow
        fail("$text is unreachable at 200% font scale; nodes=${window?.let(::nodeText)}")
    }
    private fun nodeText(node: AccessibilityNodeInfo): String = buildString {
        append(node.text); append('|'); append(node.contentDescription); append('|')
        for (index in 0 until node.childCount) node.getChild(index)?.let { append(nodeText(it)) }
    }
    private fun contains(node: AccessibilityNodeInfo, text: String): Boolean {
        if (node.isVisibleToUser && (node.text?.toString()?.contains(text) == true ||
                node.contentDescription?.toString()?.contains(text) == true)) return true
        for (index in 0 until node.childCount) node.getChild(index)?.let { if (contains(it, text)) return true }
        return false
    }
    private fun scroll(node: AccessibilityNodeInfo, action: Int = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD): Boolean {
        if (node.isScrollable && node.actionList.any { it.id == action }) {
            val bounds = Rect().also(node::getBoundsInScreen)
            if (bounds.height() < 48) return false
            val x = bounds.exactCenterX()
            val forward = action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            val start = bounds.top + bounds.height() * if (forward) 0.75f else 0.25f
            val end = bounds.top + bounds.height() * if (forward) 0.25f else 0.75f
            val down = SystemClock.uptimeMillis()
            // Half-viewport swipes cannot skip a short field label in a 200%-font landscape frame.
            for (step in 0..10) {
                val type = when (step) { 0 -> MotionEvent.ACTION_DOWN; 10 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), type,
                    x, start + (end - start) * step / 10, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
                finally { event.recycle() }
                Thread.sleep(if (step == 9) 200 else 25)
            }
            return true
        }
        for (index in 0 until node.childCount) node.getChild(index)?.let { if (scroll(it, action)) return true }
        return false
    }
}
