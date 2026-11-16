package com.mackseoul.vcardnative.data.profile

import com.mackseoul.vcardnative.data.keys.PublicKeyValidator
import com.mackseoul.vcardnative.domain.backup.BackupSnapshot
import com.mackseoul.vcardnative.domain.images.ImageStructure
import com.mackseoul.vcardnative.domain.images.ImageStructureTest
import com.mackseoul.vcardnative.domain.profile.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID

class ManagedProfileRepositoryTest {
    private val roots = mutableListOf<File>()
    @Before fun asciiDefault() { ProfileValidator.hostValidator = AsciiHostValidator }
    @After fun cleanup() { roots.forEach { it.deleteRecursively() } }

    private val sha = "a".repeat(64)
    private val archive = "backup-work\\${UUID.randomUUID()}.archive"
    private val foreignArchive = "${UUID.randomUUID()}.archive"
    private fun draft(name: String = "Ada") = ProfileDraft.empty().copy(values = mapOf(
        ProfileField.FullName to name, ProfileField.FirstName to "Given", ProfileField.MiddleName to "Middle",
        ProfileField.LastName to "Family",
    ))
    private fun full(): ProfileDraft {
        val bytes = ImageStructureTest.png()
        val portrait = DraftImage(ImageAsset(UUID.randomUUID(), "image/png", bytes.size.toLong(), 1, 1,
            ImageStructure.digest(bytes)), bytes)
        val key = PublicKeyValidator.import(requireNotNull(javaClass.getResourceAsStream("/openpgp/valid-public.asc"))
            .use { it.readBytes() })
        return draft("Recovered Person").copy(profileImage = portrait.asset,
            pendingImages = mapOf(ImageRole.Portrait to portrait), publicKey = key.asset, pendingPublicKey = key)
    }
    private val backup: BackupSnapshot by lazy {
        val source = ProfileRepository(DurableModelStoreFiles())
        source.save(full(), null)
        source.backupSnapshot()
    }
    private fun ready(repository: ProfileRepository) = (repository.load() as ProfileRead.Ready).saved

    @Test fun nullableOperationCallbacksDoNotFallThroughToAbsentBareStore() {
        val repository = ProfileRepository.managed(DurableModelStoreFiles())
        var calls = 0
        assertNull(repository.withFileOperation { calls++; null })
        val saved = repository.save(draft(), null)
        assertNull(repository.withSavedSnapshot(saved.token) { calls++; null })
        assertEquals(2, calls)
        assertEquals(saved, ready(repository))
    }

    /** Profile saved through the managed API plus unrelated root content and an unissued archive. */
    private fun seeded(): Pair<DurableModelStoreFiles, SavedProfile> {
        val fs = DurableModelStoreFiles()
        fs.put("unrelated.txt", byteArrayOf(9))
        fs.put("other-app-dir\\keep", byteArrayOf(8))
        val repository = ProfileRepository.managed(fs)
        val first = repository.save(draft(), null)
        val saved = repository.save(draft("Grace"), first.token)
        fs.put(archive, byteArrayOf(7))
        fs.put("backup-work\\notes.txt", byteArrayOf(6))
        fs.put("backup-work\\$foreignArchive.part", byteArrayOf(5))
        fs.put("backup-work\\${foreignArchive.uppercase()}", byteArrayOf(4))
        // Setup only: make the baseline fixture durable regardless of the implementation under test.
        fs.syncDirectory("")
        fs.crash()
        return fs to saved
    }
    private fun corrupt(fs: DurableModelStoreFiles) {
        fs.put("store-v1\\INIT", byteArrayOf(1))
        fs.crash()
    }
    private fun unavailable(fs: StoreFiles) = ProfileRepository.managed(fs).load() as ProfileRead.Unavailable
    private fun evidence(fs: DurableModelStoreFiles) =
        if (fs.has("profile-operations\\evidence")) fs.list("profile-operations\\evidence") else emptySet()
    private fun relative(snapshot: Map<String, List<Byte>>, prefix: String) =
        snapshot.mapKeys { it.key.removePrefix(prefix) }
    private fun assertUnrelatedIntact(fs: DurableModelStoreFiles) {
        assertArrayEquals(byteArrayOf(9), fs.bytes("unrelated.txt"))
        assertArrayEquals(byteArrayOf(8), fs.bytes("other-app-dir\\keep"))
        assertArrayEquals(byteArrayOf(6), fs.bytes("backup-work\\notes.txt"))
        assertArrayEquals(byteArrayOf(5), fs.bytes("backup-work\\$foreignArchive.part"))
        assertArrayEquals(byteArrayOf(4), fs.bytes("backup-work\\${foreignArchive.uppercase()}"))
    }
    private fun assertDeletedOutcome(fs: DurableModelStoreFiles) {
        assertEquals(ProfileRead.Empty, ProfileRepository.managed(fs).load())
        assertFalse(fs.has("store-v1") && fs.list("store-v1").isNotEmpty())
        assertTrue(evidence(fs).isEmpty())
        assertFalse(fs.has(archive))
        assertFalse(fs.has("profile-operations\\intent"))
        assertUnrelatedIntact(fs)
    }

