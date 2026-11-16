package com.mackseoul.vcardnative.data.profile

import com.mackseoul.vcardnative.data.keys.PublicKeyValidator
import com.mackseoul.vcardnative.domain.backup.BackupSnapshot
import com.mackseoul.vcardnative.domain.images.ImageStructure
import com.mackseoul.vcardnative.domain.images.ImageStructureTest
import com.mackseoul.vcardnative.domain.profile.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

class BackupRepositoryTest {
    private val roots = mutableListOf<File>()
    @After fun cleanup() { roots.forEach { it.deleteRecursively() } }
    private fun files() = JavaTestStoreFiles(File("build\\backup-store-tests\\${UUID.randomUUID()}").also { roots += it })
    private fun plain(name: String) = ProfileDraft.empty().copy(values = mapOf(ProfileField.FullName to name))
    private fun full(): ProfileDraft {
        val bytes = ImageStructureTest.png()
        val portrait = DraftImage(ImageAsset(UUID.randomUUID(), "image/png", bytes.size.toLong(), 1, 1,
            ImageStructure.digest(bytes)), bytes)
        val cover = DraftImage(portrait.asset.copy(assetId = UUID.randomUUID()), bytes)
        val key = PublicKeyValidator.import(requireNotNull(javaClass.getResourceAsStream("/openpgp/valid-public.asc"))
            .use { it.readBytes() })
        return plain("Backup Saved").copy(
            values = mapOf(ProfileField.FullName to "Backup Saved", ProfileField.Biography to "Hidden saved information",
                ProfileField.FirstName to "Backup", ProfileField.MiddleName to "Synthetic", ProfileField.LastName to "Saved"),
            visibility = ProfileDraft.empty().visibility +
                (ProfileField.Biography to Visibility(false, false)) +
                (ProfileField.ProfileImage to Visibility(false, null)),
            profileImage = portrait.asset, coverImage = cover.asset,
            pendingImages = mapOf(ImageRole.Portrait to portrait, ImageRole.Cover to cover),
            publicKey = key.asset, pendingPublicKey = key,
            appearance = Appearance(Theme.light, Accent.amethyst),
        )
    }
    private fun unchanged(before: Map<String, ByteArray>, files: JavaTestStoreFiles) {
        assertEquals(before.keys, files.snapshot().keys)
        before.forEach { (path, bytes) -> assertArrayEquals(bytes, files.bytes(path)) }
    }
    private fun active(files: JavaTestStoreFiles): Manifest =
        files.list("gen").map { ProfileCodec.decode(files.bytes("gen\\$it")) }.maxBy { it.sequence }

    @Test fun snapshotIncludesHiddenValuesExactAssetsMetadataAndNeverLaterEdits() {
        val files = files()
        val repository = ProfileRepository(files)
        val saved = repository.save(full(), null)
        val snapshot = repository.backupSnapshot()
        assertEquals(saved.data, snapshot.data)
        assertEquals("Hidden saved information", snapshot.data.value(ProfileField.Biography))
        assertArrayEquals(repository.readImage(saved.data.profile.profileImage!!), snapshot.images.getValue(ImageRole.Portrait))
        assertArrayEquals(repository.readPublicKey(saved.data.profile.publicKey!!), snapshot.publicKey)
        val bytes = snapshot.images.getValue(ImageRole.Portrait)
        bytes[0] = 0
        assertNotEquals(0.toByte(), snapshot.images.getValue(ImageRole.Portrait)[0])
        repository.save(saved.data.toDraft().copy(values = mapOf(ProfileField.FullName to "Later Saved")), saved.token)
        assertEquals(saved.data, snapshot.data)
    }

    @Test fun fullRestorePreservesAggregateResetsLogicalRevisionAndFollowingSaveKeepsLineage() {
        val source = ProfileRepository(files())
        source.save(full(), null)
        val snapshot = source.backupSnapshot()
        val files = files()
        val repository = ProfileRepository(files)
        val prior = repository.save(plain("Local Before"), null)
        val predecessor = active(files)
        var restored = repository.restore(snapshot, prior.token)
        assertEquals(snapshot.data, restored.data)
        assertNotEquals(prior.token.generationId, restored.token.generationId)
        assertEquals(1L, restored.token.revision)
        assertEquals(restored, (ProfileRepository(files).load() as ProfileRead.Ready).saved)
        assertEquals("restore", active(files).kind)
        assertEquals(predecessor.id, active(files).previous)
        assertEquals(2L, active(files).sequence)
        assertArrayEquals(snapshot.publicKey, repository.readPublicKey(restored.data.profile.publicKey!!))
        restored = repository.save(restored.data.toDraft(), restored.token)
        assertEquals(2L, restored.token.revision)
        assertEquals("save", active(files).kind)
        assertEquals(3L, active(files).sequence)
        assertEquals(restored, (ProfileRepository(files).load() as ProfileRead.Ready).saved)
        assertEquals(2, files.list("gen").size)
        assertEquals(3, files.list("blob").size)
    }

