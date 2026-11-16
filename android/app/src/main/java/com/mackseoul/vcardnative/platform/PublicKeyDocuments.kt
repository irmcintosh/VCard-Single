package com.mackseoul.vcardnative.platform

import android.content.ContentResolver
import android.net.Uri
import com.mackseoul.vcardnative.domain.keys.PublicKeyException
import com.mackseoul.vcardnative.domain.sharing.PublicKeyExport
import java.io.IOException
import java.io.ByteArrayOutputStream
import com.mackseoul.vcardnative.data.keys.PublicKeyValidator

class PublicKeyDocuments(private val resolver: ContentResolver) {
    fun read(uri: Uri): ByteArray {
        try {
            return (resolver.openInputStream(uri)
                ?: throw PublicKeyException("Android could not open the selected public-key document.")).use {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                try {
                    while (true) {
                        val count = it.read(buffer, 0, minOf(buffer.size, 65_537 - output.size()))
                        if (count < 0) break
                        if (count == 0) throw IOException("Document stream made no progress.")
                        output.write(buffer, 0, count)
                        if (output.size() > 65_536)
                            throw PublicKeyException("Select a public-key file between 1 byte and 64 KiB.")
                    }
                    if (output.size() == 0)
                        throw PublicKeyException("Select a public-key file between 1 byte and 64 KiB.")
                    output.toByteArray()
                } finally {
                    buffer.fill(0)
                }
            }
        } catch (_: IOException) {
            throw PublicKeyException("The selected document could not be read. Your previous key is unchanged.")
        } catch (_: SecurityException) {
            throw PublicKeyException("Android denied document access. Your previous key is unchanged.")
        }
    }

    fun write(uri: Uri, export: PublicKeyExport) {
        val status = PublicKeyValidator.validateStored(export.bytes, export.asset)
        if (!status.usable) throw PublicKeyException(status.explanation ?: "The public key is not currently usable.")
        try {
            (resolver.openOutputStream(uri, "wt")
                ?: throw PublicKeyException("Android could not open the public-key destination.")).use {
                it.write(export.bytes)
                it.flush()
            }
        } catch (_: IOException) {
            throw PublicKeyException("The public-key write did not complete. The destination may contain an incomplete file.")
        } catch (_: SecurityException) {
            throw PublicKeyException("Android denied writing the public-key destination. No completed output is confirmed.")
        }
    }
}