    // ---- Baseline regressions (fail on the pre-M1.11 repository) ----

    @Test fun failedDeleteIsNeverAPartialUnrecoverableProfile() {
        val partial = mutableListOf<String>()
        var index = 1
        while (true) {
            val (fs, saved) = seeded()
            val repository = ProfileRepository.managed(fs)
            fs.resetFaults()
            fs.failAt = index
            val failed = try { repository.delete(saved.token); false } catch (_: StoreException) { true }
            if (!failed) break
            val step = fs.events.last()
            fs.resetFaults()
            val read = ProfileRepository.managed(fs).load()
            if (read is ProfileRead.Unavailable && read.pendingOperation == null && read.recoveryToken == null) partial += step
            index++
            check(index < 5000)
        }
        assertEquals("Delete failures leaving an unrecoverable partial profile", emptyList<String>(), partial)
    }

    @Test fun interruptedDeleteNeverLeavesPartialOrResurrectableProfile() {
        var exercised = 0
        for (abrupt in listOf(false, true)) for (after in listOf(false, true)) {
            var index = 1
            while (true) {
                val (fs, saved) = seeded()
                val repository = ProfileRepository.managed(fs)
                fs.resetFaults()
                fs.failAt = index
                fs.failAfter = after
                fs.abrupt = abrupt
                val failure = try { repository.delete(saved.token); null } catch (e: Throwable) { e }
                if (failure == null) {
                    fs.resetFaults()
                    // A fault swallowed by best-effort A2 cleanup may still complete the deletion.
                    assertDeletedOutcome(fs)
                    fs.crash()
                    assertDeletedOutcome(fs)
                    if (fs.event < index) break
                    index++
                    continue
                }
                exercised++
                if (abrupt) assertTrue(failure is InjectedTermination) else assertTrue(failure is StoreException)
                fs.resetFaults()
                var deleted = false
                if (!abrupt) {
                    // Same process: the pending deletion is resumed before anything else.
                    when (val read = ProfileRepository.managed(fs).load()) {
                        ProfileRead.Empty -> deleted = true
                        is ProfileRead.Ready -> assertEquals(saved, read.saved)
                        is ProfileRead.Unavailable -> fail("Partial delete at $index/$after: ${read.message}")
                    }
                }
                fs.crash()
                when (val read = ProfileRepository.managed(fs).load()) {
                    ProfileRead.Empty -> deleted = true
                    is ProfileRead.Ready -> {
                        assertFalse("Resurrected at $index", deleted)
                        assertEquals(saved, read.saved)
                        ProfileRepository.managed(fs).delete(saved.token)
                        deleted = true
                    }
                    is ProfileRead.Unavailable -> fail("Partial delete after crash at $index/$after/$abrupt: ${read.message}")
                }
                assertTrue(deleted)
                assertDeletedOutcome(fs)
                fs.crash()
                assertDeletedOutcome(fs)
                index++
                check(index < 5000)
            }
        }
        assertTrue(exercised > 40)
    }

    @Test fun unavailableLiveStoreOffersRecoveryAndPreservesEvidence() {
        val (fs, _) = seeded()
        corrupt(fs)
        val before = relative(fs.snapshot("store-v1"), "store-v1")
        val read = unavailable(fs)
        assertNull(read.pendingOperation)
        val token = requireNotNull(read.recoveryToken)
        val repository = ProfileRepository.managed(fs)
        val recovered = repository.recover(backup, token, sha)
        assertEquals(backup.data, recovered.data)
        assertEquals(recovered, ready(ProfileRepository.managed(fs)))
        fs.crash()
        assertEquals(recovered, ready(ProfileRepository.managed(fs)))
        val id = evidence(fs).single()
        assertEquals(before, relative(fs.snapshot("profile-operations\\evidence\\$id"), "profile-operations\\evidence\\$id"))
        try { ProfileRepository.managed(fs).recover(backup, token, sha); fail("Stale token replay") } catch (_: StoreException) { }
        assertEquals(recovered, ready(ProfileRepository.managed(fs)))
        assertUnrelatedIntact(fs)
    }

    // ---- Recovery ----

    @Test fun everyRecoveryFaultBeforeAndAfterEachStepPreservesLiveOrEvidence() {
        var exercised = 0
        for (abrupt in listOf(false, true)) for (after in listOf(false, true)) {
            var index = 1
            while (true) {
                val (fs, _) = seeded()
                corrupt(fs)
                val before = relative(fs.snapshot("store-v1"), "store-v1")
                val token = requireNotNull(unavailable(fs).recoveryToken)
                val repository = ProfileRepository.managed(fs)
                fs.resetFaults()
                fs.failAt = index
                fs.failAfter = after
                fs.abrupt = abrupt
                val failure = try { repository.recover(backup, token, sha); null } catch (e: Throwable) { e }
                if (failure == null) {
                    val done = fs.event < index
                    fs.resetFaults()
                    assertTrue(checkRecoveryState(fs, before, true))
                    fs.crash()
                    assertTrue(checkRecoveryState(fs, before, true))
                    if (done) break
                    index++
                    continue
                }
                exercised++
                if (abrupt) assertTrue(failure is InjectedTermination) else assertTrue("$failure", failure is StoreException)
                label = "$index ${fs.events.last()} after=$after abrupt=$abrupt"
                fs.resetFaults()
                if (!abrupt) checkRecoveryState(fs, before, false)
                fs.crash()
                val recovered = checkRecoveryState(fs, before, false)
                if (!recovered) {
                    val again = requireNotNull(unavailable(fs).recoveryToken)
                    assertEquals(backup.data, ProfileRepository.managed(fs).recover(backup, again, sha).data)
                }
                fs.crash()
                assertTrue(checkRecoveryState(fs, before, true))
                index++
                check(index < 5000)
            }
        }
        assertTrue(exercised > 40)
    }