    @Test fun firstRunRestoreAndUnchangedBlobReuse() {
        val source = ProfileRepository(files())
        source.save(full(), null)
        val snapshot = source.backupSnapshot()
        val files = files()
        val repository = ProfileRepository(files)
        val first = repository.restore(snapshot, null)
        val blobs = files.snapshot().filterKeys { it.startsWith("blob\\") }
        val second = repository.restore(snapshot, first.token)
        assertNotEquals(first.token.generationId, second.token.generationId)
        assertEquals(1L, second.token.revision)
        assertEquals(blobs.keys, files.snapshot().filterKeys { it.startsWith("blob\\") }.keys)
        assertEquals(second, (ProfileRepository(files).load() as ProfileRead.Ready).saved)
    }

    @Test fun importedAssetIdCanReplaceChangedContentWithoutOverwritingPredecessorBlob() {
        val files = files()
        val repository = ProfileRepository(files)
        val oldBytes = ImageStructureTest.png()
        val oldAsset = ImageAsset(UUID.randomUUID(), "image/png", oldBytes.size.toLong(), 1, 1, ImageStructure.digest(oldBytes))
        val saved = repository.save(plain("Local Image").copy(
            profileImage = oldAsset, pendingImages = mapOf(ImageRole.Portrait to DraftImage(oldAsset, oldBytes))), null)
        val oldBlobs = files.snapshot().filterKeys { it.startsWith("blob\\") }
        val newBytes = ImageStructureTest.png(raw = byteArrayOf(0, 5, 6, 7, 8))
        val newAsset = oldAsset.copy(byteLength = newBytes.size.toLong(), sha256 = ImageStructure.digest(newBytes))
        val data = saved.data.copy(profile = saved.data.profile.copy(profileImage = newAsset))
        val snapshot = BackupSnapshot(data, mapOf(ImageRole.Portrait to newBytes), null, Instant.now())
        val restored = repository.restore(snapshot, saved.token)
        assertEquals(newAsset, restored.data.profile.profileImage)
        assertArrayEquals(newBytes, repository.readImage(newAsset))
        oldBlobs.forEach { (path, bytes) -> assertArrayEquals(bytes, files.bytes(path)) }
        assertEquals(2, files.list("blob").size)
    }

    @Test fun restoredImageBlobPublicationFailuresKeepPredecessorBytes() {
        val source = files()
        val repository = ProfileRepository(source)
        val saved = repository.save(plain("Local Before"), null)
        val baseline = source.snapshot()
        val bytes = ImageStructureTest.png()
        val asset = ImageAsset(UUID.randomUUID(), "image/png", bytes.size.toLong(), 1, 1, ImageStructure.digest(bytes))
        val snapshot = BackupSnapshot(saved.data.copy(profile = saved.data.profile.copy(profileImage = asset)),
            mapOf(ImageRole.Portrait to bytes), null, Instant.now())
        source.resetFaults()
        repository.restore(snapshot, saved.token)
        val events = source.events.take(source.events.indexOf("delete:PENDING") + 2)
        val indices = events.indices.filter { events[it].contains(".blob") || events[it] == "dirsync:blob" }
        assertTrue(indices.isNotEmpty())
        for (after in listOf(false, true)) for (index in indices) {
            val target = files()
            target.restore(baseline)
            target.resetFaults()
            target.failAt = index + 1
            target.failAfter = after
            try { ProfileRepository(target).restore(snapshot, saved.token); fail() } catch (_: StoreException) { }
            target.resetFaults()
            baseline.forEach { (path, content) -> assertArrayEquals(content, target.bytes(path)) }
            val read = ProfileRepository(target).load()
            assertTrue(read is ProfileRead.Ready || read is ProfileRead.Unavailable)
            if (read is ProfileRead.Ready) assertEquals(saved, read.saved)
        }
    }

