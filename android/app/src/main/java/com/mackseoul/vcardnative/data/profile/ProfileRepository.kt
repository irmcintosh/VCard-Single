package com.mackseoul.vcardnative.data.profile

import com.mackseoul.vcardnative.domain.profile.*
import com.mackseoul.vcardnative.domain.images.ImageStructure
import com.mackseoul.vcardnative.data.keys.PublicKeyValidator
import java.time.Instant
import java.util.UUID
import com.mackseoul.vcardnative.domain.backup.BackupSnapshot

data class RevisionToken(val generationId: String, val revision: Long)
data class SavedProfile(val data: AppData, val token: RevisionToken)
sealed interface ProfileRead {
    data object Empty : ProfileRead
    data class Ready(val saved: SavedProfile) : ProfileRead
    /**
     * [recoveryToken] is present only when the live store is unavailable, no operation is pending and
     * the exact unavailable state can be preserved as evidence by [ProfileRepository.recover].
     * [pendingOperation] is "delete", "recover" or "blocked" when an operation record prevents use.
     */
    data class Unavailable(
        val message: String,
        val recoveryToken: String? = null,
        val pendingOperation: String? = null,
    ) : ProfileRead
}

open class StoreException(message: String = "The profile could not be saved. Your draft has been kept.") : Exception(message)
class ValidationException(val errors: Map<ProfileField, String>) :
    StoreException("Correct the highlighted fields before saving.")
class StaleRevisionException : StoreException("The saved profile changed. Reopen the editor before saving.")

/** The unmanaged A2 store engine operating on one store directory. */
internal class ProfileStore(private val fileOperations: StoreFiles) {
    private val files get() = fileOperations

    fun <T> withSavedSnapshot(expected: RevisionToken, action: (SavedProfile) -> T): T =
        synchronized(files.coordinationLock) {
            val saved = when (val read = load()) {
                is ProfileRead.Ready -> read.saved
                is ProfileRead.Unavailable -> throw StoreException(read.message)
                ProfileRead.Empty -> throw StoreException("Save a profile before exporting a contact.")
            }
            if (saved.token != expected) throw StaleRevisionException()
            action(saved)
        }

    fun load(): ProfileRead = synchronized(files.coordinationLock) {
        try {
            val manifest = reconcile()
            if (manifest != null) cleanupVerifiedAncestors(manifest)
            if (manifest == null) ProfileRead.Empty else ProfileRead.Ready(manifest.saved)
        } catch (_: Exception) {
            ProfileRead.Unavailable(UNAVAILABLE)
        }
    }

    fun readImage(asset: ImageAsset): ByteArray = synchronized(files.coordinationLock) {
        try {
            val active = requireNotNull(reconcile())
            val role = imageAssets(active.saved.data).entries.firstOrNull { it.value == asset }?.key
                ?: throw IllegalArgumentException()
            verifiedImage(active, role, asset)
        } catch (_: Exception) {
            throw StoreException(UNAVAILABLE)
        }
    }

    fun readPublicKey(asset: PublicKeyAsset): ByteArray = synchronized(files.coordinationLock) {
        try {
            val active = requireNotNull(reconcile())
            require(active.saved.data.profile.publicKey == asset)
            verifiedPublicKey(active, asset)
        } catch (_: Exception) {
            throw StoreException(UNAVAILABLE)
        }
    }

    fun backupSnapshot(): BackupSnapshot = synchronized(files.coordinationLock) {
        try {
            val current = reconcile() ?: throw StoreException("Save a profile before creating a backup.")
            val images = imageAssets(current.saved.data).mapValues { (role, asset) -> verifiedImage(current, role, asset) }
            val key = current.saved.data.profile.publicKey?.let { verifiedPublicKey(current, it) }
            BackupSnapshot(current.saved.data, images, key, Instant.now())
        } catch (error: StoreException) {
            throw error
        } catch (_: Exception) {
            throw StoreException(UNAVAILABLE)
        }
    }

    fun restore(snapshot: BackupSnapshot, expected: RevisionToken?): SavedProfile {
        try {
            val data = snapshot.data
            val images = snapshot.images
            require(images.keys == imageAssets(data).keys)
            require((snapshot.publicKey != null) == (data.profile.publicKey != null))
            val assetIds = imageAssets(data).values.map { it.assetId } + listOfNotNull(data.profile.publicKey?.assetId)
            require(assetIds.size == assetIds.toSet().size)
            val draft = data.toDraft().copy(
                pendingImages = imageAssets(data).mapValues { (role, asset) -> DraftImage(asset, images.getValue(role)) },
                pendingPublicKey = data.profile.publicKey?.let {
                    com.mackseoul.vcardnative.domain.keys.DraftPublicKey(it, requireNotNull(snapshot.publicKey))
                },
            )
            return commit(draft, expected, data)
        } catch (error: StoreException) {
            throw error
        } catch (_: IllegalArgumentException) {
            throw StoreException("The restore candidate is invalid. Saved local data is unchanged.")
        }
    }