    private var label = ""

    /** Returns true when recovered. The unavailable store is always either live or retained evidence. */
    private fun checkRecoveryState(fs: DurableModelStoreFiles, before: Map<String, List<Byte>>, requireRecovered: Boolean): Boolean {
        val read = ProfileRepository.managed(fs).load()
        assertUnrelatedIntact(fs)
        return when (read) {
            is ProfileRead.Ready -> {
                assertEquals(backup.data, read.saved.data)
                val id = evidence(fs).single()
                assertEquals(before, relative(fs.snapshot("profile-operations\\evidence\\$id"), "profile-operations\\evidence\\$id"))
                assertTrue(fs.list("profile-operations\\candidate").isEmpty())
                true
            }
            is ProfileRead.Unavailable -> {
                assertFalse(label, requireRecovered)
                assertNull(label, read.pendingOperation)
                assertNotNull(read.recoveryToken)
                assertEquals(before, relative(fs.snapshot("store-v1"), "store-v1"))
                assertTrue(evidence(fs).isEmpty())
                assertFalse(fs.has("profile-operations\\candidate") && fs.list("profile-operations\\candidate").isNotEmpty())
                false
            }
            ProfileRead.Empty -> { fail("Recovery lost the unavailable store"); false }
        }
    }

    @Test fun staleOrForeignRecoveryTokensAndHealthyStoresAreRejected() {
        val (fs, saved) = seeded()
        assertNull((ProfileRepository.managed(fs).load() as? ProfileRead.Unavailable)?.recoveryToken)
        try { ProfileRepository.managed(fs).recover(backup, "0".repeat(64), sha); fail() } catch (_: StoreException) { }
        assertEquals(saved, ready(ProfileRepository.managed(fs)))
        corrupt(fs)
        val token = requireNotNull(unavailable(fs).recoveryToken)
        fs.put("store-v1\\INIT", byteArrayOf(2))
        val changed = requireNotNull(unavailable(fs).recoveryToken)
        assertNotEquals(token, changed)
        val before = fs.snapshot()
        for (bad in listOf(token, "", "x")) {
            try { ProfileRepository.managed(fs).recover(backup, bad, sha); fail() } catch (_: StoreException) { }
        }
        try { ProfileRepository.managed(fs).recover(backup, changed, "ABC"); fail() } catch (_: StoreException) { }
        assertEquals(before, fs.snapshot())
        val recovered = ProfileRepository.managed(fs).recover(backup, changed, sha)
        // A later profile is never removed by replaying any token from before it existed.
        val later = ProfileRepository.managed(fs).save(recovered.data.toDraft(), recovered.token)
        for (old in listOf(token, changed)) {
            try { ProfileRepository.managed(fs).recover(backup, old, sha); fail() } catch (_: StoreException) { }
        }
        assertEquals(later, ready(ProfileRepository.managed(fs)))
    }

    @Test fun invalidCandidateIsDiscardedAndUnavailableLiveStaysIntact() {
        val (fs, _) = seeded()
        corrupt(fs)
        val before = fs.snapshot()
        val token = requireNotNull(unavailable(fs).recoveryToken)
        val broken = BackupSnapshot(backup.data, emptyMap(), backup.publicKey, backup.capturedAt)
        try { ProfileRepository.managed(fs).recover(broken, token, sha); fail() } catch (_: StoreException) { }
        val after = fs.snapshot().filterKeys { !it.startsWith("profile-operations") }
        assertEquals(before, after)
        assertTrue(fs.list("profile-operations\\candidate").isEmpty())
        assertFalse(fs.has("profile-operations\\intent"))
        assertEquals(token, unavailable(fs).recoveryToken)
    }

    @Test fun uncertainReferenceIsClearedOnlyByVerifiedReplacement() {
        val fs = DurableModelStoreFiles()
        val repository = ProfileRepository.managed(fs)
        val first = repository.save(draft(), null)
        fs.resetFaults()
        val events = mutableListOf<String>()
        val recorder = DurableModelStoreFiles()
        ProfileRepository.managed(recorder).save(draft(), null).let {
            recorder.resetFaults()
            ProfileRepository.managed(recorder).save(draft("B"), it.token)
            events += recorder.events
        }
        fs.failAt = events.indexOfFirst { it.startsWith("overwrite:store-v1\\ptr") } + 1
        try { repository.save(draft("B"), first.token); fail() } catch (_: StoreException) { }
        fs.resetFaults()
        val token = requireNotNull(unavailable(fs).recoveryToken)
        try { repository.save(draft("C"), first.token); fail() } catch (_: StoreException) { }
        val recovered = repository.recover(backup, token, sha)
        assertEquals(recovered, ready(repository))
        assertEquals(2L, ProfileRepository.managed(fs).save(recovered.data.toDraft(), recovered.token).token.revision)
    }

