package com.mackseoul.vcardnative.platform.sharing

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import android.test.InstrumentationTestCase
import com.mackseoul.vcardnative.data.sharing.ContactQrEncoder
import com.mackseoul.vcardnative.domain.sharing.QrImageContext
import com.mackseoul.vcardnative.domain.sharing.QrImageNames
import com.mackseoul.vcardnative.domain.sharing.QrPng
import kotlinx.coroutines.runBlocking

@Suppress("DEPRECATION")
class QrImageGalleryTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private val resolver get() = context.contentResolver
    private val collection: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val createdNames = mutableListOf<String>()

    override fun tearDown() {
        // Removes only items created by this test, never other gallery content.
        createdNames.forEach { find(it)?.let { uri -> resolver.delete(uri, null, null) } }
        super.tearDown()
    }

    private fun find(name: String): Uri? {
        val bundle = android.os.Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DISPLAY_NAME} = ?")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(name))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        return resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), bundle, null)?.use {
            if (it.moveToFirst()) ContentUris.withAppendedId(collection, it.getLong(0)) else null
        }
    }

    fun testTwoSavesCreateDistinctPublishedPngsInPicturesVCardNative() {
        val png = QrPng.encode(ContactQrEncoder.encodeHostedProfileUrl("https://example.com/test-card"))
        val gallery = QrImageGallery(resolver)
        val first = QrImageNames.fileName(QrImageContext.HostedProfile)
        val second = QrImageNames.fileName(QrImageContext.HostedProfile)
        createdNames += listOf(first, second)
        val storedFirst = runBlocking { gallery.save(png, first) }
        val storedSecond = runBlocking { gallery.save(png, second) }
        createdNames += listOf(storedFirst, storedSecond)
        assertFalse(storedFirst == storedSecond)
        for (name in listOf(storedFirst, storedSecond)) {
            val uri = requireNotNull(find(name)) { "Saved image not found: $name" }
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
                assertEquals("image/png", it.getString(1))
                assertEquals("Pictures/VCard Native/", it.getString(2))
            }
            assertTrue(png.contentEquals(resolver.openInputStream(uri)!!.use { it.readBytes() }))
        }
    }

    fun testInvalidInputAndRejectedInsertLeaveNoGalleryEntry() {
        val gallery = QrImageGallery(resolver)
        val name = QrImageNames.fileName(QrImageContext.PrimaryContact)
        createdNames += name
        try { runBlocking { gallery.save("not a png".toByteArray(), name) }; fail() } catch (_: QrImageSaveException) { }
        val png = QrPng.encode(ContactQrEncoder.encodeHostedProfileUrl("https://example.com/test-card"))
        try { runBlocking { gallery.save(png, "../escape.png") }; fail() } catch (_: QrImageSaveException) { }
        val audio = QrImageGallery(resolver, MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))
        try { runBlocking { audio.save(png, name) }; fail() } catch (_: QrImageSaveException) { }
        assertNull(find(name))
    }
}
