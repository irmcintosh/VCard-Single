package com.mackseoul.vcardnative.domain.backup

import com.mackseoul.vcardnative.domain.profile.AppData
import com.mackseoul.vcardnative.domain.profile.ImageRole
import java.time.Instant
import java.util.Collections

class BackupException(
    message: String = "The backup is incomplete, unsupported or invalid.",
    cause: Throwable? = null,
) : Exception(message, cause)

class BackupSnapshot(
    data: AppData,
    images: Map<ImageRole, ByteArray>,
    publicKey: ByteArray?,
    val capturedAt: Instant,
) {
    val data: AppData = ownedData(data)
    private val imageContent = ownedImages(images)
    private val keyContent = publicKey?.copyOf()
    val images: Map<ImageRole, ByteArray> get() = ownedImages(imageContent)
    val publicKey: ByteArray? get() = keyContent?.copyOf()
}

/** Validated content only; no persisted generation, revision or cached key usability. */
class RestoreCandidate internal constructor(
    data: AppData,
    images: Map<ImageRole, ByteArray>,
    publicKey: ByteArray?,
    val createdAt: Instant,
) {
    val data: AppData = ownedData(data)
    private val imageContent = ownedImages(images)
    private val keyContent = publicKey?.copyOf()
    val images: Map<ImageRole, ByteArray> get() = ownedImages(imageContent)
    val publicKey: ByteArray? get() = keyContent?.copyOf()
}

private fun ownedData(data: AppData): AppData = data.copy(profile = data.profile.copy(
    values = Collections.unmodifiableMap(LinkedHashMap(data.profile.values)),
    visibility = Collections.unmodifiableMap(LinkedHashMap(data.profile.visibility)),
))

private fun ownedImages(images: Map<ImageRole, ByteArray>): Map<ImageRole, ByteArray> =
    Collections.unmodifiableMap(images.mapValues { it.value.copyOf() })