    @Test fun evidenceIsRetainedAcrossCleanupAndNeverExportedOrBackedUp() {
        val (fs, _) = seeded()
        corrupt(fs)
        val repository = ProfileRepository.managed(fs)
        var saved = repository.recover(backup, requireNotNull(unavailable(fs).recoveryToken), sha)
        val id = evidence(fs).single()
        val retained = fs.snapshot("profile-operations\\evidence\\$id")
        repeat(4) { saved = repository.save(saved.data.toDraft().copy(values = mapOf(ProfileField.FullName to "N$it")), saved.token) }
        assertEquals(retained, fs.snapshot("profile-operations\\evidence\\$id"))
        val snapshot = repository.backupSnapshot()
        assertEquals(saved.data, snapshot.data)
        assertEquals("N3", snapshot.data.value(ProfileField.FullName))
        assertArrayEquals(backup.images.getValue(ImageRole.Portrait), snapshot.images.getValue(ImageRole.Portrait))
        assertEquals(saved, repository.withSavedSnapshot(saved.token) { it })
        // A second recovery keeps both pieces of evidence.
        fs.put("store-v1\\INIT", byteArrayOf(3))
        repository.recover(backup, requireNotNull(unavailable(fs).recoveryToken), sha)
        assertEquals(2, evidence(fs).size)
        assertEquals(retained, fs.snapshot("profile-operations\\evidence\\$id"))
    }

    // ---- Deletion ----

    @Test fun deletionScopeExcludesUnrelatedAndNeverEnumeratesTheRoot() {
        val (fs, _) = seeded()
        corrupt(fs)
        var saved = ProfileRepository.managed(fs).recover(backup, requireNotNull(unavailable(fs).recoveryToken), sha)
        assertEquals(1, evidence(fs).size)
        fs.resetFaults()
        saved = ProfileRepository.managed(fs).save(saved.data.toDraft(), saved.token)
        ProfileRepository.managed(fs).backupSnapshot()
        try { ProfileRepository.managed(fs).delete(saved.token.copy(revision = 1)); fail() } catch (_: StaleRevisionException) { }
        ProfileRepository.managed(fs).delete(saved.token)
        assertFalse(fs.events.any { it == "list:" })
        assertDeletedOutcome(fs)
        assertEquals(draft("New").values, ProfileRepository.managed(fs).save(draft("New"), null).data.profile.values)
    }

    @Test fun pendingDeletionIsReportedAndRetriedWithoutToken() {
        val (fs, saved) = seeded()
        val (recorder, recorded) = seeded()
        ProfileRepository.managed(recorder).delete(recorded.token)
        val publish = recorder.events.indexOfFirst { it.startsWith("move:store-v1->") }
        assertTrue(publish > 0)
        fs.failAt = publish + 1
        fs.abrupt = true
        try { ProfileRepository.managed(fs).delete(saved.token); fail() } catch (_: InjectedTermination) { }
        fs.resetFaults()
        val failing = object : StoreFiles by fs {
            override fun delete(path: String) = throw IOException("Injected persistent failure")
        }
        val read = ProfileRepository.managed(failing).load() as ProfileRead.Unavailable
        assertEquals("delete", read.pendingOperation)
        assertNull(read.recoveryToken)
        for (action in listOf<() -> Unit>(
            { ProfileRepository.managed(failing).save(draft("New"), null) },
            { ProfileRepository.managed(failing).backupSnapshot() },
            { ProfileRepository.managed(failing).withFileOperation { } },
        )) try { action(); fail() } catch (_: StoreException) { }
        assertFalse(fs.has("store-v1"))
        fs.crash()
        assertDeletedOutcome(fs)
    }

    @Test fun unsyncedIntentRemovalIsResyncedBeforeAnyOtherOperation() {
        val (recorder, recorded) = seeded()
        ProfileRepository.managed(recorder).delete(recorded.token)
        val removal = recorder.events.indexOfFirst { it == "delete:profile-operations\\intent" }
        assertEquals("dirsync:profile-operations", recorder.events[removal + 1])
        val (fs, saved) = seeded()
        fs.failAt = removal + 2
        try { ProfileRepository.managed(fs).delete(saved.token); fail() } catch (_: StoreException) { }
        fs.resetFaults()
        assertEquals(ProfileRead.Empty, ProfileRepository.managed(fs).load())
        assertEquals("dirsync:profile-operations", fs.events.first())
        fs.crash()
        assertDeletedOutcome(fs)
    }

    // ---- Malformed and contradictory operation records ----

