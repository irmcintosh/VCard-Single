package com.mackseoul.vcardnative.data.profile

import com.mackseoul.vcardnative.domain.profile.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

class ProfileRepositoryTest {
    @Test fun commitNotificationsFollowSuccessfulSavesRestoresAndDeletionOnly() {
        var notifications = 0
        val store = files()
        val repository = ProfileRepository(store) { notifications++ }
        val saved = repository.save(draft(), null)
        assertEquals(1, notifications)
        val edited = saved.data.toDraft().copy(values = saved.data.profile.values +
            (ProfileField.ProfessionalTitle to "Uncommitted"))
        repository.load()
        val snapshot = repository.backupSnapshot()
        assertEquals(1, notifications)
        assertThrows(ValidationException::class.java) {
            repository.save(edited.copy(values = edited.values - ProfileField.FullName), saved.token)
        }
        assertEquals(1, notifications)
        store.freeBytes = 0
        assertThrows(StoreException::class.java) { repository.save(edited, saved.token) }
        assertEquals(1, notifications)
        store.freeBytes = Long.MAX_VALUE
        val changed = repository.save(edited, saved.token)
        assertEquals(2, notifications)
        val restored = repository.restore(snapshot, changed.token)
        assertEquals(3, notifications)
        assertThrows(StaleRevisionException::class.java) { repository.delete(changed.token) }
        assertEquals(3, notifications)
        repository.delete(restored.token)
        assertEquals(4, notifications)
        assertEquals(ProfileRead.Empty, repository.load())
    }

    @Test fun notificationFailureCannotTurnCommittedSaveIntoFailure() {
        val repository = ProfileRepository(files()) { throw IllegalStateException("Widget unavailable") }
        val saved = repository.save(draft(), null)
        assertEquals(saved, ready(repository))
    }

    @Test fun notificationProjectionUsesSavedTitleAndPhotoVisibility() {
        val store = files()
        lateinit var repository: ProfileRepository
        val projections = mutableListOf<com.mackseoul.vcardnative.ui.widget.QuickCardWidgetState>()
        repository = ProfileRepository(store) {
            projections += com.mackseoul.vcardnative.ui.widget.QuickCardWidgetProjection.from(repository.load())
        }
        val bytes = com.mackseoul.vcardnative.domain.images.ImageStructureTest.png()
        val asset = ImageAsset(UUID.randomUUID(), "image/png", bytes.size.toLong(), 1, 1, sha256(bytes))
        val saved = repository.save(draft().copy(values = draft().values +
            (ProfileField.ProfessionalTitle to "Saved title"), profileImage = asset,
            pendingImages = mapOf(ImageRole.Portrait to DraftImage(asset, bytes))), null)
        val shown = projections.last() as com.mackseoul.vcardnative.ui.widget.QuickCardWidgetState.Ready
        assertEquals("Saved title", shown.professionalTitle)
        assertEquals(asset, shown.photo)
        val hidden = saved.data.toDraft().copy(visibility = saved.data.profile.visibility + mapOf(
            ProfileField.ProfessionalTitle to Visibility(false, true),
            ProfileField.ProfileImage to Visibility(false, null)))
        assertEquals(1, projections.size)
        repository.save(hidden, saved.token)
        val updated = projections.last() as com.mackseoul.vcardnative.ui.widget.QuickCardWidgetState.Ready
        assertNull(updated.professionalTitle)
        assertNull(updated.photo)
        assertEquals(2, projections.size)
    }

