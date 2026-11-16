package com.mackseoul.vcardnative.data.profile

import com.mackseoul.vcardnative.domain.profile.*
import com.mackseoul.vcardnative.domain.images.ImageStructure
import com.mackseoul.vcardnative.domain.images.ImageStructureTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ProfileImageRepositoryTest {
    private val roots = mutableListOf<File>()
    @After fun cleanup() { roots.forEach { it.deleteRecursively() } }
    private fun files() = JavaTestStoreFiles(File("build\\image-tests\\${UUID.randomUUID()}").also { roots += it })
    private fun image(): DraftImage {
        val bytes = ImageStructureTest.png()
        return DraftImage(ImageAsset(UUID.randomUUID(),"image/png",bytes.size.toLong(),1,1,ImageStructure.digest(bytes)),bytes)
    }
    private fun draft(image: DraftImage) = ProfileDraft.empty().copy(values=mapOf(ProfileField.FullName to "Ada"),
        profileImage=image.asset,pendingImages=mapOf(ImageRole.Portrait to image),
        visibility=ProfileDraft.empty().visibility + (ProfileField.ProfileImage to Visibility(false,null)))

    @Test fun saveReloadReuseDiscardRemoveAndAppearancePreserveExactBytes() {
        val files = files(); val repo = ProfileRepository(files); val image = image()
        var saved = repo.save(draft(image),null)
        assertArrayEquals(image.bytes,repo.readImage(image.asset))
        assertEquals(saved,(ProfileRepository(files).load() as ProfileRead.Ready).saved)
        assertEquals(image.asset,saved.data.toDraft().profileImage)
        assertTrue(saved.data.toDraft().pendingImages.isEmpty())
        val blobs = files.snapshot().filterKeys { it.startsWith("blob\\") }
        val discarded = draft(image()).copy(coverImage=null)
        assertNotEquals(saved.data.toDraft(),discarded)
        for(theme in Theme.entries) for(accent in Accent.entries) {
            saved = repo.save(saved.data.toDraft().copy(appearance=Appearance(theme,accent)),saved.token)
            assertEquals(Appearance(theme,accent),(repo.load() as ProfileRead.Ready).saved.data.appearance)
            assertEquals(Visibility(false,null),saved.data.visibility(ProfileField.ProfileImage))
            assertArrayEquals(image.bytes,repo.readImage(image.asset))
        }
        assertEquals(blobs.keys,files.snapshot().filterKeys { it.startsWith("blob\\") }.keys)
        blobs.forEach { (path,bytes) -> assertArrayEquals(bytes,files.bytes(path)) }
        saved = repo.save(saved.data.toDraft().copy(profileImage=null),saved.token)
        assertEquals(1,files.list("blob").size) // Predecessor still owns image.
        saved = repo.save(saved.data.toDraft(),saved.token)
        assertTrue(files.list("blob").isEmpty())
        try { repo.readImage(image.asset); fail() } catch (_: StoreException) { }
    }

    @Test fun corruptMissingDigestAndStructuralImageNeverExposeOrFallBack() {
        for(mode in listOf("missing","length","digest","structure","descriptor")) {
            val files = files(); val repo = ProfileRepository(files); val image = image()
            val first = repo.save(draft(image),null)
            val second = repo.save(first.data.toDraft(),first.token)
            val blob = "blob\\${files.list("blob").single()}"
            when(mode) {
                "missing" -> files.remove(blob)
                "length" -> files.put(blob,image.bytes.copyOf(image.bytes.size-1))
                "digest" -> files.put(blob,image.bytes.also { it[0] = 0 })
                else -> {
                    val pointerPath = "ptr\\slot-1"
                    val envelope = StrictJson.decode(files.bytes(pointerPath)) as Map<*,*>
                    val payload = envelope["payload"] as Map<*,*>
                    val path = "gen\\${payload["manifestId"]}.manifest"
                    val manifest = ProfileCodec.decode(files.bytes(path))
                    val bad = image.bytes + byteArrayOf(0)
                    val asset = if(mode == "descriptor") image.asset.copy(width=2) else image.asset.copy(
                        byteLength=bad.size.toLong(),sha256=ImageStructure.digest(bad))
                    val mappings = if(mode == "structure") {
                        val newBlob = UUID.randomUUID().toString()
                        files.put("blob\\$newBlob.blob",bad)
                        mapOf(ImageRole.Portrait to newBlob)
                    } else manifest.blobs
                    val edited = manifest.copy(
                        saved=second.copy(data=second.data.copy(profile=second.data.profile.copy(profileImage=asset))),
                        blobs=mappings)
                    val bytes = ProfileCodec.encode(edited); files.put(path,bytes)
                    val newPayload = payload.entries.associate { it.key as String to it.value }.toMutableMap()
                    newPayload["manifestLength"] = bytes.size
                    newPayload["manifestSha256"] = sha256(bytes)
                    files.put(pointerPath,StrictJson.encode(mapOf("payload" to newPayload,"sha256" to sha256(StrictJson.encode(newPayload)))))
                }
            }
            val before = files.snapshot()
            assertTrue("Mode $mode exposed corrupt image", ProfileRepository(files).load() is ProfileRead.Unavailable)
            try { repo.readImage(image.asset); fail() } catch(e: StoreException) { assertFalse(e.message!!.contains("Ada")) }
            assertEquals(before.keys,files.snapshot().keys)
            before.forEach { (p,b) -> assertArrayEquals(b,files.bytes(p)) }
        }
    }

    @Test fun lowSpaceBadPendingOrUnownedDescriptorPerformNoWrites() {
        val files = files(); val repo = ProfileRepository(files); val image = image()
        val saved = repo.save(draft(image),null); val before = files.snapshot()
        for(bad in listOf(draft(image()).copy(pendingImages=emptyMap()),
            draft(image).copy(pendingImages=mapOf(ImageRole.Portrait to DraftImage(image.asset,byteArrayOf(1)))))) {
            try { repo.save(bad,saved.token); fail() } catch (_: StoreException) { }
        }
        files.freeBytes = 0
        try { repo.save(draft(image()),saved.token); fail() } catch (_: StoreException) { }
        assertEquals(before.keys,files.snapshot().keys)
        before.forEach { (p,b) -> assertArrayEquals(b,files.bytes(p)) }
    }

    @Test fun blobPublicationFaultsPreservePreviousImageAndRecoveryEvidence() {
        val source = files(); val repo = ProfileRepository(source)
        val old = image(); val saved = repo.save(draft(old),null); val baseline = source.snapshot()
        source.resetFaults();repo.save(draft(image()),saved.token)
        val events = source.events.take(source.events.indexOf("delete:PENDING")+2)
        val indices = events.indices.filter {
            events[it].contains(".blob") || events[it] == "dirsync:blob" || events[it].startsWith("overwrite:")
        }
        assertTrue(indices.isNotEmpty())
        for(after in listOf(false,true)) for(index in indices) {
            val files = files();files.restore(baseline);files.resetFaults();files.failAt=index+1;files.failAfter=after
            try { ProfileRepository(files).save(draft(image()),saved.token);fail() } catch (_: StoreException) { }
            files.resetFaults()
            baseline.filterKeys { it.startsWith("blob\\") || it.startsWith("gen\\") }.forEach { (p,b) -> assertArrayEquals(b,files.bytes(p)) }
            val loaded = ProfileRepository(files).load()
            assertTrue(loaded is ProfileRead.Ready || loaded is ProfileRead.Unavailable)
            if(loaded is ProfileRead.Ready) assertEquals(saved,loaded.saved)
        }
    }
}