    @Test fun malformedOrContradictoryRecordsBlockWithoutReset() {
        val (recorder, recorded) = seeded()
        ProfileRepository.managed(recorder).delete(recorded.token)
        val publish = recorder.events.indexOfFirst { it.startsWith("move:store-v1->") }
        fun pendingDelete(): Pair<DurableModelStoreFiles, SavedProfile> {
            val (fs, saved) = seeded()
            fs.failAt = publish + 1
            fs.abrupt = true
            try { ProfileRepository.managed(fs).delete(saved.token); fail() } catch (_: InjectedTermination) { }
            fs.crash()
            return fs to saved
        }
        val intent = pendingDelete().first.bytes("profile-operations\\intent")
        val cases = mapOf<String, (DurableModelStoreFiles) -> Unit>(
            "garbage" to { it.put("profile-operations\\intent", byteArrayOf(1, 2)) },
            "checksum" to { fs -> fs.put("profile-operations\\intent", String(intent).replace("\"revision\":2", "\"revision\":3").toByteArray()) },
            "truncated" to { it.put("profile-operations\\intent", intent.copyOf(intent.size - 3)) },
            "unknown-entry" to { it.put("profile-operations\\junk", byteArrayOf(1)) },
            "orphan-candidate" to { it.mkdirs("profile-operations\\candidate\\${UUID.randomUUID()}") },
            "bad-evidence-name" to { it.mkdirs("profile-operations\\evidence\\not-a-uuid") },
            "orphan-confirm" to { it.put("profile-operations\\confirm", intent) },
            "operations-file" to { it.put("profile-operations", byteArrayOf(1)) },
        )
        assertTrue(String(intent).contains("\"revision\":2"))
        for ((name, mutate) in cases) {
            val (fs, saved) = seeded()
            ProfileRepository.managed(fs).load()
            if (name != "operations-file") fs.mkdirs("profile-operations")
            mutate(fs)
            fs.crash()
            assertBlocked(fs, name)
            assertEquals(saved.data, (ProfileRepository(ScopedStoreFiles(fs, "store-v1")).load() as ProfileRead.Ready).saved.data)
        }
        // A delete intent replayed against a newer profile never deletes it.
        val (fs, saved) = pendingDelete()
        val record = fs.bytes("profile-operations\\intent")
        ProfileRepository(ScopedStoreFiles(fs, "store-v1")).save(draft("Newer"), saved.token)
        fs.put("profile-operations\\intent", record)
        fs.crash()
        assertBlocked(fs, "stale-delete")
        assertEquals("Newer", (ProfileRepository(ScopedStoreFiles(fs, "store-v1")).load() as ProfileRead.Ready)
            .saved.data.value(ProfileField.FullName))
    }

    private fun assertBlocked(fs: DurableModelStoreFiles, name: String) {
        val before = fs.snapshot()
        val read = ProfileRepository.managed(fs).load()
        assertTrue("$name: $read", read is ProfileRead.Unavailable && read.pendingOperation == "blocked" && read.recoveryToken == null)
        val repository = ProfileRepository.managed(fs)
        val token = RevisionToken(UUID.randomUUID().toString(), 1)
        for (action in listOf<() -> Unit>(
            { repository.save(draft("X"), null) }, { repository.delete(token) },
            { repository.recover(backup, "0".repeat(64), sha) }, { repository.backupSnapshot() },
            { repository.restore(backup, null) }, { repository.withFileOperation { } },
        )) try { action(); fail(name) } catch (_: StoreException) { }
        assertEquals(name, before, fs.snapshot())
    }

    // ---- Coverage closure: identity, capacity, unavailable causes, instances ----

    private fun identity(): ProfileDraft {
        val bytes = ImageStructureTest.png()
        val portrait = DraftImage(ImageAsset(UUID.randomUUID(), "image/png", bytes.size.toLong(), 1, 1,
            ImageStructure.digest(bytes)), bytes)
        val cover = DraftImage(portrait.asset.copy(assetId = UUID.randomUUID()), bytes)
        val key = PublicKeyValidator.import(requireNotNull(javaClass.getResourceAsStream("/openpgp/valid-public.asc"))
            .use { it.readBytes() })
        return ProfileDraft.empty().copy(
            values = mapOf(ProfileField.FullName to "Identity Person", ProfileField.Biography to "Hidden biography",
                ProfileField.Phone to "+44 20 7031 3000", ProfileField.Email to "ada@example.com"),
            visibility = ProfileDraft.empty().visibility +
                (ProfileField.Biography to Visibility(false, false)) +
                (ProfileField.Email to Visibility(false, true)) +
                (ProfileField.ProfileImage to Visibility(false, null)),
            profileImage = portrait.asset, coverImage = cover.asset,
            pendingImages = mapOf(ImageRole.Portrait to portrait, ImageRole.Cover to cover),
            publicKey = key.asset, pendingPublicKey = key,
            appearance = Appearance(Theme.light, Accent.amethyst),
            phoneRegion = "GB", includeCountryCodeWhenSharing = false,
        )
    }

    private val identityBackup: BackupSnapshot by lazy {
        val source = ProfileRepository(DurableModelStoreFiles())
        source.save(identity(), null)
        source.backupSnapshot()
    }