    @Test fun hostedProfileReplacementAndClearingUseSuccessfulProfileSaveOnly() {
        val repository = ProfileRepository(files())
        val originalUrl = "https://example.com/original"
        val original = repository.save(draft().copy(values = draft().values +
            (ProfileField.HostedProfileUrl to originalUrl)), null)
        val replacementUrl = "HTTPS://EXAMPLE.COM/Replacement/%2f"
        val edited = original.data.toDraft().copy(values = original.data.profile.values +
            (ProfileField.HostedProfileUrl to replacementUrl))
        assertEquals(originalUrl, ready(repository).data.value(ProfileField.HostedProfileUrl))
        val replacement = repository.save(edited, original.token)
        assertEquals(replacementUrl, ready(repository).data.value(ProfileField.HostedProfileUrl))
        val invalid = replacement.data.toDraft().copy(values = replacement.data.profile.values +
            (ProfileField.HostedProfileUrl to "http://example.com/not-https"))
        assertThrows(ValidationException::class.java) { repository.save(invalid, replacement.token) }
        assertEquals(replacement, ready(repository))
        assertEquals("http://example.com/not-https", invalid.values[ProfileField.HostedProfileUrl])
        val cleared = repository.save(replacement.data.toDraft().copy(values = replacement.data.profile.values +
            (ProfileField.HostedProfileUrl to "")), replacement.token)
        assertNull(cleared.data.value(ProfileField.HostedProfileUrl))
        assertEquals(cleared, ready(repository))
        assertEquals("Ada", cleared.data.value(ProfileField.FullName))
    }

    @Test fun failedStorageSaveKeepsSocialDraftAndPreviouslySavedData() {
        val store = files()
        val repository = ProfileRepository(store)
        val first = repository.save(draft(), null)
        val entry = SocialProfile(UUID.randomUUID(), "github", "My custom GitHub label",
            "https://example.com/Exact/%2f", true, true)
        val edited = first.data.toDraft().withSocialProfiles(listOf(entry))
        store.freeBytes = 0
        assertThrows(StoreException::class.java) { repository.save(edited, first.token) }
        assertEquals(listOf(entry), edited.socialProfiles)
        assertEquals(first, ready(repository))
        store.freeBytes = Long.MAX_VALUE
        val saved = repository.save(edited, first.token)
        assertEquals(listOf(entry), saved.data.profile.socialProfiles)
        assertEquals("My custom GitHub label", ready(repository).data.profile.socialProfiles.single().label)
    }

    @Test fun removingLegacyEntryWithNonDefaultVisibilityCanBeSaved() {
        val repository = ProfileRepository(files())
        val first = repository.save(draft().copy(
            values = draft().values + (ProfileField.LinkedIn to "https://example.com/legacy"),
            visibility = draft().visibility + (ProfileField.LinkedIn to Visibility(false, true)),
        ), null)
        val replacement = SocialProfile(UUID.randomUUID(), "instagram", "Instagram",
            "https://example.com/new", true, true)
        val changed = first.data.toDraft().withSocialProfiles(listOf(replacement))
        assertEquals(Visibility(false, true), changed.visibility[ProfileField.LinkedIn])
        val saved = repository.save(changed, first.token)
        assertEquals(saved, ready(repository))
        assertEquals(listOf(replacement), saved.data.profile.socialProfiles)
        assertNull(saved.data.value(ProfileField.LinkedIn))
        assertEquals("Ada", saved.data.value(ProfileField.FullName))
    }

    @Test fun invalidSocialSavePreservesDraftAndCommittedProfile() {
        val repository = ProfileRepository(files())
        val saved = repository.save(draft(), null)
        val incomplete = SocialProfile(UUID.randomUUID(), "", "", " ", true, false)
        val edited = saved.data.toDraft().withSocialProfiles(listOf(incomplete))
        assertThrows(ValidationException::class.java) { repository.save(edited, saved.token) }
        assertEquals(listOf(incomplete), edited.socialProfiles)
        assertEquals(saved, ready(repository))
    }

    @Test fun collectionEditorMappingPreservesIdsDuplicatesAndUnrelatedFields() {
        val first = SocialProfile(UUID.randomUUID(), "linkedin", "Work", "HTTPS://EXAMPLE.COM/Card", true, true)
        val second = first.copy(id = UUID.randomUUID(), label = "Personal", includeInContactExport = false)
        val initial = draft().withSocialProfiles(listOf(first, second))
        val moved = initial.moveSocialProfile(second.id, -1)
        assertEquals(listOf(second.id, first.id), moved.socialProfiles.map { it.id })
        assertEquals(initial.values[ProfileField.FullName], moved.values[ProfileField.FullName])
        assertEquals(listOf(first.id, second.id), initial.socialProfiles.map { it.id })
        val repository = ProfileRepository(files())
        val saved = repository.save(moved, null)
        assertEquals(listOf(second, first), ready(repository).data.profile.socialProfiles)
        val removed = saved.data.toDraft().withSocialProfiles(listOf(first))
        repository.save(removed, saved.token)
        assertEquals(listOf(first), ready(repository).data.profile.socialProfiles)
        assertEquals("Ada", ready(repository).data.value(ProfileField.FullName))
    }