    fun save(draft: ProfileDraft, expected: RevisionToken?): SavedProfile = commit(draft, expected, null)

    fun delete(expected: RevisionToken) = synchronized(files.coordinationLock) {
        try {
            val current = reconcile() ?: throw StoreException("There is no saved profile to delete.")
            if (current.saved.token != expected) throw StaleRevisionException()

            files.list("ptr").forEach { files.delete("ptr\\$it") }
            files.syncDirectory("ptr")

            files.list("gen").forEach { files.delete("gen\\$it") }
            files.syncDirectory("gen")
            files.list("blob").forEach { files.delete("blob\\$it") }
            files.syncDirectory("blob")
            files.list("stage").forEach { stageId ->
                val stagePath = "stage\\$stageId"
                files.list(stagePath).forEach { files.delete("$stagePath\\$it") }
                files.syncDirectory(stagePath)
                files.delete(stagePath)
                files.syncDirectory("stage")
            }
            files.delete("INIT")
            files.delete("ptr")
            files.delete("gen")
            files.delete("blob")
            files.delete("stage")
            files.syncDirectory("")
            require(files.list("").isEmpty())
        } catch (error: StaleRevisionException) {
            throw error
        } catch (error: StoreException) {
            throw error
        } catch (_: Exception) {
            throw StoreException("Profile deletion could not be completed. Local data was not treated as deleted.")
        }
    }