    private fun assertIdentity(repository: ProfileRepository, expected: BackupSnapshot) {
        val saved = ready(repository)
        assertEquals(expected.data, saved.data)
        assertEquals(1L, saved.token.revision)
        val profile = saved.data.profile
        assertEquals("Hidden biography", saved.data.value(ProfileField.Biography))
        assertEquals(Visibility(false, false), saved.data.visibility(ProfileField.Biography))
        assertEquals(Visibility(false, true), saved.data.visibility(ProfileField.Email))
        assertEquals(Visibility(false, null), saved.data.visibility(ProfileField.ProfileImage))
        assertEquals("+44 20 7031 3000", saved.data.value(ProfileField.Phone))
        assertEquals("+442070313000", profile.phoneNumberE164)
        assertEquals("GB", profile.phoneRegion)
        assertFalse(profile.includeCountryCodeWhenSharing)
        assertArrayEquals(expected.images.getValue(ImageRole.Portrait), repository.readImage(profile.profileImage!!))
        assertArrayEquals(expected.images.getValue(ImageRole.Cover), repository.readImage(profile.coverImage!!))
        assertArrayEquals(expected.publicKey, repository.readPublicKey(profile.publicKey!!))
        val again = repository.backupSnapshot()
        assertEquals(expected.data, again.data)
        assertEquals(expected.images.keys, again.images.keys)
        expected.images.forEach { (role, bytes) -> assertArrayEquals(bytes, again.images.getValue(role)) }
        assertArrayEquals(expected.publicKey, again.publicKey)
    }

    @Test fun managedRecoveryRestoresFullSnapshotIdentityImagesPrivacyAndPhoneMetadata() {
        val (fs, _) = seeded()
        corrupt(fs)
        val before = relative(fs.snapshot("store-v1"), "store-v1")
        val token = requireNotNull(unavailable(fs).recoveryToken)
        val recovered = ProfileRepository.managed(fs).recover(identityBackup, token, sha)
        assertEquals(identityBackup.data, recovered.data)
        assertIdentity(ProfileRepository.managed(fs), identityBackup)
        fs.crash()
        assertIdentity(ProfileRepository.managed(fs), identityBackup)
        val id = evidence(fs).single()
        assertEquals(before, relative(fs.snapshot("profile-operations\\evidence\\$id"), "profile-operations\\evidence\\$id"))
        assertUnrelatedIntact(fs)
    }

    @Test fun lowCapacityCandidateConstructionPreservesUnavailableLiveWithoutConfirmedOperation() {
        // Models only the A2 capacity preflight refusing the candidate. A mid-operation ENOSPC is
        // represented by the generic write/sync IOException fault matrices, not a free-space model.
        val (fs, _) = seeded()
        corrupt(fs)
        val live = fs.snapshot("store-v1")
        val token = requireNotNull(unavailable(fs).recoveryToken)
        fs.freeBytes = 0
        try { ProfileRepository.managed(fs).recover(identityBackup, token, sha); fail() } catch (_: StoreException) { }
        for (state in listOf("same-process", "crash")) {
            if (state == "crash") fs.crash()
            assertEquals(state, live, fs.snapshot("store-v1"))
            assertFalse(state, fs.has("profile-operations\\intent"))
            assertFalse(state, fs.has("profile-operations\\confirm"))
            assertTrue(state, evidence(fs).isEmpty())
            val candidates = if (fs.has("profile-operations\\candidate")) fs.list("profile-operations\\candidate") else emptySet()
            assertTrue(state, candidates.isEmpty())
            val read = unavailable(fs)
            assertNull(state, read.pendingOperation)
            assertEquals(state, token, read.recoveryToken)
        }
        fs.freeBytes = Long.MAX_VALUE
        ProfileRepository.managed(fs).recover(identityBackup, token, sha)
        assertIdentity(ProfileRepository.managed(fs), identityBackup)
        assertEquals(1, evidence(fs).size)
        assertUnrelatedIntact(fs)
    }

