package com.mackseoul.vcardnative.data.profile

import android.os.Process
import android.test.InstrumentationTestCase
import com.mackseoul.vcardnative.data.backup.BackupArchive
import com.mackseoul.vcardnative.domain.profile.ProfileDraft
import com.mackseoul.vcardnative.domain.profile.ProfileField
import com.mackseoul.vcardnative.platform.storage.AndroidStoreFiles
import java.io.File

/**
 * Host-invoked phases; kill phases deliberately terminate instrumentation without teardown.
 * Never include this class in an unattended all-tests run.
 */
@Suppress("DEPRECATION")
class OperationRestartTest : InstrumentationTestCase() {
    private val fixture get() = File(instrumentation.targetContext.noBackupFilesDir, "m111-restart-fixture")
    private val managedRoot get() = fixture.resolve("managed")
    private fun draft(name: String) = ProfileDraft.empty().copy(values = mapOf(ProfileField.FullName to name))
    private fun repository() = ProfileRepository.managed(AndroidStoreFiles(managedRoot))
    private fun kill(label: String): Nothing {
        fixture.resolve("phase").writeText(label)
        Process.killProcess(Process.myPid())
        throw AssertionError("Process termination did not occur")
    }

    fun testSeedAndKillDuringSave() {
        assertFalse("Disposable fixture already exists; do not overwrite it.", fixture.exists())
        assertTrue(fixture.mkdirs())
        val original = repository().save(draft("Restart Original"), null)
        val source = ProfileRepository(AndroidStoreFiles(fixture.resolve("source")))
        source.save(draft("Restart Replacement"), null)
        fixture.resolve("backup.vcardnative.zip").writeBytes(BackupArchive.create(source.backupSnapshot()))
        val store = AndroidStoreFiles(managedRoot)
        val killing = object : StoreFiles by store {
            override fun syncFile(path: String) {
                store.syncFile(path)
                if (path == "store-v1\\ptr\\slot-1") kill("save-killed")
            }
        }
        ProfileRepository.managed(killing).save(draft("Unconfirmed Interrupted Save"), original.token)
        fail("Save did not reach its kill boundary")
    }

    fun testVerifySaveAndKillDuringConfirmedRecovery() {
        assertEquals("save-killed", fixture.resolve("phase").readText())
        val read = repository().load()
        assertTrue(read is ProfileRead.Unavailable)
        val unavailable = read as ProfileRead.Unavailable
        assertNotNull(unavailable.recoveryToken)
        val original = snapshot(managedRoot.resolve("store-v1"))
        fixture.resolve("expected").mkdir()
        original.forEach { (path, bytes) ->
            fixture.resolve("expected").resolve(path).also { it.parentFile!!.mkdirs(); it.writeBytes(bytes) }
        }
        val archive = fixture.resolve("backup.vcardnative.zip").readBytes()
        val candidate = BackupArchive.validate(archive)
        val store = AndroidStoreFiles(managedRoot)
        val killing = object : StoreFiles by store {
            override fun syncDirectory(path: String) {
                store.syncDirectory(path)
                if (!managedRoot.resolve("store-v1").exists()) kill("recovery-killed")
            }
        }
        ProfileRepository.managed(killing).recover(
            com.mackseoul.vcardnative.domain.backup.BackupSnapshot(
                candidate.data, candidate.images, candidate.publicKey, candidate.createdAt,
            ),
            unavailable.recoveryToken!!,
            java.security.MessageDigest.getInstance("SHA-256").digest(archive)
                .joinToString("") { "%02x".format(it.toInt() and 255) },
        )
        fail("Recovery did not reach its kill boundary")
    }

    fun testVerifyRecoveryAfterProcessDeath() {
        assertEquals("recovery-killed", fixture.resolve("phase").readText())
        val saved = (repository().load() as ProfileRead.Ready).saved
        assertEquals("Restart Replacement", saved.data.value(ProfileField.FullName))
        assertEquals(1L, saved.token.revision)
        val expected = snapshot(fixture.resolve("expected"))
        val evidence = managedRoot.resolve("profile-operations").walkTopDown()
            .filter { it.isDirectory && it.resolve("INIT").isFile && it.resolve("PENDING").isFile }.toList()
        assertEquals(1, evidence.size)
        val actual = snapshot(evidence.single())
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (path, bytes) -> assertTrue(bytes.contentEquals(actual.getValue(path))) }
        fixture.resolve("phase").writeText("recovery-verified")
    }

    fun testVerifyAfterEmulatorRebootAndKillDuringDeletion() {
        assertEquals("recovery-verified", fixture.resolve("phase").readText())
        val saved = (repository().load() as ProfileRead.Ready).saved
        assertEquals("Restart Replacement", saved.data.value(ProfileField.FullName))
        val store = AndroidStoreFiles(managedRoot)
        val killing = object : StoreFiles by store {
            override fun delete(path: String) {
                store.delete(path)
                if (path.startsWith("profile-operations\\trash\\")) kill("deletion-killed")
            }
        }
        ProfileRepository.managed(killing).delete(saved.token)
        fail("Deletion did not reach its kill boundary")
    }

    fun testVerifyDeletionReplayAndNoResurrection() {
        assertEquals("deletion-killed", fixture.resolve("phase").readText())
        assertEquals(ProfileRead.Empty, repository().load())
        assertEquals(ProfileRead.Empty, repository().load())
        assertFalse(managedRoot.resolve("store-v1").walkTopDown().any { it.isFile })
        assertFalse(managedRoot.resolve("profile-operations").walkTopDown().any { it.isFile })
        assertTrue("External backup must survive profile deletion.", fixture.resolve("backup.vcardnative.zip").isFile)
        fixture.resolve("phase").writeText("deletion-verified")
    }

    fun testCleanVerifiedDisposableFixture() {
        assertEquals("deletion-verified", fixture.resolve("phase").readText())
        assertEquals(ProfileRead.Empty, repository().load())
        assertTrue(fixture.deleteRecursively())
    }

    private fun snapshot(directory: File) = directory.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(directory).path to it.readBytes()
    }
}
