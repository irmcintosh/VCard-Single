package com.mackseoul.vcardnative.platform.sharing

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import com.mackseoul.vcardnative.domain.sharing.ContactQr
import com.mackseoul.vcardnative.domain.sharing.QrImageContext
import com.mackseoul.vcardnative.domain.sharing.QrImageExport
import com.mackseoul.vcardnative.domain.sharing.QrImageNames
import com.mackseoul.vcardnative.domain.sharing.QrPng
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class QrImageSaveException(cause: Throwable? = null) : Exception("The QR image was not saved.", cause)

/** Publishes QR PNGs to Pictures/VCard Native through MediaStore; no storage permission is required. */
class QrImageGallery(
    private val resolver: ContentResolver,
    private val collection: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
) {
    /** Returns the display name Android stored. Nothing is reported as saved until the entry is published. */
    suspend fun save(png: ByteArray, displayName: String): String = withContext(Dispatchers.IO) {
        if (!QrPng.isEncodedPng(png) || !QrImageNames.isValid(displayName)) throw QrImageSaveException()
        val pending = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, QrPng.MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIRECTORY)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(collection, pending)
        } catch (error: Exception) {
            throw QrImageSaveException(error)
        } ?: throw QrImageSaveException()
        try {
            val stream = resolver.openOutputStream(uri, "w") ?: throw IOException("No output stream.")
            stream.use { it.write(png) }
            ensureActive()
            val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (resolver.update(uri, published, null, null) != 1) throw IOException("The image was not published.")
        } catch (error: Exception) {
            withContext(NonCancellable) {
                try { resolver.delete(uri, null, null) } catch (_: Exception) { }
            }
            if (error is CancellationException) throw error
            throw QrImageSaveException(error)
        }
        storedName(uri) ?: displayName
    }

    private fun storedName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.takeIf(String::isNotBlank) else null
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        const val RELATIVE_DIRECTORY = "Pictures/VCard Native"
    }
}

/** Production export: encodes the displayed QR off the main thread, then saves or shares the same bytes. */
class NativeQrImageExport(
    private val gallery: QrImageGallery,
    private val sharing: NativeContactSharing,
) : QrImageExport {
    override suspend fun save(qr: ContactQr, context: QrImageContext): String {
        val png = withContext(Dispatchers.Default) { QrPng.encode(qr) }
        return gallery.save(png, QrImageNames.fileName(context))
    }

    override suspend fun share(qr: ContactQr) {
        val png = withContext(Dispatchers.Default) { QrPng.encode(qr) }
        sharing.shareQrImage(png)
    }
}