    @Test fun everyUnavailableLiveCauseOffersRecoveryAndKeepsExactEvidence() {
        val causes = linkedMapOf<String, (DurableModelStoreFiles) -> Unit>(
            "pending-marker" to { it.put("store-v1\\PENDING", "interrupted".toByteArray()) },
            "torn-pointer" to { fs -> fs.bytes("store-v1\\ptr\\slot-1").let { fs.put("store-v1\\ptr\\slot-1", it.copyOf(it.size / 2)) } },
            "missing-pointer" to { it.delete("store-v1\\ptr\\slot-0") },
            "corrupted-manifest" to { fs ->
                val path = "store-v1\\gen\\${fs.list("store-v1\\gen").first()}"
                fs.bytes(path).let { fs.put(path, it.copyOf(it.size / 2)) }
            },
            "corrupted-init" to { it.put("store-v1\\INIT", byteArrayOf(1)) },
            "interrupted-init" to { fs ->
                listOf("ptr", "gen", "blob", "stage").forEach { directory ->
                    fs.list("store-v1\\$directory").forEach { fs.delete("store-v1\\$directory\\$it") }
                    fs.delete("store-v1\\$directory")
                }
            },
            "unknown-store-entry" to { it.put("store-v1\\stray", byteArrayOf(1)) },
        )
        for ((name, mutate) in causes) {
            val fs = DurableModelStoreFiles()
            fs.put("unrelated.txt", byteArrayOf(9))
            val first = ProfileRepository.managed(fs).save(identity(), null)
            ProfileRepository.managed(fs).save(first.data.toDraft(), first.token)
            mutate(fs)
            // Setup only: make the injected damage durable before the simulated restart.
            listOf("ptr", "gen", "blob", "stage").map { "store-v1\\$it" }.filter { fs.has(it) }.forEach { fs.syncDirectory(it) }
            fs.syncDirectory("store-v1")
            fs.syncDirectory("")
            fs.crash()
            val live = relative(fs.snapshot("store-v1"), "store-v1")
            val read = ProfileRepository.managed(fs).load()
            assertTrue("$name: $read ${fs.snapshot("store-v1").keys}", read is ProfileRead.Unavailable)
            read as ProfileRead.Unavailable
            assertNull(name, read.pendingOperation)
            val token = requireNotNull(read.recoveryToken) { name }
            assertEquals(name, live, relative(fs.snapshot("store-v1"), "store-v1"))
            try { ProfileRepository.managed(fs).save(draft("Overwrite"), null); fail(name) } catch (_: StoreException) { }
            ProfileRepository.managed(fs).recover(identityBackup, token, sha)
            fs.crash()
            assertIdentity(ProfileRepository.managed(fs), identityBackup)
            val id = evidence(fs).single()
            assertEquals(name, live, relative(fs.snapshot("profile-operations\\evidence\\$id"), "profile-operations\\evidence\\$id"))
            assertArrayEquals(name, byteArrayOf(9), fs.bytes("unrelated.txt"))
        }
    }