    @Test fun staleInvalidRelationshipsNonCanonicalAndLowSpaceDoNotModifySavedData() {
        val source = ProfileRepository(files())
        source.save(full(), null)
        val snapshot = source.backupSnapshot()
        val files = files()
        val repository = ProfileRepository(files)
        val saved = repository.save(plain("Local Before"), null)
        val before = files.snapshot()
        try { repository.restore(snapshot, null); fail() } catch (_: StaleRevisionException) { }
        val invalid = listOf(
            BackupSnapshot(snapshot.data, emptyMap(), snapshot.publicKey, Instant.now()),
            BackupSnapshot(snapshot.data, snapshot.images, null, Instant.now()),
            BackupSnapshot(snapshot.data.copy(profile = snapshot.data.profile.copy(
                values = snapshot.data.profile.values + (ProfileField.FullName to " padded "))),
                snapshot.images, snapshot.publicKey, Instant.now()),
            BackupSnapshot(snapshot.data.copy(schemaVersion = AppData.CURRENT_SCHEMA_VERSION + 1), snapshot.images, snapshot.publicKey, Instant.now()),
            BackupSnapshot(snapshot.data.copy(metadata = Metadata("invalid", "invalid")),
                snapshot.images, snapshot.publicKey, Instant.now()),
            BackupSnapshot(snapshot.data.copy(profile = snapshot.data.profile.copy(
                coverImage = snapshot.data.profile.coverImage!!.copy(assetId = snapshot.data.profile.profileImage!!.assetId))),
                snapshot.images, snapshot.publicKey, Instant.now()),
        )
        for (candidate in invalid) {
            try { repository.restore(candidate, saved.token); fail() } catch (_: StoreException) { }
            unchanged(before, files)
        }

        files.freeBytes = 0
        try { repository.restore(snapshot, saved.token); fail() } catch (_: StoreException) { }
        unchanged(before, files)
    }

    @Test fun invalidRestoreSchemaCannotPartiallyInitializeAFirstRunStore() {
        val source = ProfileRepository(files())
        source.save(plain("Imported"), null)
        val snapshot = source.backupSnapshot()
        val target = files()
        val repository = ProfileRepository(target)
        val invalid = BackupSnapshot(snapshot.data.copy(schemaVersion = AppData.CURRENT_SCHEMA_VERSION + 1), emptyMap(), null, Instant.now())
        try { repository.restore(invalid, null); fail() } catch (_: StoreException) { }
        assertTrue(target.snapshot().isEmpty())
        assertEquals(ProfileRead.Empty, repository.load())
    }

    @Test fun interruptedRestoreNeverExposesAHybridOrFallsBackAfterReferenceUncertainty() {
        val source = files()
        val repository = ProfileRepository(source)
        val saved = repository.save(plain("Local Before"), null)
        val baseline = source.snapshot()
        val imported = ProfileRepository(files())
        imported.save(plain("Imported Complete"), null)
        val snapshot = imported.backupSnapshot()
        source.resetFaults()
        repository.restore(snapshot, saved.token)
        val events = source.events.take(source.events.indexOf("delete:PENDING") + 2)
        val indices = events.indices.filter {
            events[it].startsWith("writeNew:stage") || events[it].startsWith("move:") ||
                events[it].contains("PENDING") || events[it].startsWith("overwrite:") ||
                events[it] == "fsync:ptr\\slot-1" || events[it] == "dirsync:ptr"
        }
        assertTrue(indices.isNotEmpty())
        for (after in listOf(false, true)) for (index in indices) {
            val target = files()
            target.restore(baseline)
            target.resetFaults()
            target.failAt = index + 1
            target.failAfter = after
            try { ProfileRepository(target).restore(snapshot, saved.token); fail() } catch (_: StoreException) { }
            target.resetFaults()
            baseline.filterKeys { it.startsWith("gen\\") || it.startsWith("blob\\") }
                .forEach { (path, bytes) -> assertArrayEquals(bytes, target.bytes(path)) }
            val read = ProfileRepository(target).load()
            assertTrue(read is ProfileRead.Ready || read is ProfileRead.Unavailable)
            if (read is ProfileRead.Ready) assertEquals(saved, read.saved)
        }
    }

    @Test fun corruptionAndPartialInitializationCannotBeReplacedAsFirstRun() {
        val source = ProfileRepository(files())
        source.save(plain("Imported"), null)
        val snapshot = source.backupSnapshot()
        for (partial in listOf(false, true)) {
            val target = files()
            if (partial) target.put("INIT", byteArrayOf(0)) else {
                ProfileRepository(target).save(plain("Local"), null)
                target.put("ptr\\slot-0", byteArrayOf(1))
            }
            val before = target.snapshot()
            try { ProfileRepository(target).restore(snapshot, null); fail() } catch (_: StoreException) { }
            assertTrue(ProfileRepository(target).load() is ProfileRead.Unavailable)
            unchanged(before, target)
        }
    }
}
