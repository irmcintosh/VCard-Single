package com.mackseoul.vcardnative.platform

import android.content.ContentResolver
import android.net.Uri
import com.mackseoul.vcardnative.domain.profile.DraftImage
import com.mackseoul.vcardnative.domain.profile.ImageRole
import com.mackseoul.vcardnative.platform.images.AndroidImageCanonicalizer
import com.mackseoul.vcardnative.domain.images.ImageException
import java.io.IOException

class ImageSelectionReader(private val resolver: ContentResolver) {
    fun read(uri: Uri, role: ImageRole): DraftImage {
        try {
            val stream = resolver.openInputStream(uri)
                ?: throw ImageException("The selected image could not be opened. The previous image is unchanged.")
            return stream.use { AndroidImageCanonicalizer.canonicalize(it, role) }
        } catch (_: SecurityException) {
            throw ImageException("Access to the selected image was lost. Select it again; the previous image is unchanged.")
        } catch (_: IOException) {
            throw ImageException("The selected image could not be read. The previous image is unchanged.")
        }
    }
}