    private fun commit(draft: ProfileDraft, expected: RevisionToken?, restored: AppData?): SavedProfile {
        require(restored == null || restored.schemaVersion in 1..AppData.CURRENT_SCHEMA_VERSION)
        val validation = ProfileValidator.validate(draft)
        val validated = validation.normalized ?: throw ValidationException(validation.errors)
        val normalized = validated.copy(visibility = validated.visibility.mapValues { (field, preference) ->
            if (field in legacySocialFields && validated.socialProfiles.none { it.legacyField == field }) {
                field.defaultVisibility()
            } else preference
        })
        return synchronized(files.coordinationLock) {
            var referenceStarted = false
            try {
                val current = reconcile()
                if (current?.saved?.token != expected) throw StaleRevisionException()
                val revision = if (restored != null) 1L else current?.saved?.token?.revision?.let { Math.addExact(it, 1L) } ?: 1L
                val sequence = current?.sequence?.let { Math.addExact(it, 1L) } ?: 1L
                val time = Instant.now().toString()
                val data = if (restored != null) {
                    restored.copy(
                        schemaVersion = AppData.CURRENT_SCHEMA_VERSION,
                        profile = restored.profile.copy(
                            values = normalized.values.toMap(),
                            visibility = normalized.visibility.toMap(),
                            phoneRegion = normalized.phoneRegion,
                            includeCountryCodeWhenSharing = normalized.includeCountryCodeWhenSharing,
                            phoneNumberE164 = normalized.phoneNumberE164,
                            socialProfiles = normalized.socialProfiles.toList(),
                        ),
                    )
                } else AppData(
                    schemaVersion = AppData.CURRENT_SCHEMA_VERSION,
                    profile = Profile(
                        current?.saved?.data?.profile?.profileId ?: UUID.randomUUID(),
                        normalized.values.toMap(), normalized.visibility.toMap(),
                        publicKey = normalized.publicKey,
                        profileImage = normalized.profileImage, coverImage = normalized.coverImage,
                        phoneRegion = normalized.phoneRegion,
                        includeCountryCodeWhenSharing = normalized.includeCountryCodeWhenSharing,
                        phoneNumberE164 = normalized.phoneNumberE164,
                        socialProfiles = normalized.socialProfiles.toList(),
                    ),
                    appearance = normalized.appearance,
                    metadata = Metadata(current?.saved?.data?.metadata?.createdAt ?: time, time)
                )
                if (restored != null) require(validated == draft) { "Restore fields must already be canonical." }
                val saved = SavedProfile(data, RevisionToken(
                    if (restored != null) randomId() else current?.saved?.token?.generationId ?: randomId(), revision))
                val newBlobs = linkedMapOf<String, ByteArray>()
                val existing = current?.let { imageAssets(it.saved.data) }.orEmpty()
                val blobs = imageAssets(data).mapValues { (role, asset) ->
                    val priorRole = existing.entries.firstOrNull { it.value == asset }?.key
                    val pending = normalized.pendingImages[role]
                    if (pending != null) ImageStructure.validateCanonical(pending.bytes, asset, role)
                    if (priorRole != null) {
                        requireNotNull(current).blobs.getValue(priorRole)
                    } else {
                        require(restored != null || existing.values.none { it.assetId == asset.assetId })
                        val content = requireNotNull(pending).bytes
                        ImageStructure.validateCanonical(content, asset, role)
                        randomId().also { newBlobs[it] = content }
                    }
                }
                val keyBlob = normalized.publicKey?.let { asset ->
                    val pending = normalized.pendingPublicKey
                    if (pending != null) PublicKeyValidator.validateStored(pending.bytes, asset)
                    if (current?.saved?.data?.profile?.publicKey == asset) {
                        requireNotNull(current.keyBlob)
                    } else {
                        require(restored != null || current?.saved?.data?.profile?.publicKey?.assetId != asset.assetId)
                        val content = requireNotNull(pending).bytes
                        PublicKeyValidator.validateStored(content, asset)
                        randomId().also { newBlobs[it] = content }
                    }
                }
                val manifest = Manifest(randomId(), sequence, current?.id, saved, blobs, keyBlob,
                    if (restored == null) "save" else "restore")
                val bytes = ProfileCodec.encode(manifest)
                require(ProfileCodec.decode(bytes) == manifest)
                val pointer = encodePointer(manifest, bytes)
                preflight(bytes.size, pointer.size, current == null, newBlobs.values.map { it.size })
                if (current == null) initialize()
                val stage = "stage\\${manifest.id}"
                files.createDirectory(stage)
                files.syncDirectory("stage")
                for ((id, content) in newBlobs) {
                    val stagedBlob = "$stage\\$id.blob"
                    files.writeNew(stagedBlob, content)
                    files.syncFile(stagedBlob)
                    files.moveNew(stagedBlob, "blob\\$id.blob")
                    files.syncDirectory(stage)
                    files.syncDirectory("blob")
                }
                verifyAssets(manifest)
                val stagedManifest = "$stage\\manifest"
                files.writeNew(stagedManifest, bytes)
                files.syncFile(stagedManifest)
                val finalManifest = "gen\\${manifest.id}.manifest"
                files.moveNew(stagedManifest, finalManifest)
                files.syncDirectory(stage)
                files.syncDirectory("gen")
                require(files.read(finalManifest, StrictJson.MAX_BYTES).contentEquals(bytes))
                require(ProfileCodec.decode(bytes) == manifest)

                // A durable intent makes an interrupted reference update unreadable, not an invitation
                // to choose an older slot. Evidence is retained on every uncertain outcome.
                referenceStarted = true
                files.writeNew("PENDING", manifest.id.toByteArray(Charsets.US_ASCII))
                files.syncFile("PENDING")
                files.syncDirectory("")
                val slot = if (sequence % 2L == 1L) "ptr\\slot-0" else "ptr\\slot-1"
                files.overwrite(slot, pointer)
                files.syncFile(slot)
                files.syncDirectory("ptr")
                require(files.read(slot, POINTER_LIMIT).contentEquals(pointer))
                val verified = readPointer(slot)
                require(verified.manifest == manifest)
                files.delete("PENDING")
                files.syncDirectory("")
                cleanupVerifiedAncestors(manifest)
                return@synchronized saved
            } catch (e: StaleRevisionException) {
                throw e
            } catch (e: Exception) {
                if (referenceStarted) {
                    StoreLocks.markUncertain(files.uncertaintyKey)
                    try {
                        if (!files.exists("PENDING")) {
                            files.writeNew("PENDING", "uncertain".toByteArray(Charsets.US_ASCII))
                            files.syncFile("PENDING")
                            files.syncDirectory("")
                        }
                    } catch (_: Exception) {
                        // An ongoing filesystem failure cannot be repaired here. Never report success.
                    }
                }
                if (e is StoreException) throw e
                throw StoreException(if (referenceStarted) UNAVAILABLE else if (restored != null)
                    "Restore did not commit. Your previously saved data is preserved."
                else "The profile could not be saved. Your draft has been kept.")
            }
        }
    }

