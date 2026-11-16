package com.mackseoul.vcardnative.ui.profile

import android.net.Uri
import android.test.InstrumentationTestCase
import androidx.lifecycle.ViewModelStore
import com.mackseoul.vcardnative.data.profile.ProfileRead
import com.mackseoul.vcardnative.data.profile.ProfileRepository
import com.mackseoul.vcardnative.domain.images.ImageException
import com.mackseoul.vcardnative.domain.keys.PublicKeyException
import com.mackseoul.vcardnative.domain.profile.*
import com.mackseoul.vcardnative.domain.sharing.ContactExport
import com.mackseoul.vcardnative.platform.storage.AndroidStoreFiles
import java.io.File
import java.util.UUID

@Suppress("DEPRECATION")
class PublicKeyWorkflowTest : InstrumentationTestCase() {
    private lateinit var root: File
    private lateinit var repository: ProfileRepository
    private lateinit var model: ProfileViewModel
    private val viewModels = ViewModelStore()
    private var selected = ByteArray(0)
    private val uri = Uri.parse("content://test/public-key")

    override fun setUp() {
        super.setUp()
        root = File(instrumentation.targetContext.noBackupFilesDir, "public-key-test-${UUID.randomUUID()}")
        repository = ProfileRepository(AndroidStoreFiles(root))
        repository.save(ProfileDraft.empty().copy(values = mapOf(ProfileField.FullName to "Saved Public Key")), null)
        createModel()
    }

    private fun createModel() {
        run {
            model = ProfileViewModel({ repository }, { _, _ -> throw ImageException("Unused test image reader") }, {},
                readSelectedPublicKey = { selected.copyOf() })
            viewModels.put("key", model)
        }
        await { !it.loading }
    }
    override fun tearDown() {
        run { viewModels.clear() }
        root.deleteRecursively()
        super.tearDown()
    }
    private fun run(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(condition: (ProfileScreenState) -> Boolean) {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!condition(model.state.value)) {
            if (System.nanoTime() >= deadline) fail("Public-key state did not settle")
            Thread.sleep(20)
        }
    }
    private fun fixture(name: String) = instrumentation.context.assets.open("openpgp/$name").use { it.readBytes() }
    private fun import(name: String = "valid-public.asc") {
        selected = fixture(name)
        run { model.startKeyImport(); model.keyImportResult(uri) }
        await { !it.processingKey }
    }
    private fun edit() = run { model.select(ProfileDestination.Profile); model.select(ProfileDestination.Editor) }
    private fun save() {
        run { model.save() }
        await { !it.saving }
    }

    fun testImportIsDraftExplicitSaveReloadFingerprintAndExportOmissions() {
        val original = model.state.value.saved!!
        val contact = ContactExport.from(original.data.profile).bytes
        edit()
        import()
        assertNull(model.state.value.keyError)
        val asset = requireNotNull(model.state.value.draft?.publicKey)
        assertEquals(if (asset.keyVersion == 4) 40 else 64, asset.fingerprint.length)
        assertTrue(asset.fingerprint.matches(Regex("[0-9A-F]+")))
        assertTrue(model.state.value.dirty)
        assertNull((repository.load() as ProfileRead.Ready).saved.data.profile.publicKey)
        assertNull(model.state.value.saved!!.data.profile.publicKey)
        save()
        assertNull(model.state.value.saveError)
        assertEquals(asset, model.state.value.saved!!.data.profile.publicKey)
        assertTrue(contact.contentEquals(ContactExport.from(model.state.value.saved!!.data.profile).bytes))
        assertNull(model.state.value.saved!!.data.toMyCard(model.state.value.keyStatus).profile!!.publicKey)
        run { viewModels.clear() }
        createModel()
        assertEquals(asset, model.state.value.saved!!.data.profile.publicKey)
        assertTrue(requireNotNull(model.state.value.keyStatus).usable)
        edit()
        assertNull(model.state.value.draft!!.pendingPublicKey)
        assertEquals(asset, model.state.value.draft!!.publicKey)
    }

