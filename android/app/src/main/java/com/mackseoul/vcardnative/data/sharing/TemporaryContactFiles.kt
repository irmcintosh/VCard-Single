package com.mackseoul.vcardnative.data.sharing

import com.mackseoul.vcardnative.domain.sharing.ContactExport
import com.mackseoul.vcardnative.domain.sharing.PublicKeyExport
import com.mackseoul.vcardnative.domain.sharing.QrPng
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

class TemporaryContactFiles(
    private val root: File,
    private val boot: Long,
    private val fileName: String = ContactExport.FILE_NAME,
) {
    init { require(boot >= 0 && fileName in setOf(ContactExport.FILE_NAME, PublicKeyExport.FILE_NAME, QrPng.FILE_NAME)) }

    private val maxBytes = if (fileName == QrPng.FILE_NAME) QrPng.MAX_BYTES else ContactExport.MAX_BYTES

    fun create(bytes: ByteArray): File = synchronized(lock) {
        require(bytes.size in 1..maxBytes)
        ensureDirectory(root)
        cleanup()
        val id = UUID.randomUUID().toString()
        val directory = File(root, "payload").resolve(id)
        val lease = File(root, "leases").resolve(id)
        try {
            ensureDirectory(File(root, "leases"))
            ensureDirectory(directory)
            // Written before publishing a URI: an interrupted handoff is retained, not retried
            // or deleted based on a chooser result that says nothing about recipient lifetime.
            writeNew(lease, "$boot\n".toByteArray(Charsets.US_ASCII))
            val file = File(directory, fileName)
            writeNew(file, bytes)
            file
        } catch (error: IOException) {
            discardKnown(directory, lease)
            throw error
        }
    }

    fun discard(file: File) = synchronized(lock) {
        val directory = requireNotNull(file.parentFile)
        require(directory.parentFile == File(root, "payload") && directory.name.matches(ID))
        discardKnown(directory, File(root, "leases").resolve(directory.name))
    }

    fun cleanup() = synchronized(lock) {
        val payload = File(root, "payload")
        if (!payload.exists()) return@synchronized
        ensureDirectory(payload)
        val entries = payload.listFiles() ?: throw IOException("Cannot enumerate temporary contacts.")
        for (directory in entries) {
            if (!directory.name.matches(ID) || !directory.isDirectory || Files.isSymbolicLink(directory.toPath())) continue
            val lease = File(root, "leases").resolve(directory.name)
            if (lease.exists()) {
                if (!lease.isFile || Files.isSymbolicLink(lease.toPath()) || lease.length() > 32) continue
                val record = lease.readText(Charsets.US_ASCII)
                val recordedBoot = record.takeIf { it.matches(Regex("[0-9]+\\n")) }?.trim()?.toLongOrNull()
                // Non-persistable URI grants cannot survive a reboot. Same-boot and uncertain
                // leases have no safe expiry because Android exposes no complete grant inventory.
                if (recordedBoot != null && recordedBoot < boot) discardKnown(directory, lease)
            } else if (System.currentTimeMillis() - directory.lastModified() >= UNUSED_RETENTION_MILLIS) {
                discardKnown(directory, lease)
            }
        }
    }

    private fun discardKnown(directory: File, lease: File) {
        val entries = directory.listFiles() ?: if (!directory.exists()) emptyArray() else
            throw IOException("Cannot enumerate temporary contact.")
        if (entries.any { it.name != fileName || !it.isFile || Files.isSymbolicLink(it.toPath()) }) return
        entries.forEach { if (!it.delete()) throw IOException("Cannot remove temporary contact.") }
        if (directory.exists() && !directory.delete()) throw IOException("Cannot remove temporary contact directory.")
        if (lease.exists() && !lease.delete()) throw IOException("Cannot remove temporary contact lease.")
    }

    private fun writeNew(file: File, bytes: ByteArray) {
        if (!file.createNewFile()) throw IOException("Temporary contact already exists.")
        FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
    }

    private fun ensureDirectory(directory: File) {
        if ((!directory.isDirectory && !directory.mkdirs()) || Files.isSymbolicLink(directory.toPath())) {
            throw IOException("Temporary contact storage is unavailable.")
        }
    }

    companion object {
        private val lock = Any()
        private val ID = Regex("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}")
        const val UNUSED_RETENTION_MILLIS = 24 * 60 * 60 * 1000L
    }
}
