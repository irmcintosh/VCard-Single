package com.mackseoul.vcardnative.domain.keys

import com.mackseoul.vcardnative.domain.profile.PublicKeyAsset

class PublicKeyException(message: String) : Exception(message)

class DraftPublicKey(val asset: PublicKeyAsset, bytes: ByteArray) {
    private val content = bytes.copyOf()
    val bytes: ByteArray get() = content.copyOf()

    override fun equals(other: Any?): Boolean =
        other is DraftPublicKey && asset == other.asset && content.contentEquals(other.content)

    override fun hashCode(): Int = 31 * asset.hashCode() + content.contentHashCode()
}

data class PublicKeyStatus(val usable: Boolean, val explanation: String?)