    fun testSecretMalformedFailedImportCancelAndDiscardPreservePriorKey() {
        edit()
        import()
        save()
        val original = model.state.value.saved!!
        val bytes = repository.readPublicKey(original.data.profile.publicKey!!)
        edit()
        import("secret.asc")
        assertNotNull(model.state.value.keyError)
        assertEquals(original.data.profile.publicKey, model.state.value.draft!!.publicKey)
        selected = "not OpenPGP".toByteArray()
        run { model.startKeyImport(); model.keyImportResult(uri) }
        await { !it.processingKey }
        assertNotNull(model.state.value.keyError)
        run { model.startKeyImport(); model.keyImportResult(null) }
        assertEquals(original.data.profile.publicKey, model.state.value.draft!!.publicKey)
        run { model.removePublicKey(); model.requestBack {} }
        assertTrue(model.state.value.confirmDiscard)
        run { model.leaveEditor {} }
        assertEquals(original, (repository.load() as ProfileRead.Ready).saved)
        assertTrue(bytes.contentEquals(repository.readPublicKey(original.data.profile.publicKey!!)))
    }

    fun testVisibilityStaleRevisionAndNativeHandoffFailureAreExplicit() {
        edit()
        import()
        run { model.privacy(ProfileField.PublicKey, Visibility(true, null)) }
        save()
        run { model.select(ProfileDestination.MyCard) }
        var handedOff = false
        run { model.sharePublicKey { handedOff = true; throw PublicKeyException("Test denied handoff") } }
        await { !it.handingOffKey }
        assertTrue(handedOff)
        assertEquals("Test denied handoff", model.state.value.keyError)
        assertNull(model.state.value.keyNotice)
        val saved = model.state.value.saved!!
        repository.save(saved.data.toDraft().copy(visibility = saved.data.profile.visibility +
            (ProfileField.PublicKey to Visibility(false, null))), saved.token)
        handedOff = false
        run { model.sharePublicKey { handedOff = true } }
        await { !it.handingOffKey }
        assertFalse(handedOff)
        assertNotNull(model.state.value.keyError)
        run { model.reload() }
        await { !it.loading }
        assertFalse(model.startPublicKeySave())
        assertNull(model.state.value.saved!!.data.toMyCard(model.state.value.keyStatus).profile!!.publicKey)
    }

    fun testSaveDestinationCancelAndCompletedWriteUseOnlySavedValidatedBytes() {
        edit()
        import()
        run { model.privacy(ProfileField.PublicKey, Visibility(true, null)) }
        save()
        run { model.select(ProfileDestination.MyCard) }
        val saved = model.state.value.saved!!
        val expected = repository.readPublicKey(saved.data.profile.publicKey!!)
        run {
            assertTrue(model.startPublicKeySave())
            model.publicKeySaveResult(null) { _, _ -> fail("Cancelled destination wrote bytes") }
        }
        assertNull(model.state.value.keyNotice)
        var output: ByteArray? = null
        run {
            assertTrue(model.startPublicKeySave())
            model.publicKeySaveResult(uri) { destination, export ->
                assertEquals(uri, destination)
                output = export.bytes
            }
        }
        await { !it.handingOffKey }
        assertTrue(expected.contentEquals(requireNotNull(output)))
        assertEquals(saved, (repository.load() as ProfileRead.Ready).saved)
        assertNotNull(model.state.value.keyNotice)
    }

    fun testFailedSaveRetainsImportedDraftAndCorruptReloadExposesNoSavedKey() {
        edit()
        import()
        val pending = requireNotNull(model.state.value.draft)
        val prior = requireNotNull(model.state.value.saved)
        root.resolve("PENDING").writeText("uncertain")
        save()
        assertNotNull(model.state.value.saveError)
        assertNotNull(model.state.value.unavailable)
        assertEquals(pending, model.state.value.draft)
        assertEquals(prior, model.state.value.saved)
        assertNull(model.state.value.keyNotice)
        run { model.reload() }
        await { !it.loading }
        assertNull(model.state.value.saved)
        assertNull(model.state.value.keyStatus)
        assertFalse(model.startPublicKeySave())
    }
}
