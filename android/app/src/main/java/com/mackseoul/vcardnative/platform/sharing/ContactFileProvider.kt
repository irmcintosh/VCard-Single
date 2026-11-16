package com.mackseoul.vcardnative.platform.sharing

import androidx.core.content.FileProvider
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException
import com.mackseoul.vcardnative.domain.sharing.ContactExport
import com.mackseoul.vcardnative.domain.sharing.PublicKeyExport

class ContactFileProvider : FileProvider() {
    override fun getType(uri: Uri): String? {
        val inferred = super.getType(uri)
        return when (uri.lastPathSegment) {
            ContactExport.FILE_NAME -> ContactExport.MIME_TYPE
            PublicKeyExport.FILE_NAME -> PublicKeyExport.MIME_TYPE
            else -> inferred
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw FileNotFoundException("Contact exports are read-only.")
        return super.openFile(uri, mode)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Contact exports are read-only.")
}
