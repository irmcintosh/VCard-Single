package com.mackseoul.vcardnative.domain.sharing

import com.mackseoul.vcardnative.domain.profile.PublicKeyAsset

class PublicKeyExport(val asset: PublicKeyAsset, bytes: ByteArray) {
    private val content = bytes.copyOf()
    val bytes: ByteArray get() = content.copyOf()

    companion object {
        const val FILE_NAME = "vcard-native-public-key.asc"
        const val MIME_TYPE = "application/pgp-keys"
    }
}