    private val roots = mutableListOf<File>()
    @Before fun asciiDefault() { ProfileValidator.hostValidator = AsciiHostValidator }
    @After fun cleanup() { roots.forEach { it.deleteRecursively() } }

    private fun files(): JavaTestStoreFiles {
        val root = File("build\\profile-tests\\${UUID.randomUUID()}")
        roots += root
        return JavaTestStoreFiles(root)
    }
    private fun draft(name: String = "Ada") = ProfileDraft.empty().copy(values = mapOf(ProfileField.FullName to name))
    private fun ready(repository: ProfileRepository) = (repository.load() as ProfileRead.Ready).saved

    @Test fun explicitFirstSaveReloadAndStableIdentityRevisions() {
        val files = files()
        val repository = ProfileRepository(files)
        assertEquals(ProfileRead.Empty, repository.load())
        assertTrue(files.snapshot().isEmpty())
        val first = repository.save(draft(), null)
        assertEquals(1L, first.token.revision)
        assertEquals(first, ready(ProfileRepository(files)))
        val second = repository.save(draft("Grace"), first.token)
        assertEquals(first.data.profile.profileId, second.data.profile.profileId)
        assertEquals(first.token.generationId, second.token.generationId)
        assertEquals(first.data.metadata.createdAt, second.data.metadata.createdAt)
        assertEquals(2L, second.token.revision)
        assertEquals(second, ready(ProfileRepository(files)))
        assertEquals(second.data.toDraft(), ProfileValidator.validate(draft("Grace")).normalized)
    }

    @Test fun profileDeletionRejectsStaleRevisionAndClearsAllGenerations() {
        val files = files()
        val repository = ProfileRepository(files)
        val first = repository.save(draft(), null)
        val latest = repository.save(draft("Grace"), first.token)
        val beforeStaleDelete = files.snapshot()

        try {
            repository.delete(first.token)
            fail("A stale revision must not be deleted")
        } catch (_: StaleRevisionException) { }
        assertSnapshot(beforeStaleDelete, files.snapshot())

        repository.delete(latest.token)
        assertTrue(files.snapshot().isEmpty())
        assertEquals(ProfileRead.Empty, repository.load())
        assertEquals(ProfileRead.Empty, ProfileRepository(JavaTestStoreFiles(files.root.resolve("."))).load())
    }

    @Test fun staleAndInvalidSavesPerformNoWrites() {
        val files = files()
        val repository = ProfileRepository(files)
        val first = repository.save(draft(), null)
        val before = files.snapshot()
        for (token in listOf(null, RevisionToken(UUID.randomUUID().toString(), 1), first.token.copy(revision = 0))) {
            try { repository.save(draft("Other"), token); fail() } catch (_: StaleRevisionException) { }
        }
        try { repository.save(draft(""), first.token); fail() } catch (e: ValidationException) {
            assertTrue(e.errors.containsKey(ProfileField.FullName))
            assertFalse(e.message!!.contains("Ada"))
        }
        assertSnapshot(before, files.snapshot())
    }

