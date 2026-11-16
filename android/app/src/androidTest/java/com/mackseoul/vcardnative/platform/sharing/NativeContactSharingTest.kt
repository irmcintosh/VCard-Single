package com.mackseoul.vcardnative.platform.sharing

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import android.test.InstrumentationTestCase
import androidx.core.content.FileProvider
import com.mackseoul.vcardnative.MainActivity
import com.mackseoul.vcardnative.platform.storage.AndroidHostValidator
import com.mackseoul.vcardnative.data.sharing.TemporaryContactFiles
import com.mackseoul.vcardnative.domain.profile.*
import com.mackseoul.vcardnative.domain.sharing.ContactExport
import com.mackseoul.vcardnative.domain.sharing.ContactExportException
import com.mackseoul.vcardnative.domain.sharing.QrPng
import com.mackseoul.vcardnative.data.sharing.ContactQrEncoder
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Suppress("DEPRECATION")
class NativeContactSharingTest : InstrumentationTestCase() {
    private lateinit var activity: Activity
    private val created = mutableListOf<File>()
    private val context get() = instrumentation.targetContext

    override fun setUp() {
        super.setUp()
        activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun tearDown() {
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toLong()
        val files = TemporaryContactFiles(File(context.cacheDir, "contact-share"), boot)
        created.forEach { files.discard(it) }
        val images = TemporaryContactFiles(File(context.cacheDir, "contact-share"), boot, QrPng.FILE_NAME)
        createdImages.forEach { images.discard(it) }
        instrumentation.runOnMainSync { activity.finish() }
        super.tearDown()
    }

    private val createdImages = mutableListOf<File>()

    fun testQrPngShareUsesImageMimeReadOnlyGrantSharedProviderAndExactBytes() {
        val png = QrPng.encode(ContactQrEncoder.encode(export()))
        val captured = AtomicReference<Intent>()
        runBlocking { NativeContactSharing(activity) { captured.set(it) }.shareQrImage(png) }
        val chooser = requireNotNull(captured.get())
        val send = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        val uri = requireNotNull(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        createdImages += File(context.cacheDir, "contact-share").resolve("payload").resolve(uri.pathSegments[1])
            .resolve(QrPng.FILE_NAME)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/png", send.type)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, chooser.flags and (Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.contact-files", uri.authority)
        assertEquals("contact", uri.pathSegments[0])
        assertEquals(QrPng.FILE_NAME, uri.lastPathSegment)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        assertEquals(uri, chooser.clipData!!.getItemAt(0).uri)
        assertEquals("image/png", context.contentResolver.getType(uri))
        assertTrue(png.contentEquals(context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }))
        try { context.contentResolver.openOutputStream(uri); fail("PNG share URI was writable") }
        catch (_: Exception) { }
        try {
            runBlocking { NativeContactSharing(activity) { fail("Non-PNG reached Android") }.shareQrImage(export().bytes) }
            fail("Non-PNG bytes accepted")
        } catch (_: ContactExportException) { }
    }

    private fun export(): ContactExport {
        val draft = ProfileDraft.empty().copy(
            values = mapOf(ProfileField.FullName to "Zoë 😀 Example", ProfileField.Organization to "A, B; C\\D",
                ProfileField.Biography to "First\nSecond", ProfileField.Email to "excluded@example.invalid"),
            visibility = ProfileDraft.empty().visibility +
                (ProfileField.Organization to Visibility(false, true)) +
                (ProfileField.Biography to Visibility(true, true)),
        )
        val normalized = requireNotNull(ProfileValidator.validate(draft).normalized)
        return ContactExport.from(Profile(UUID.randomUUID(), normalized.values, normalized.visibility))
    }

