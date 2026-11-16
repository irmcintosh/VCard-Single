package com.mackseoul.vcardnative.platform

import android.graphics.BitmapFactory
import com.mackseoul.vcardnative.domain.backup.BackupException
import com.mackseoul.vcardnative.domain.profile.AppData
import com.mackseoul.vcardnative.domain.profile.ImageRole
import com.mackseoul.vcardnative.domain.images.ImageStructure

object BackupImageValidation {
    fun validate(data: AppData, images: Map<ImageRole, ByteArray>) {
        for ((role, bytes) in images) {
            val asset = if (role == ImageRole.Portrait) data.profile.profileImage else data.profile.coverImage
            try {
                ImageStructure.validateCanonical(bytes, requireNotNull(asset), role)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw BackupException("The backup contains an image that Android cannot decode.")
                try {
                    if (bitmap.width != asset.width || bitmap.height != asset.height)
                        throw BackupException("The backup image dimensions do not match its metadata.")
                } finally { bitmap.recycle() }
            } catch (_: IllegalArgumentException) {
                throw BackupException("The backup contains an invalid image or image relationship.")
            }
        }
    }
}
