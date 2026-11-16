package com.mackseoul.vcardnative.data.profile

import com.mackseoul.vcardnative.data.keys.PublicKeyValidator
import com.mackseoul.vcardnative.domain.keys.DraftPublicKey
import com.mackseoul.vcardnative.domain.profile.*
import com.mackseoul.vcardnative.domain.sharing.ContactExport
import com.mackseoul.vcardnative.ui.profile.toMyCard
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class PublicKeyRepositoryTest {
    private val roots = mutableListOf<File>()
    @After fun cleanup() { roots.forEach { it.deleteRecursively() } }
    private fun files() = JavaTestStoreFiles(File("build\\key-tests\\${UUID.randomUUID()}").also { roots += it })
    private fun key(): DraftPublicKey = PublicKeyValidator.import(
        requireNotNull(javaClass.getResourceAsStream("/openpgp/valid-public.asc")).use { it.readBytes() },
    )
    private fun draft(key: DraftPublicKey) = ProfileDraft.empty().copy(
        values = mapOf(ProfileField.FullName to "Saved Key Profile"),
        publicKey = key.asset, pendingPublicKey = key,
    )
    private fun assertSnapshot(expected: Map<String, ByteArray>, files: JavaTestStoreFiles) {
        assertEquals(expected.keys, files.snapshot().keys)
        expected.forEach { (path, bytes) -> assertArrayEquals(bytes, files.bytes(path)) }
    }

    @Test fun explicitSaveReloadReuseRemovalPredecessorAndExportOmissions() {
        val files = files()
        val repository = ProfileRepository(files)
        val key = key()
        var saved = repository.save(draft(key), null)
        assertEquals(saved, (ProfileRepository(files).load() as ProfileRead.Ready).saved)
        assertArrayEquals(key.bytes, repository.readPublicKey(key.asset))
        assertEquals(key.asset, saved.data.toDraft().publicKey)
        assertNull(saved.data.toDraft().pendingPublicKey)
        assertNull(saved.data.toMyCard().profile!!.publicKey)
        val expectedContact = ContactExport.from(saved.data.profile.copy(publicKey = null)).bytes
        val originalBlobs = files.snapshot().filterKeys { it.startsWith("blob\\") }
        saved = repository.save(saved.data.toDraft().copy(
            visibility = saved.data.profile.visibility + (ProfileField.PublicKey to Visibility(true, null)),
            appearance = Appearance(Theme.light, Accent.jade),
        ), saved.token)
        assertEquals(key.asset, saved.data.toMyCard().profile!!.publicKey!!.asset)
        assertArrayEquals(expectedContact, ContactExport.from(saved.data.profile).bytes)
        assertEquals(originalBlobs.keys, files.snapshot().filterKeys { it.startsWith("blob\\") }.keys)
        originalBlobs.forEach { (path, bytes) -> assertArrayEquals(bytes, files.bytes(path)) }
        saved = repository.save(saved.data.toDraft().copy(publicKey = null), saved.token)
        assertEquals(1, files.list("blob").size)
        saved = repository.save(saved.data.toDraft(), saved.token)
        assertTrue(files.list("blob").isEmpty())
        assertNull(saved.data.profile.publicKey)
        try { repository.readPublicKey(key.asset); fail() } catch (_: StoreException) { }
    }

    @Test fun discardedReplacementInvalidPendingUnownedAndLowCapacityKeepPriorKey() {
        val files = files()
        val repository = ProfileRepository(files)
        val key = key()
        val saved = repository.save(draft(key), null)
        val before = files.snapshot()
        val replacement = key()
        assertNotEquals(saved.data.toDraft(), draft(replacement))
        for (bad in listOf(
            draft(replacement).copy(pendingPublicKey = null),
            draft(key).copy(pendingPublicKey = DraftPublicKey(key.asset, byteArrayOf(1, 2))),
            draft(replacement).copy(visibility = draft(replacement).visibility +
                (ProfileField.PublicKey to Visibility(true, true))),
        )) {
            try { repository.save(bad, saved.token); fail() } catch (_: StoreException) { }
        }
        files.freeBytes = 0
        try { repository.save(draft(replacement), saved.token); fail() } catch (_: StoreException) { }
        assertSnapshot(before, files)
    }

    @Test fun missingCorruptOrMetadataMismatchedKeyFailsClosedWithoutFallback() {
        for (mode in listOf("missing", "digest", "descriptor")) {
            val files = files()
            val repository = ProfileRepository(files)
            val key = key()
            val first = repository.save(draft(key), null)
            val second = repository.save(first.data.toDraft(), first.token)
            val path = "blob\\${files.list("blob").single()}"
            when (mode) {
                "missing" -> files.remove(path)
                "digest" -> files.put(path, key.bytes.also { it[it.lastIndex] = 0 })
                else -> {
                    val pointer = "ptr\\slot-1"
                    val payload = StrictJson.decode(files.bytes(pointer)).jsonObject("payload", "sha256")["payload"]
                        .jsonObject("magic", "format", "sequence", "manifestId", "manifestLength",
                            "manifestSha256", "generationId", "revision", "kind").toMutableMap()
                    val manifestPath = "gen\\${payload["manifestId"]}.manifest"
                    val manifest = ProfileCodec.decode(files.bytes(manifestPath))
                    val changed = manifest.copy(saved = second.copy(data = second.data.copy(
                        profile = second.data.profile.copy(publicKey = key.asset.copy(
                            fingerprint = "A".repeat(key.asset.fingerprint.length))),
                    )))
                    val bytes = ProfileCodec.encode(changed)
                    files.put(manifestPath, bytes)
                    payload["manifestLength"] = bytes.size
                    payload["manifestSha256"] = sha256(bytes)
                    files.put(pointer, StrictJson.encode(mapOf("payload" to payload, "sha256" to sha256(StrictJson.encode(payload)))))
                }
            }
            val before = files.snapshot()
            assertTrue(ProfileRepository(files).load() is ProfileRead.Unavailable)
            try { repository.readPublicKey(key.asset); fail() } catch (_: StoreException) { }
            assertSnapshot(before, files)
        }
    }

    @Test fun keyBlobPublicationFailuresPreservePriorAssetsAndCommitEvidence() {
        val source = files()
        val repository = ProfileRepository(source)
        val key = key()
        val saved = repository.save(draft(key), null)
        val baseline = source.snapshot()
        source.resetFaults()
        repository.save(draft(key()), saved.token)
        val events = source.events.take(source.events.indexOf("delete:PENDING") + 2)
        val indices = events.indices.filter {
            events[it].contains(".blob") || events[it] == "dirsync:blob" || events[it].startsWith("overwrite:")
        }
        assertTrue(indices.isNotEmpty())
        for (after in listOf(false, true)) for (index in indices) {
            val files = files()
            files.restore(baseline)
            files.resetFaults()
            files.failAt = index + 1
            files.failAfter = after
            try { ProfileRepository(files).save(draft(key()), saved.token); fail() } catch (_: StoreException) { }
            files.resetFaults()
            baseline.filterKeys { it.startsWith("blob\\") || it.startsWith("gen\\") }
                .forEach { (path, bytes) -> assertArrayEquals(bytes, files.bytes(path)) }
            val read = ProfileRepository(files).load()
            assertTrue(read is ProfileRead.Ready || read is ProfileRead.Unavailable)
            if (read is ProfileRead.Ready) assertEquals(saved, read.saved)
        }
    }
}
