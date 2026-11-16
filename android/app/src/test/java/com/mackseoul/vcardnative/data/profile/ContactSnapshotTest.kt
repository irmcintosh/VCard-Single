package com.mackseoul.vcardnative.data.profile

import com.mackseoul.vcardnative.domain.profile.*
import com.mackseoul.vcardnative.domain.sharing.ContactExport
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ContactSnapshotTest {
    private val root = Files.createTempDirectory("vcard-snapshot-test").toFile()
    @After fun cleanup() { root.deleteRecursively() }
    private fun draft(name: String) = ProfileDraft.empty().copy(values = mapOf(ProfileField.FullName to name))

    @Test fun unsavedDraftNeverReplacesSavedExportAndStaleApprovalCannotInvokeHandoff() {
        val repository = ProfileRepository(JavaTestStoreFiles(root))
        val saved = repository.save(draft("Saved Name"), null)
        val unsaved = saved.data.toDraft().copy(values = mapOf(ProfileField.FullName to "Unsaved Secret"))
        assertEquals("Unsaved Secret", unsaved.values[ProfileField.FullName])
        val export = repository.withSavedSnapshot(saved.token) { ContactExport.from(it.data.profile) }
        assertTrue(String(export.bytes, Charsets.UTF_8).contains("Saved Name"))
        assertFalse(String(export.bytes, Charsets.UTF_8).contains("Unsaved"))
        repository.save(draft("Changed Name"), saved.token)
        var called = false
        try { repository.withSavedSnapshot(saved.token) { called = true }; fail() }
        catch (_: StaleRevisionException) { }
        assertFalse(called)
    }

    @Test fun saveCannotCommitBetweenFinalSnapshotCheckAndHandoffStart() {
        val repository = ProfileRepository(JavaTestStoreFiles(root))
        val saved = repository.save(draft("Saved Name"), null)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val committed = CountDownLatch(1)
        val attempted = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val handoff = Thread {
            try {
                repository.withSavedSnapshot(saved.token) {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    assertEquals("Saved Name", it.data.value(ProfileField.FullName))
                }
            } catch (error: Throwable) { failure.set(error) }
        }
        val save = Thread {
            try {
                attempted.countDown()
                repository.save(draft("Next Name"), saved.token)
                committed.countDown()
            } catch (error: Throwable) { failure.set(error) }
        }
        handoff.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        save.start()
        assertTrue(attempted.await(5, TimeUnit.SECONDS))
        try { assertFalse(committed.await(150, TimeUnit.MILLISECONDS)) }
        finally { release.countDown(); handoff.join(5000); save.join(5000) }
        assertNull(failure.get())
        assertEquals(0L, committed.count)
    }

    @Test fun unavailableOrEmptySavedStoreNeverInvokesExport() {
        val files = JavaTestStoreFiles(root)
        val repository = ProfileRepository(files)
        var called = false
        try { repository.withSavedSnapshot(RevisionToken("missing", 1)) { called = true }; fail() }
        catch (_: StoreException) { }
        val saved = repository.save(draft("Saved Name"), null)
        root.resolve("PENDING").writeText("uncertain")
        try { repository.withSavedSnapshot(saved.token) { called = true }; fail() }
        catch (_: StoreException) { }
        assertFalse(called)
    }
}
