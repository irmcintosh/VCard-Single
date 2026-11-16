package com.mackseoul.vcardnative.platform

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.test.InstrumentationTestCase
import android.test.mock.MockContentResolver
import com.mackseoul.vcardnative.data.keys.PublicKeyValidator
import com.mackseoul.vcardnative.domain.keys.PublicKeyException
import com.mackseoul.vcardnative.domain.sharing.PublicKeyExport
import java.io.File
import java.util.UUID

@Suppress("DEPRECATION")
class PublicKeyDocumentsTest : InstrumentationTestCase() {
    fun testDocumentBytesNotMimeAndBoundedReadAndCompletedPublicOutput() {
        val root = File(instrumentation.targetContext.cacheDir, "key-document-test-${UUID.randomUUID()}")
        assertTrue(root.mkdir())
        try {
            val public = instrumentation.context.assets.open("openpgp/valid-public.asc").use { it.readBytes() }
            val secret = instrumentation.context.assets.open("openpgp/secret.asc").use { it.readBytes() }
            root.resolve("public").writeBytes(public)
            root.resolve("secret").writeBytes(secret)
            root.resolve("oversize").writeBytes(ByteArray(65_537))
            val provider = TestDocuments(root).apply {
                attachInfo(instrumentation.targetContext, android.content.pm.ProviderInfo().apply {
                    authority = "key-test"
                    exported = false
                })
            }
            val resolver = MockContentResolver(instrumentation.targetContext).apply { addProvider("key-test", provider) }
            val documents = PublicKeyDocuments(resolver)
            val publicUri = Uri.parse("content://key-test/public")
            val imported = PublicKeyValidator.import(documents.read(publicUri))
            val outputUri = Uri.parse("content://key-test/output")
            documents.write(outputUri, PublicKeyExport(imported.asset, imported.bytes))
            assertTrue(imported.bytes.contentEquals(root.resolve("output").readBytes()))
            val outputBefore = root.resolve("output").readBytes()
            try {
                PublicKeyValidator.import(documents.read(Uri.parse("content://key-test/secret")))
                fail("Secret material accepted despite document bytes")
            } catch (_: PublicKeyException) { }
            try {
                documents.read(Uri.parse("content://key-test/oversize"))
                fail("Oversized document read")
            } catch (_: PublicKeyException) { }
            try {
                documents.write(outputUri, PublicKeyExport(imported.asset, secret))
                fail("Unvalidated bytes written")
            } catch (_: PublicKeyException) { }
            assertTrue(outputBefore.contentEquals(root.resolve("output").readBytes()))
        } finally { root.deleteRecursively() }
    }

    private class TestDocuments(private val root: File) : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/png"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            throw UnsupportedOperationException()
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
            throw UnsupportedOperationException()
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val name = requireNotNull(uri.lastPathSegment)
            require(name in setOf("public", "secret", "oversize", "output"))
            return ParcelFileDescriptor.open(root.resolve(name),
                if (mode == "r") ParcelFileDescriptor.MODE_READ_ONLY else
                    ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
        }
    }
}
