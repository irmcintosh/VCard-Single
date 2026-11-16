package com.mackseoul.vcardnative.platform

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.test.InstrumentationTestCase
import android.test.mock.MockContentResolver
import com.mackseoul.vcardnative.data.backup.BackupStaging
import com.mackseoul.vcardnative.domain.backup.BackupException
import java.io.File
import java.io.IOException
import java.util.UUID

@Suppress("DEPRECATION")
class BackupDocumentsTest : InstrumentationTestCase() {
    fun testMimeIndependentBoundedReadCompletedOutputAndUnavailableDestinationFeedback() {
        val root = File(instrumentation.targetContext.noBackupFilesDir, "backup-document-test-${UUID.randomUUID()}")
        assertTrue(root.mkdir())
        try {
            val bytes = byteArrayOf(1, 2, 3)
            root.resolve("input").writeBytes(bytes)
            root.resolve("oversize").writeBytes(ByteArray(BackupStaging.MAX_BYTES.toInt() + 1))
            val provider = TestDocuments(root).apply {
                attachInfo(instrumentation.targetContext, android.content.pm.ProviderInfo().apply {
                    authority = "backup-test"
                    exported = false
                })
            }
            val resolver = MockContentResolver(instrumentation.targetContext).apply { addProvider("backup-test", provider) }
            val staging = BackupStaging(root.resolve("stage"))
            val documents = BackupDocuments(resolver, staging)
            val stage = documents.read(Uri.parse("content://backup-test/input"))
            documents.write(Uri.parse("content://backup-test/output"), stage)
            assertTrue(bytes.contentEquals(root.resolve("output").readBytes()))
            try { documents.read(Uri.parse("content://backup-test/oversize")); fail() } catch (_: BackupException) { }
            try { documents.write(Uri.parse("content://backup-test/denied"), stage); fail() }
            catch (error: BackupException) { assertTrue(error.message!!.contains("incomplete")) }
            assertTrue(bytes.contentEquals(root.resolve("output").readBytes()))
            staging.discard(stage)
            assertTrue(root.resolve("stage").listFiles()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }

    private class TestDocuments(private val root: File) : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri) = "text/plain"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            throw UnsupportedOperationException()
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
            throw UnsupportedOperationException()
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val name = requireNotNull(uri.lastPathSegment)
            if (name == "denied") throw IOException("Injected provider failure")
            require(name in setOf("input", "oversize", "output"))
            return ParcelFileDescriptor.open(root.resolve(name),
                if (mode == "r") ParcelFileDescriptor.MODE_READ_ONLY else
                    ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
        }
    }
}