    private fun preflight(manifestBytes: Int, pointerBytes: Int, first: Boolean, blobBytes: List<Int>) {
        val unit = files.allocationUnitBytes()
        require(unit in 1..1_048_576)
        fun allocation(size: Int) = Math.multiplyExact((size.toLong() + unit - 1) / unit, unit)
        // Actual new files and directory entries only. Existing generations already consume the
        // filesystem's reported free space; no arbitrary free-space percentage/reserve is imposed.
        var needed = Math.addExact(allocation(manifestBytes), allocation(pointerBytes))
        blobBytes.forEach { needed = Math.addExact(needed, allocation(it)) }
        needed = Math.addExact(needed, allocation(36))
        needed = Math.addExact(needed, unit) // Per-operation directory.
        if (first) needed = Math.addExact(needed, Math.addExact(allocation(INIT.size), Math.multiplyExact(unit, 4)))
        if (files.usableSpace() < needed) throw StoreException("Not enough internal storage. Free space and try again.")
    }

    private fun initialize() {
        require(files.list("").isEmpty())
        files.writeNew("INIT", INIT)
        files.syncFile("INIT")
        files.syncDirectory("")
        for (directory in listOf("ptr", "gen", "blob", "stage")) {
            files.createDirectory(directory)
            files.syncDirectory("")
        }
    }

    private fun reconcile(): Manifest? {
        require(!StoreLocks.isUncertain(files.uncertaintyKey) && !files.exists("PENDING"))
        val entries = files.list("")
        if (entries.isEmpty()) return null
        require(entries == setOf("INIT", "ptr", "gen", "blob", "stage"))
        require(files.read("INIT", INIT.size).contentEquals(INIT))
        val pointerNames = files.list("ptr")
        require(pointerNames.isNotEmpty() && pointerNames.all { it == "slot-0" || it == "slot-1" })
        val pointers = pointerNames.map { readPointer("ptr\\$it") }.sortedBy { it.manifest.sequence }
        val latest = pointers.last().manifest
        if (pointers.size == 1) {
            require(latest.sequence == 1L && latest.previous == null && latest.saved.token.revision == 1L &&
                pointerNames == setOf("slot-0"))
            // Without a second slot, additional published manifests may be evidence of a lost
            // newer reference. Do not mistake that situation for the normal first commit.
            require(files.list("gen") == setOf("${latest.id}.manifest"))
        } else {
            val old = pointers.first().manifest
            require(latest.sequence == Math.addExact(old.sequence, 1L) && latest.previous == old.id)
            verifySuccessor(old, latest)
        }
        return latest
    }

    private data class Pointer(val manifest: Manifest)

    private fun cleanupVerifiedAncestors(active: Manifest) {
        // readImage returns an owned copy under this same lock; no live filesystem handles escape.
        if (active.previous == null) return
        try {
            require(reconcile() == active)
            if (files.exists("PENDING")) return
            val stages = files.list("stage")
            if (stages.any { files.list("stage\\$it").isNotEmpty() }) return
            val names = files.list("gen")
            val manifests = names.associate { name ->
                require(name.endsWith(".manifest"))
                val id = uuid(name.removeSuffix(".manifest"))
                val manifest = ProfileCodec.decode(files.read("gen\\$name", StrictJson.MAX_BYTES))
                require(manifest.id == id)
                verifyAssets(manifest)
                id to manifest
            }
            val chain = linkedSetOf(active.id)
            var current = active
            while (current.previous != null) {
                val previous = manifests[current.previous] ?: break // Previously pruned ancestry.
                require(chain.add(previous.id))
                verifySuccessor(previous, current)
                current = previous
            }
            // Any object outside the established committed lineage is recovery evidence.
            if (chain != manifests.keys || active.previous !in chain) return
            val protectedBlobs = (allBlobs(active) + allBlobs(manifests.getValue(active.previous))).toSet()
            val knownBlobs = manifests.values.flatMap { allBlobs(it) }.toSet()
            val blobNames = files.list("blob")
            // Unknown blobs and nonempty staging directories are recovery evidence.
            if (blobNames != knownBlobs.map { "$it.blob" }.toSet()) return
            val removable = chain.filter { it != active.id && it != active.previous }
            // Oldest first keeps any surviving history contiguous after an interrupted cleanup.
            removable.sortedBy { manifests.getValue(it).sequence }.forEach {
                files.delete("gen\\$it.manifest")
                files.syncDirectory("gen")
            }
            (knownBlobs - protectedBlobs).forEach {
                files.delete("blob\\$it.blob")
                files.syncDirectory("blob")
            }
        } catch (_: Exception) {
            // Cleanup is best effort, never part of commit success or justification for rollback.
            // Active, predecessor and all uncertain objects remain outside its deletion set.
        }
    }

