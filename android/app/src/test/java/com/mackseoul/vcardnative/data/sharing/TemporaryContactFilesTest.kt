package com.mackseoul.vcardnative.data.sharing

import com.mackseoul.vcardnative.domain.sharing.ContactExport
import com.mackseoul.vcardnative.domain.sharing.PublicKeyExport
import com.mackseoul.vcardnative.domain.sharing.QrPng
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class TemporaryContactFilesTest {
    private val root = Files.createTempDirectory("vcard-contact-test").toFile()
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun immutableGenericFilesAndSameBootLeaseSurviveExpiryAndNewInstances() {
        val store = TemporaryContactFiles(root, 4)
        val first = store.create(byteArrayOf(1, 2, 3))
        val second = store.create(byteArrayOf(4))
        assertEquals(ContactExport.FILE_NAME, first.name)
        assertNotEquals(first.parent, second.parent)
        first.parentFile!!.setLastModified(1)
        TemporaryContactFiles(root, 4).cleanup()
        assertArrayEquals(byteArrayOf(1, 2, 3), first.readBytes())
        assertArrayEquals(byteArrayOf(4), second.readBytes())
        TemporaryContactFiles(root, 5).cleanup()
        assertFalse(first.exists())
        assertFalse(second.exists())
    }

    @Test fun orphanAfter24HoursRemovedButRecentUnknownAndCorruptEvidenceRetained() {
        fun orphan() = root.resolve("payload").resolve(UUID.randomUUID().toString()).also { it.mkdirs() }
        val old = orphan().also { it.resolve(ContactExport.FILE_NAME).writeText("old"); it.setLastModified(1) }
        val recent = orphan().also { it.resolve(ContactExport.FILE_NAME).writeText("recent") }
        val unknown = orphan().also { it.resolve("unknown").writeText("evidence"); it.setLastModified(1) }
        val leased = TemporaryContactFiles(root, 4).create(byteArrayOf(5))
        root.resolve("leases").resolve(leased.parentFile!!.name).writeText("corrupt")
        TemporaryContactFiles(root, 5).cleanup()
        assertFalse(old.exists())
        assertTrue(recent.exists())
        assertTrue(unknown.resolve("unknown").exists())
        assertTrue(leased.exists())
    }

    @Test fun knownFailedHandoffDiscardsOnlyItsOwnContact() {
        val store = TemporaryContactFiles(root, 4)
        val first = store.create(byteArrayOf(1))
        val second = store.create(byteArrayOf(2))
        store.discard(first)
        assertFalse(first.exists())
        assertTrue(second.exists())
        assertFalse(root.resolve("leases").resolve(first.parentFile!!.name).exists())
    }

    @Test fun publicKeyPayloadUsesSeparateGenericNameAndSameGrantLifetimeSafeguards() {
        val directory = root.resolve("public-key-share")
        val store = TemporaryContactFiles(directory, 4, PublicKeyExport.FILE_NAME)
        val bytes = "public-only test bytes".toByteArray()
        val first = store.create(bytes)
        assertEquals(PublicKeyExport.FILE_NAME, first.name)
        assertArrayEquals(bytes, first.readBytes())
        first.parentFile!!.setLastModified(1)
        TemporaryContactFiles(directory, 4, PublicKeyExport.FILE_NAME).cleanup()
        assertTrue(first.exists())
        TemporaryContactFiles(directory, 5, PublicKeyExport.FILE_NAME).cleanup()
        assertFalse(first.exists())
        try { TemporaryContactFiles(directory, 5, "../private.asc"); fail("Arbitrary filenames accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun qrPngUsesItsOwnNameAndBoundWhileContactAndKeyLimitsStayAt64Kb() {
        val png = TemporaryContactFiles(root, 4, QrPng.FILE_NAME)
        val image = png.create(ByteArray(QrPng.MAX_BYTES) { 1 })
        assertEquals(QrPng.FILE_NAME, image.name)
        assertEquals(root.resolve("payload"), image.parentFile!!.parentFile)
        try { png.create(ByteArray(QrPng.MAX_BYTES + 1)); fail("Oversized PNG accepted") }
        catch (_: IllegalArgumentException) { }
        for (name in listOf(ContactExport.FILE_NAME, PublicKeyExport.FILE_NAME)) {
            val store = TemporaryContactFiles(root.resolve(name), 4, name)
            assertEquals(ContactExport.MAX_BYTES, store.create(ByteArray(ContactExport.MAX_BYTES)).length().toInt())
            try { store.create(ByteArray(ContactExport.MAX_BYTES + 1)); fail("$name limit changed") }
            catch (_: IllegalArgumentException) { }
        }
        for (name in listOf("vcard-native-qr.png.vcf", "../vcard-native-qr.png", "qr.png", "vcard-native-qr.PNG")) {
            try { TemporaryContactFiles(root, 4, name); fail("Arbitrary filename $name accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun qrPngStaleCleanupIsIsolatedFromContactFilesInTheSharedFolder() {
        val image = TemporaryContactFiles(root, 4, QrPng.FILE_NAME).create(byteArrayOf(9))
        val contact = TemporaryContactFiles(root, 4).create(byteArrayOf(8))
        // A same-boot grant may still be in use by the recipient.
        TemporaryContactFiles(root, 4, QrPng.FILE_NAME).cleanup()
        assertTrue(image.exists())
        // Each store removes only directories holding exactly its own file name.
        TemporaryContactFiles(root, 5).cleanup()
        assertTrue(image.exists())
        assertFalse(contact.exists())
        TemporaryContactFiles(root, 5, QrPng.FILE_NAME).cleanup()
        assertFalse(image.exists())
        assertFalse(root.resolve("leases").resolve(image.parentFile!!.name).exists())
        val mixed = root.resolve("payload").resolve(UUID.randomUUID().toString()).also { it.mkdirs() }
        mixed.resolve(QrPng.FILE_NAME).writeText("png")
        mixed.resolve("private.txt").writeText("evidence")
        mixed.setLastModified(1)
        TemporaryContactFiles(root, 6, QrPng.FILE_NAME).cleanup()
        assertTrue(mixed.resolve("private.txt").exists())
        val discarded = TemporaryContactFiles(root, 6, QrPng.FILE_NAME).create(byteArrayOf(1))
        TemporaryContactFiles(root, 6, QrPng.FILE_NAME).discard(discarded)
        assertFalse(discarded.exists())
    }
}