    @Test fun serializedRepositoryInstancesRejectStaleRevision() {
        val files = files()
        val firstRepository = ProfileRepository(files)
        val initial = firstRepository.save(draft(), null)
        val secondFiles = JavaTestStoreFiles(files.root.resolve("."))
        assertSame(files.coordinationLock, secondFiles.coordinationLock)
        val secondRepository = ProfileRepository(secondFiles)
        val results = java.util.Collections.synchronizedList(mutableListOf<Any>())
        val threads = List(2) { index -> Thread {
            try {
                results += (if (index == 0) firstRepository else secondRepository)
                    .save(draft("Name $index"), initial.token)
            } catch (e: StaleRevisionException) { results += e }
        } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(1, results.count { it is SavedProfile })
        assertEquals(1, results.count { it is StaleRevisionException })
        assertEquals(2L, ready(firstRepository).token.revision)
    }

    @Test fun privacyAndAllTextFieldsSurviveReloadAndClearing() {
        val files = files()
        val preferences = ProfileDraft.empty().visibility + (ProfileField.Email to Visibility(false, true))
        val values = mapOf(
            ProfileField.FullName to "  A\u0301da  ", ProfileField.ProfessionalTitle to "Developer",
            ProfileField.Organization to "Organization", ProfileField.Biography to "Line\r\nNext",
            ProfileField.Location to "City", ProfileField.Phone to "+1 (212) 555-0123",
            ProfileField.Email to "Ada+tag@Example.COM", ProfileField.Website to "http://Example.com/é",
            ProfileField.LinkedIn to "https://example.com/linked", ProfileField.GitHub to "https://example.com/git",
            ProfileField.OtherProfessional to "https://example.com/other",
            ProfileField.HostedProfileUrl to "https://example.com/card"
        )
        val repository = ProfileRepository(files)
        val saved = repository.save(ProfileDraft(values, preferences), null)
        assertEquals(saved, ready(ProfileRepository(files)))
        assertEquals("Áda", saved.data.value(ProfileField.FullName))
        assertEquals("Ada+tag@example.com", saved.data.value(ProfileField.Email))
        assertEquals("http://Example.com/é", saved.data.value(ProfileField.Website))
        assertEquals("https://example.com/card", saved.data.value(ProfileField.HostedProfileUrl))
        assertEquals("http://Example.com/é", ready(repository).data.value(ProfileField.Website))
        assertEquals(
            listOf("linkedin", "github", "otherProfessional"),
            saved.data.profile.socialProfiles.map { it.platform },
        )
        val persistedIds = saved.data.profile.socialProfiles.map { it.id }
        assertEquals(persistedIds, ready(repository).data.profile.socialProfiles.map { it.id })
        assertEquals(Visibility(false, true), saved.data.visibility(ProfileField.Email))
        val cleared = repository.save(saved.data.toDraft().copy(values = saved.data.profile.values - ProfileField.Email), saved.token)
        assertNull(cleared.data.value(ProfileField.Email))
        assertEquals(Visibility(false, true), ready(repository).data.visibility(ProfileField.Email))
    }

    @Test fun repeatedSocialPlatformsAndUrlsKeepOrderIdsVisibilityAndExactText() {
        val repository = ProfileRepository(files())
        val draft = ProfileDraft.empty().copy(
            values = mapOf(ProfileField.FullName to "Ada"),
            socialProfiles = listOf(
                SocialProfile(UUID.fromString("00000000-0000-4000-8000-000000000001"),
                    "linkedin", "LinkedIn work", "HTTPS://EXAMPLE.COM/a", true, false),
                SocialProfile(UUID.fromString("00000000-0000-4000-8000-000000000002"),
                    "linkedin", "LinkedIn personal", "HTTPS://EXAMPLE.COM/a", false, true),
                SocialProfile(UUID.fromString("00000000-0000-4000-8000-000000000003"),
                    "custom platform", "Custom", "https://example.com/a", true, true),
            ),
        )
        val saved = repository.save(draft, null)
        val restored = ready(repository)

        assertEquals(draft.socialProfiles, restored.data.profile.socialProfiles)
        assertEquals(draft.socialProfiles.map { it.id }, restored.data.profile.socialProfiles.map { it.id })
        assertEquals(listOf("HTTPS://EXAMPLE.COM/a", "HTTPS://EXAMPLE.COM/a", "https://example.com/a"),
            restored.data.profile.socialProfiles.map { it.url })
        assertEquals(saved, restored)
    }

    @Test fun lowCapacityDoesNotInitializeOrAlterExistingStore() {
        val files = files()
        files.freeBytes = 0
        try { ProfileRepository(files).save(draft(), null); fail() } catch (_: StoreException) { }
        assertTrue(files.snapshot().isEmpty())
        files.freeBytes = Long.MAX_VALUE
        val repository = ProfileRepository(files)
        val saved = repository.save(draft(), null)
        val before = files.snapshot()
        files.freeBytes = 0
        try { repository.save(draft("Grace"), saved.token); fail() } catch (_: StoreException) { }
        assertSnapshot(before, files.snapshot())
        assertEquals(saved, ready(repository))
    }

    @Test fun neverTreatPartialInitializationAsFirstRun() {
        for ((path, bytes) in listOf(
            "INIT" to byteArrayOf(1), "stage\\orphan" to byteArrayOf(2),
            "gen\\orphan.manifest" to byteArrayOf(3), "ptr\\slot-0" to byteArrayOf(4)
        )) {
            val files = files()
            files.put(path, bytes)
            val before = files.snapshot()
            val repository = ProfileRepository(files)
            assertTrue(repository.load() is ProfileRead.Unavailable)
            try { repository.save(draft(), null); fail() } catch (_: StoreException) { }
            assertSnapshot(before, files.snapshot())
        }
    }

    @Test fun corruptActiveOrPredecessorOrPointerNeverFallsBack() {
        for (target in listOf("active", "predecessor", "slot-0", "slot-1", "INIT")) {
            val files = files()
            val repository = ProfileRepository(files)
            val first = repository.save(draft(), null)
            repository.save(draft("Grace"), first.token)
            val pointer0 = pointerPayload(files.bytes("ptr\\slot-0"))
            val pointer1 = pointerPayload(files.bytes("ptr\\slot-1"))
            val path = when (target) {
                "active" -> "gen\\${pointer1.string("manifestId")}.manifest"
                "predecessor" -> "gen\\${pointer0.string("manifestId")}.manifest"
                "INIT" -> "INIT"
                else -> "ptr\\$target"
            }
            files.put(path, files.bytes(path).let { it.copyOf(it.size / 2) })
            val before = files.snapshot()
            assertTrue(ProfileRepository(files).load() is ProfileRead.Unavailable)
            assertSnapshot(before, files.snapshot())
        }
    }

    @Test fun missingAndConflictingPointerEvidenceIsUnavailable() {
        for (mode in listOf("missing", "missing-current", "same-sequence", "wrong-slot", "pending")) {
            val files = files()
            val repository = ProfileRepository(files)
            val first = repository.save(draft(), null)
            repository.save(draft("Grace"), first.token)
            when (mode) {
                "missing" -> files.remove("ptr\\slot-0")
                "missing-current" -> files.remove("ptr\\slot-1")
                "same-sequence", "wrong-slot" -> files.put("ptr\\slot-0", files.bytes("ptr\\slot-1"))
                else -> files.put("PENDING", "interrupted".toByteArray())
            }
            assertTrue(ProfileRepository(files).load() is ProfileRead.Unavailable)
        }
    }

    @Test fun cleanReconciliationRetainsOnlyActiveAndPredecessorIdempotently() {
        val files = files()
        val repository = ProfileRepository(files)
        var saved = repository.save(draft(), null)
        repeat(5) {
            val oldPointers = files.snapshot().filterKeys { it.startsWith("gen\\") }
            saved = repository.save(draft("Name $it"), saved.token)
            files.snapshot().filterKeys { it.startsWith("gen\\") }.forEach { (path, bytes) ->
                oldPointers[path]?.let { assertArrayEquals(it, bytes) }
            }
            assertEquals(2, files.root.resolve("gen").list()!!.size)
            val before = files.snapshot()
            assertEquals(saved, ready(repository))
            assertEquals(saved, ready(repository))
            assertSnapshot(before, files.snapshot())
        }
        assertEquals(2, files.root.resolve("gen").list()!!.size)
        assertEquals(saved, ready(repository))
    }

    @Test fun orphanStageAndManifestEvidencePreventsCleanup() {
        for (evidence in listOf("stage", "manifest")) {
            val files = files()
            val repository = ProfileRepository(files)
            var saved = repository.save(draft(), null)
            saved = repository.save(draft("Second"), saved.token)
            val before = files.snapshot().filterKeys { it.startsWith("gen\\") }
            if (evidence == "stage") files.put("stage\\orphan\\evidence", byteArrayOf(1))
            else files.put("gen\\${UUID.randomUUID()}.manifest", before.values.first())
            repeat(3) { saved = repository.save(draft("Name $it"), saved.token) }
            assertEquals(saved, ready(repository))
            before.forEach { (path, bytes) -> assertArrayEquals(bytes, files.bytes(path)) }
            assertTrue(files.root.resolve("gen").list()!!.size >= 4)
        }
    }

    @Test fun cleanupFaultsNeverAffectReferencesAndReconciliationRetriesIdempotently() {
        val source = files()
        val repository = ProfileRepository(source)
        var saved = repository.save(draft(), null)
        source.put("stage\\orphan\\evidence", byteArrayOf(1))
        repeat(3) { saved = repository.save(draft("Name $it"), saved.token) }
        source.remove("stage\\orphan\\evidence")
        val baseline = source.snapshot()
        source.resetFaults()
        assertEquals(saved, ready(repository))
        val events = source.events.toList()
        val deleteIndices = events.indices.filter {
            events[it].startsWith("delete:gen\\") || events[it] == "dirsync:gen"
        }
        assertTrue(deleteIndices.isNotEmpty())
        for (after in listOf(false, true)) for (index in deleteIndices) {
            val files = files()
            files.restore(baseline)
            // Snapshot contains files only, so explicitly recreate the empty stage directory.
            files.root.resolve("stage\\orphan").mkdirs()
            files.failAt = index + 1
            files.failAfter = after
            assertEquals(saved, ready(ProfileRepository(files)))
            files.resetFaults()
            assertEquals(saved, ready(ProfileRepository(files)))
            assertEquals(2, files.root.resolve("gen").list()!!.size)
            baseline.filterKeys { it.startsWith("ptr\\") }.forEach { (path, bytes) ->
                assertArrayEquals(bytes, files.bytes(path))
            }
        }
    }

    @Test fun initializedWithoutCommitIsNotEmpty() {
        val files = files()
        files.put("INIT", "VCARD-STORE-A2-1\n".toByteArray(Charsets.US_ASCII))
        listOf("ptr", "gen", "blob", "stage").forEach { files.root.resolve(it).mkdirs() }
        assertTrue(ProfileRepository(files).load() is ProfileRead.Unavailable)
        try { ProfileRepository(files).save(draft(), null); fail() } catch (_: StoreException) { }
        assertEquals(1, files.snapshot().size)
    }

    @Test fun preciseEveryFilesystemFaultBeforeAndAfterReferencePreservesEvidence() {
        val source = files()
        val sourceRepository = ProfileRepository(source)
        val initial = sourceRepository.save(draft(), null)
        val baseline = source.snapshot()
        source.resetFaults()
        sourceRepository.save(draft("Grace"), initial.token)
        // Cleanup is deliberately best effort and is outside commit's failure contract.
        val steps = source.events.take(source.events.indexOf("delete:PENDING") + 2)
        assertTrue(steps.any { it.startsWith("overwrite:") })
        for (after in listOf(false, true)) for (index in steps.indices) {
            val files = files()
            files.restore(baseline)
            files.resetFaults()
            files.failAt = index + 1
            files.failAfter = after
            val repository = ProfileRepository(files)
            try {
                repository.save(draft("Grace"), initial.token)
                fail("Missing failure at ${steps[index]}, after=$after")
            } catch (_: StoreException) { }
            files.resetFaults()
            baseline.filterKeys { it.startsWith("gen\\") || it == "INIT" }.forEach { (path, bytes) ->
                assertArrayEquals("Immutable evidence changed at ${steps[index]}", bytes, files.bytes(path))
            }
            val state = repository.load()
            assertTrue(state is ProfileRead.Ready || state is ProfileRead.Unavailable)
            if (state is ProfileRead.Ready) assertEquals(initial, state.saved)
            val restarted = ProfileRepository(files).load()
            assertTrue(restarted is ProfileRead.Ready || restarted is ProfileRead.Unavailable)
            if (restarted is ProfileRead.Ready) assertEquals(initial, restarted.saved)
        }
    }

    @Test fun firstSaveFaultAtEveryStepNeverExposesEmptyAfterInitialization() {
        val baselineFiles = files()
        val baselineRepository = ProfileRepository(baselineFiles)
        baselineFiles.resetFaults()
        baselineRepository.save(draft(), null)
        val steps = baselineFiles.events.toList()
        for (after in listOf(false, true)) for (index in steps.indices) {
            val files = files()
            files.resetFaults()
            files.failAt = index + 1
            files.failAfter = after
            try { ProfileRepository(files).save(draft(), null); fail() } catch (_: StoreException) { }
            files.resetFaults()
            val snapshot = files.snapshot()
            val loaded = ProfileRepository(files).load()
            if (snapshot.isEmpty() && files.root.list()!!.isEmpty()) assertEquals(ProfileRead.Empty, loaded)
            else assertTrue("Partial init became readable at ${steps[index]}", loaded is ProfileRead.Unavailable)
        }
    }

    @Test fun tornReferenceUpdateIsUnavailableAndDoesNotDeletePriorGenerations() {
        val files = files()
        val repository = ProfileRepository(files)
        val saved = repository.save(draft(), null)
        val immutable = files.snapshot().filterKeys { it.startsWith("gen\\") }
        files.tearPointer = true
        try { repository.save(draft("Grace"), saved.token); fail() } catch (_: StoreException) { }
        files.resetFaults()
        assertTrue(ProfileRepository(files).load() is ProfileRead.Unavailable)
        immutable.forEach { (path, bytes) -> assertArrayEquals(bytes, files.bytes(path)) }
        assertTrue(files.bytes("ptr\\slot-0").isNotEmpty())
    }

    @Test fun pendingRemovalSyncFaultIsUnavailableAcrossRepositoryInstances() {
        val source = files()
        val sourceRepository = ProfileRepository(source)
        val initial = sourceRepository.save(draft(), null)
        val baseline = source.snapshot()
        source.resetFaults()
        sourceRepository.save(draft("Grace"), initial.token)
        val deleteIndex = source.events.indexOf("delete:PENDING")
        assertTrue(deleteIndex > 0)
        for (after in listOf(false, true)) {
            val files = files()
            files.restore(baseline)
            files.resetFaults()
            files.failAt = deleteIndex + 2 // Root directory fsync immediately after marker removal.
            files.failAfter = after
            val repository = ProfileRepository(files)
            try { repository.save(draft("Grace"), initial.token); fail() } catch (_: StoreException) { }
            files.resetFaults()
            assertTrue(files.bytes("PENDING").isNotEmpty())
            assertTrue(repository.load() is ProfileRead.Unavailable)
            assertTrue(ProfileRepository(JavaTestStoreFiles(files.root)).load() is ProfileRead.Unavailable)
            // Even loss of evidence cannot make another same-process Activity expose success.
            files.remove("PENDING")
            assertTrue(ProfileRepository(JavaTestStoreFiles(files.root)).load() is ProfileRead.Unavailable)
        }
    }

    private fun pointerPayload(bytes: ByteArray): Map<String, Any?> =
        StrictJson.decode(bytes).jsonObject("payload", "sha256")["payload"].jsonObject(
            "magic", "format", "sequence", "manifestId", "manifestLength", "manifestSha256", "generationId", "revision", "kind"
        )

    private fun assertSnapshot(before: Map<String, ByteArray>, after: Map<String, ByteArray>) {
        assertEquals(before.keys, after.keys)
        before.forEach { (path, bytes) -> assertArrayEquals(bytes, after[path]) }
    }
}