    private fun readPointer(path: String): Pointer {
        val envelope = StrictJson.decode(files.read(path, POINTER_LIMIT)).jsonObject("payload", "sha256")
        val payload = envelope["payload"].jsonObject(
            "magic", "format", "sequence", "manifestId", "manifestLength", "manifestSha256", "generationId", "revision", "kind"
        )
        require(envelope.string("sha256") == sha256(StrictJson.encode(payload)))
        require(payload.string("magic") == "VCARD-A2" && payload.long("format") == 1L &&
            payload.string("kind") in setOf("save", "restore"))
        val sequence = payload.long("sequence")
        require(sequence > 0 && path == if (sequence % 2L == 1L) "ptr\\slot-0" else "ptr\\slot-1")
        val id = uuid(payload.string("manifestId"))
        val length = payload.long("manifestLength")
        require(length in 1..StrictJson.MAX_BYTES.toLong())
        val bytes = files.read("gen\\$id.manifest", StrictJson.MAX_BYTES)
        require(bytes.size.toLong() == length && sha256(bytes) == payload.string("manifestSha256"))
        val manifest = ProfileCodec.decode(bytes)
        require(manifest.id == id && manifest.sequence == sequence && manifest.kind == payload.string("kind") &&
            manifest.saved.token == RevisionToken(uuid(payload.string("generationId")), payload.long("revision")))
        verifyAssets(manifest)
        return Pointer(manifest)
    }

    private fun verifySuccessor(previous: Manifest, current: Manifest) {
        require(current.sequence == Math.addExact(previous.sequence, 1L) && current.previous == previous.id)
        if (current.kind == "restore") {
            require(current.saved.token.generationId != previous.saved.token.generationId && current.saved.token.revision == 1L)
        } else {
            require(current.saved.token.generationId == previous.saved.token.generationId &&
                current.saved.token.revision == Math.addExact(previous.saved.token.revision, 1L) &&
                current.saved.data.profile.profileId == previous.saved.data.profile.profileId &&
                current.saved.data.metadata.createdAt == previous.saved.data.metadata.createdAt)
        }
    }

    private fun verifyAssets(manifest: Manifest) {
        val assets = imageAssets(manifest.saved.data)
        require(manifest.blobs.keys == assets.keys)
        val assetIds = assets.values.map { it.assetId } + listOfNotNull(manifest.saved.data.profile.publicKey?.assetId)
        require(assetIds.distinct().size == assetIds.size)
        val blobIds = allBlobs(manifest)
        require(blobIds.distinct().size == blobIds.size)
        assets.forEach { (role, asset) -> verifiedImage(manifest, role, asset) }
        val key = manifest.saved.data.profile.publicKey
        require((key != null) == (manifest.keyBlob != null))
        key?.let { verifiedPublicKey(manifest, it) }
    }

    private fun allBlobs(manifest: Manifest): List<String> =
        manifest.blobs.values.toList() + listOfNotNull(manifest.keyBlob)

    private fun verifiedPublicKey(manifest: Manifest, asset: PublicKeyAsset): ByteArray {
        val id = uuid(requireNotNull(manifest.keyBlob))
        val content = files.read("blob\\$id.blob", 65_536)
        PublicKeyValidator.validateStored(content, asset)
        return content
    }

    private fun verifiedImage(manifest: Manifest, role: ImageRole, asset: ImageAsset): ByteArray {
        val id = uuid(manifest.blobs.getValue(role))
        val content = files.read("blob\\$id.blob", ImageStructure.RETAINED_LIMIT)
        ImageStructure.validateCanonical(content, asset, role)
        return content
    }

    private fun encodePointer(manifest: Manifest, bytes: ByteArray): ByteArray {
        val payload = linkedMapOf(
            "magic" to "VCARD-A2", "format" to 1, "sequence" to manifest.sequence,
            "manifestId" to manifest.id, "manifestLength" to bytes.size, "manifestSha256" to sha256(bytes),
            "generationId" to manifest.saved.token.generationId, "revision" to manifest.saved.token.revision,
            "kind" to manifest.kind
        )
        return StrictJson.encode(linkedMapOf("payload" to payload, "sha256" to sha256(StrictJson.encode(payload))))
    }

    private fun randomId() = UUID.randomUUID().toString()

    companion object {
        private val INIT = "VCARD-STORE-A2-1\n".toByteArray(Charsets.US_ASCII)
        private const val POINTER_LIMIT = 4096
        internal const val UNAVAILABLE = "Saved profile storage is unavailable. Existing data has been preserved; do not reset it."
    }
}