    @Test fun missingOrCorruptAssetsAreUnavailableWithRecoveryAndExactEvidence() {
        val damages = linkedMapOf<String, (DurableModelStoreFiles) -> Unit>(
            "all-missing" to { fs -> fs.list("store-v1\\blob").forEach { fs.delete("store-v1\\blob\\$it") } },
            "one-missing" to { fs -> fs.delete("store-v1\\blob\\${fs.list("store-v1\\blob").first()}") },
            "one-corrupt" to { fs ->
                val path = "store-v1\\blob\\${fs.list("store-v1\\blob").first()}"
                fs.put(path, fs.bytes(path).also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() })
            },
        )
        for ((name, damage) in damages) {
            val fs = DurableModelStoreFiles()
            fs.put("unrelated.txt", byteArrayOf(9))
            val saved = ProfileRepository.managed(fs).save(identity(), null)
            damage(fs)
            // Setup only: make the injected damage durable before the simulated restart.
            fs.syncDirectory("store-v1\\blob")
            fs.crash()
            val damaged = relative(fs.snapshot("store-v1"), "store-v1")
            val repository = ProfileRepository.managed(fs)
            val read = repository.load()
            assertTrue("$name: $read", read is ProfileRead.Unavailable)
            read as ProfileRead.Unavailable
            assertNull(name, read.pendingOperation)
            val token = requireNotNull(read.recoveryToken) { name }
            assertEquals(name, damaged, relative(fs.snapshot("store-v1"), "store-v1"))
            // A stale token captured before the damage cannot be used to overwrite or delete it.
            try { repository.save(draft("Overwrite"), saved.token); fail(name) } catch (_: StoreException) { }
            try { repository.backupSnapshot(); fail(name) } catch (_: StoreException) { }
            try { repository.recover(identityBackup, "0".repeat(64), sha); fail(name) } catch (_: StoreException) { }
            assertEquals(name, damaged, relative(fs.snapshot("store-v1"), "store-v1"))
            val recovered = ProfileRepository.managed(fs).recover(identityBackup, token, sha)
            assertEquals(name, identityBackup.data, recovered.data)
            fs.crash()
            assertIdentity(ProfileRepository.managed(fs), identityBackup)
            val id = evidence(fs).single()
            assertEquals(name, damaged, relative(fs.snapshot("profile-operations\\evidence\\$id"), "profile-operations\\evidence\\$id"))
            assertArrayEquals(name, byteArrayOf(9), fs.bytes("unrelated.txt"))
            try { ProfileRepository.managed(fs).recover(identityBackup, token, sha); fail("$name replay") } catch (_: StoreException) { }
        }
    }

    @Test fun twoManagedInstancesShareTheLockAndRejectStaleRevisions() {
        val (fs, saved) = seeded()
        val first = ProfileRepository.managed(fs)
        val second = ProfileRepository.managed(fs)
        val newer = second.save(draft("Second"), saved.token)
        try { first.save(draft("First"), saved.token); fail() } catch (_: StaleRevisionException) { }
        try { first.delete(saved.token); fail() } catch (_: StaleRevisionException) { }
        try { first.withSavedSnapshot(saved.token) { fail() }; fail() } catch (_: StaleRevisionException) { }
        assertEquals(newer, ready(first))
        try { first.recover(backup, "0".repeat(64), sha); fail() } catch (_: StoreException) { }
        assertEquals(newer, ready(second))

        // The second instance cannot run while the first holds the shared root lock.
        var result: Result<SavedProfile>? = null
        first.withFileOperation {
            val thread = Thread { result = runCatching { second.save(draft("Blocked"), newer.token) } }
            thread.start()
            thread.join(300)
            assertTrue(thread.isAlive)
            assertNull(result)
            first.save(draft("Holder"), newer.token)
            thread
        }.join(10_000)
        assertTrue(result!!.exceptionOrNull() is StaleRevisionException)
        assertEquals("Holder", ready(second).data.value(ProfileField.FullName))

        // Concurrent saves from the same token: exactly one wins.
        val current = ready(first)
        val outcomes = java.util.concurrent.ConcurrentLinkedQueue<Result<SavedProfile>>()
        val start = java.util.concurrent.CountDownLatch(1)
        val threads = listOf(first, second).mapIndexed { index, repository ->
            Thread { start.await(); outcomes += runCatching { repository.save(draft("Racer $index"), current.token) } }
                .also { it.start() }
        }
        start.countDown()
        threads.forEach { it.join(10_000) }
        assertEquals(1, outcomes.count { it.isSuccess })
        assertTrue(outcomes.single { it.isFailure }.exceptionOrNull() is StaleRevisionException)
        assertEquals(outcomes.single { it.isSuccess }.getOrThrow(), ready(ProfileRepository.managed(fs)))
        fs.crash()
        assertEquals(5L, ready(ProfileRepository.managed(fs)).token.revision)
    }

    // ---- Format and API identity ----

    @Test fun managedLiveStoreIsTheUnchangedA2FormatWithIdenticalAssets() {
        val fs = DurableModelStoreFiles()
        val repository = ProfileRepository.managed(fs)
        assertEquals(ProfileRead.Empty, repository.load())
        val saved = repository.save(full(), null)
        fs.crash()
        assertEquals(saved, ready(ProfileRepository.managed(fs)))
        assertEquals(setOf("INIT", "ptr", "gen", "blob", "stage"), fs.list("store-v1"))
        assertArrayEquals("VCARD-STORE-A2-1\n".toByteArray(Charsets.US_ASCII), fs.bytes("store-v1\\INIT"))
        val bare = ProfileRepository(ScopedStoreFiles(fs, "store-v1"))
        assertEquals(saved, (bare.load() as ProfileRead.Ready).saved)
        assertArrayEquals(bare.readImage(saved.data.profile.profileImage!!), repository.readImage(saved.data.profile.profileImage!!))
        corrupt(fs)
        val recovered = ProfileRepository.managed(fs).recover(backup, requireNotNull(unavailable(fs).recoveryToken), sha)
        assertEquals(backup.data, recovered.data)
        assertEquals(backup.data.profile.profileImage, recovered.data.profile.profileImage)
        assertEquals(backup.data.profile.publicKey, recovered.data.profile.publicKey)
        assertArrayEquals(backup.images.getValue(ImageRole.Portrait), ProfileRepository.managed(fs).readImage(recovered.data.profile.profileImage!!))
        assertArrayEquals(backup.publicKey, ProfileRepository.managed(fs).readPublicKey(recovered.data.profile.publicKey!!))
        assertEquals("restore", ProfileCodec.decode(fs.bytes("store-v1\\gen\\${fs.list("store-v1\\gen").single()}")).kind)
    }

    @Test fun fileOperationsAreAllowedForOrdinaryUnavailableState() {
        val (fs, _) = seeded()
        assertEquals(5, ProfileRepository.managed(fs).withFileOperation { 5 })
        corrupt(fs)
        assertEquals(6, ProfileRepository.managed(fs).withFileOperation { 6 })
        // Reentrant: backup creation can span the snapshot and its staging.
        val repository = ProfileRepository.managed(fs)
        assertEquals(7, repository.withFileOperation { repository.withFileOperation { 7 } })
    }

    @Test fun realFilesystemRecoveryAndDeletionUseAtomicDirectoryRenames() {
        val root = File("build\\managed-profile-tests\\${UUID.randomUUID()}").also { roots += it }
        val fs = JavaTestStoreFiles(root)
        root.resolve("unrelated.txt").writeBytes(byteArrayOf(1))
        val repository = ProfileRepository.managed(fs)
        val saved = repository.save(draft(), null)
        root.resolve("store-v1\\INIT").writeBytes(byteArrayOf(1))
        val token = requireNotNull((repository.load() as ProfileRead.Unavailable).recoveryToken)
        val recovered = repository.recover(backup, token, sha)
        assertNotEquals(saved.token, recovered.token)
        assertEquals(recovered, ready(ProfileRepository.managed(JavaTestStoreFiles(root.resolve(".")))))
        assertEquals(1, root.resolve("profile-operations\\evidence").list()!!.size)
        repository.delete(recovered.token)
        assertEquals(ProfileRead.Empty, repository.load())
        assertTrue(root.resolve("unrelated.txt").isFile)
        assertEquals(0, root.resolve("profile-operations\\evidence").list()!!.size)
    }
}
