package com.mackseoul.vcardnative.platform.sharing

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.Settings
import android.test.InstrumentationTestCase
import androidx.core.content.FileProvider
import com.mackseoul.vcardnative.MainActivity
import com.mackseoul.vcardnative.data.keys.PublicKeyValidator
import com.mackseoul.vcardnative.data.sharing.TemporaryContactFiles
import com.mackseoul.vcardnative.domain.keys.PublicKeyException
import com.mackseoul.vcardnative.domain.sharing.PublicKeyExport
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Suppress("DEPRECATION")
class NativePublicKeySharingTest : InstrumentationTestCase() {
    private lateinit var activity: Activity
    private val created = mutableListOf<File>()
    private val context get() = instrumentation.targetContext
    override fun setUp() {
        super.setUp()
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    override fun tearDown() {
        val files = TemporaryContactFiles(File(context.cacheDir, "public-key-share"),
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toLong(), PublicKeyExport.FILE_NAME)
        created.forEach { files.discard(it) }
        instrumentation.runOnMainSync { activity.finish() }
        super.tearDown()
    }
    private fun export(): PublicKeyExport {
        val key = PublicKeyValidator.import(instrumentation.context.assets.open("openpgp/valid-public.asc").use { it.readBytes() })
        return PublicKeyExport(key.asset, key.bytes)
    }
    private fun capture(export: PublicKeyExport): Intent {
        val captured = AtomicReference<Intent>()
        NativeContactSharing(activity) { captured.set(it) }.sharePublicKey(export)
        val chooser = requireNotNull(captured.get())
        val send = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        val uri = requireNotNull(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        created += File(context.cacheDir, "public-key-share").resolve("payload")
            .resolve(uri.pathSegments[1]).resolve(PublicKeyExport.FILE_NAME)
        return send
    }
    fun testPublicOnlyExactBytesMimeAndNarrowReadOnlyProvider() {
        val export = export()
        val send = capture(export)
        val uri = requireNotNull(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals("application/pgp-keys", send.type)
        assertEquals(PublicKeyExport.FILE_NAME, uri.lastPathSegment)
        assertEquals("content", uri.scheme)
        assertEquals("application/pgp-keys", context.contentResolver.getType(uri))
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags)
        assertTrue(export.bytes.contentEquals(context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }))
        assertFalse(uri.toString().contains("/data/"))
        try { context.contentResolver.openOutputStream(uri); fail("Provider granted write") }
        catch (_: java.io.FileNotFoundException) { }
        for (path in listOf(File(context.noBackupFilesDir, "store-v1/INIT"),
            File(context.cacheDir, "public-key-share/leases/private"))) {
            try { FileProvider.getUriForFile(context, uri.authority!!, path); fail("Provider exposed private storage") }
            catch (_: IllegalArgumentException) { }
        }
        val invalid = PublicKeyExport(export.asset, "private or malformed".toByteArray())
        try { NativeContactSharing(activity) { fail("Invalid material reached Android") }.sharePublicKey(invalid); fail() }
        catch (_: PublicKeyException) { }
    }
    fun testExternalReceiverReadOnlyGrantAndRevocation() {
        val export = export()
        val send = capture(export)
        val uri = requireNotNull(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        fun receive(grant: Boolean): Intent {
            val action = "${context.packageName}.test.key.${UUID.randomUUID()}"
            val done = CountDownLatch(1)
            val result = AtomicReference<Intent>()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { result.set(intent); done.countDown() }
            }
            context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_EXPORTED)
            try {
                val outgoing = Intent(if (grant) Intent.ACTION_SEND else "${context.packageName}.test.NO_GRANT")
                    .setComponent(ComponentName(instrumentation.context.packageName, ContactReceiverActivity::class.java.name))
                    .putExtra(Intent.EXTRA_STREAM, uri).putExtra("resultAction", action)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (grant) {
                    outgoing.clipData = send.clipData
                    outgoing.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(outgoing)
                assertTrue(done.await(10, TimeUnit.SECONDS))
                return requireNotNull(result.get())
            } finally { context.unregisterReceiver(receiver) }
        }
        val received = receive(true)
        assertFalse(received.getBooleanExtra("readDenied", true))
        assertTrue(received.getBooleanExtra("writeDenied", false))
        assertEquals(String(export.bytes, Charsets.US_ASCII), received.getStringExtra("content"))
        context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val denied = receive(false)
        assertTrue(denied.getBooleanExtra("readDenied", false))
        assertTrue(denied.getBooleanExtra("writeDenied", false))
    }
}
