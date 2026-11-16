package com.mackseoul.vcardnative.data.backup

import com.mackseoul.vcardnative.domain.backup.BackupException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

data class StagedBackup(val id: String, val byteLength: Long, val sha256: String)

class BackupStaging(
    private val root: File,
    private val checkCapacity: (File, Int) -> Unit = { directory, count ->
        if (directory.usableSpace < count) throw BackupException("Not enough internal storage to stage the backup.")
    },
) {
    private val lock = Any()

    fun stage(input: InputStream): StagedBackup = synchronized(lock) {
        ensureRoot()
        val id = UUID.randomUUID().toString()
        val file = file(id)
        try {
            check(file.createNewFile())
            var size = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            FileOutputStream(file).use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) throw BackupException("The backup document could not be read completely.")
                    size += count
                    if (size > MAX_BYTES) throw BackupException("The backup exceeds the 24 MiB archive limit.")
                    checkCapacity(root, count)
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                }
                output.fd.sync()
            }
            if (size == 0L) throw BackupException("The selected backup is empty.")
            StagedBackup(id, size, digest.digest().hex())
        } catch (error: Exception) {
            if (file.exists() && !file.delete())
                throw BackupException("Backup staging failed and its incomplete temporary file could not be removed.")
            if (error is BackupException) throw error
            throw BackupException("The backup could not be staged. Saved local data is unchanged.")
        }
    }

    fun read(stage: StagedBackup): ByteArray = synchronized(lock) {
        try {
            val file = file(stage.id)
            require(stage.byteLength in 1..MAX_BYTES && file.length() == stage.byteLength && file.isFile)
            val bytes = file.inputStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(count > 0 && output.size().toLong() + count <= MAX_BYTES)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            require(bytes.size.toLong() == stage.byteLength &&
                MessageDigest.getInstance("SHA-256").digest(bytes).hex() == stage.sha256)
            bytes
        } catch (_: Exception) {
            throw BackupException("The staged backup changed or is unavailable. Choose the backup again; nothing was restored.")
        }
    }

    fun discard(stage: StagedBackup) = synchronized(lock) {
        try {
            val file = file(stage.id)
            if (file.exists() && !file.delete()) throw java.io.IOException()
        } catch (_: Exception) {
            throw BackupException("The temporary backup could not be removed. Saved local data is unchanged.")
        }
    }

    fun cleanupAbandoned() = synchronized(lock) {
        ensureRoot()
        val files = root.listFiles() ?: throw BackupException("Temporary backup storage could not be inspected.")
        for (file in files) {
            if (file.name.matches(Regex("[0-9a-f-]{36}\\.archive")) &&
                file.isFile && !Files.isSymbolicLink(file.toPath())) {
                if (!file.delete()) throw BackupException("An abandoned temporary backup could not be removed.")
            }
        }
    }

    private fun ensureRoot() {
        if ((!root.isDirectory && !root.mkdirs()) || Files.isSymbolicLink(root.toPath()))
            throw BackupException("Internal backup staging is unavailable.")
    }
    private fun file(id: String): File {
        require(UUID.fromString(id).toString() == id)
        ensureRoot()
        return root.resolve("$id.archive").also { require(!Files.isSymbolicLink(it.toPath())) }
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        const val MAX_BYTES = 25_165_824L
    }
}