    private fun capture(export: ContactExport): Intent {
        val intent = AtomicReference<Intent>()
        NativeContactSharing(activity) { chooser -> intent.set(chooser) }.share(export)
        val chooser = requireNotNull(intent.get())
        val send = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        val uri = requireNotNull(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        val id = uri.pathSegments[1]
        created += File(context.cacheDir, "contact-share").resolve("payload").resolve(id).resolve(ContactExport.FILE_NAME)
        return chooser
    }

    fun testChooserMimeClipDataGenericNameExactContentAndNarrowProvider() {
        val export = export()
        val chooser = capture(export)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/vcard", send.type)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags)
        assertEquals(0, chooser.flags and (Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
        val uri = requireNotNull(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.contact-files", uri.authority)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        assertEquals(uri, chooser.clipData!!.getItemAt(0).uri)
        assertFalse(uri.toString().contains("Zo"))
        assertFalse(uri.toString().contains("/data/"))
        assertEquals(ContactExport.FILE_NAME, uri.lastPathSegment)
        assertEquals("text/vcard", context.contentResolver.getType(uri))
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)!!.use {
            assertTrue(it.moveToFirst())
            assertEquals(ContactExport.FILE_NAME, it.getString(0))
            assertEquals(export.bytes.size.toLong(), it.getLong(1))
        }
        assertTrue(export.bytes.contentEquals(context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }))
        val provider = requireNotNull(context.packageManager.resolveContentProvider(uri.authority!!, 0))
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        for (outside in listOf(File(context.noBackupFilesDir, "store-v1").resolve("INIT"),
            File(context.cacheDir, "contact-share").resolve("leases").resolve("private"))) {
            try { FileProvider.getUriForFile(context, uri.authority!!, outside); fail("Provider exposed non-payload storage") }
            catch (_: IllegalArgumentException) { }
        }
        val traversal = uri.buildUpon().path("/contact/../../no_backup/store-v1/INIT").build()
        try { context.contentResolver.openInputStream(traversal); fail("Provider allowed traversal") }
        catch (_: SecurityException) { }
    }

    fun testExternalUidGetsExactReadOnlyGrantAndRevocationDeniesRead() {
        val export = export()
        val chooser = capture(export)
        val send = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        val uri = requireNotNull(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        fun receive(grant: Boolean): Intent {
            val action = "${context.packageName}.test.${UUID.randomUUID()}"
            val result = AtomicReference<Intent>()
            val done = CountDownLatch(1)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    result.set(intent)
                    done.countDown()
                }
            }
            context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_EXPORTED)
            try {
                // ACTION_SEND automatically migrates EXTRA_STREAM into a new read grant.
                val outgoing = Intent(if (grant) Intent.ACTION_SEND else "${context.packageName}.test.NO_GRANT").setComponent(
                    ComponentName(instrumentation.context.packageName, ContactReceiverActivity::class.java.name),
                ).putExtra(Intent.EXTRA_STREAM, uri).putExtra("resultAction", action)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (grant) {
                    outgoing.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    outgoing.clipData = send.clipData
                }
                context.startActivity(outgoing)
                assertTrue("Receiver did not report", done.await(10, TimeUnit.SECONDS))
                return requireNotNull(result.get())
            } finally { context.unregisterReceiver(receiver) }
        }
        val received = receive(true)
        assertFalse(received.getBooleanExtra("readDenied", true))
        assertEquals(String(export.bytes, Charsets.UTF_8), received.getStringExtra("content"))
        assertTrue(received.getBooleanExtra("writeDenied", false))
        assertFalse(received.getStringExtra("content")!!.contains("excluded@example.invalid"))
        context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val revoked = receive(false)
        assertTrue(revoked.getBooleanExtra("readDenied", false))
        assertTrue(revoked.getBooleanExtra("writeDenied", false))
    }

    fun testMissingHandlerFailureDoesNotLeaveAnUnissuedContactOrEchoPayload() {
        val payload = File(context.cacheDir, "contact-share").resolve("payload")
        val before = payload.list()?.toSet().orEmpty()
        try {
            NativeContactSharing(activity) { throw ActivityNotFoundException("Private provider detail") }.share(export())
            fail("Missing handler reported success")
        } catch (error: ContactExportException) {
            assertTrue(error.message!!.contains("No Android sharing handler"))
            assertFalse(error.message!!.contains("Private"))
        }
        assertEquals(before, payload.list()?.toSet().orEmpty())
    }

    fun testAndroidIdnaAndUnicodeUriEncodingPreserveValidatedBytes() {
        val draft = ProfileDraft.empty().copy(
            values = mapOf(ProfileField.FullName to "Áda 中文 😀",
                ProfileField.Email to "person@bücher.example",
                ProfileField.Website to "https://bücher.example/中文,;?x=%2F"),
            visibility = ProfileDraft.empty().visibility +
                (ProfileField.Email to Visibility(true, true)) +
                (ProfileField.Website to Visibility(false, true)),
        )
        val normalized = requireNotNull(ProfileValidator.validate(draft, AndroidHostValidator).normalized)
        val export = ContactExport.from(Profile(UUID.randomUUID(), normalized.values, normalized.visibility))
        val text = String(export.bytes, Charsets.UTF_8).replace("\r\n ", "")
        assertTrue(text.contains("FN:Áda 中文 😀\r\n"))
        assertTrue(text.contains("EMAIL;TYPE=INTERNET:person@xn--bcher-kva.example\r\n"))
        assertTrue(text.contains("URL:https://xn--bcher-kva.example/%E4%B8%AD%E6%96%87,;?x=%2F\r\n"))
        val chooser = capture(export)
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertTrue(export.bytes.contentEquals(context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }))
    }
}
