package com.mackseoul.vcardnative.platform.storage

import android.system.Os
import android.system.OsConstants
import com.mackseoul.vcardnative.data.profile.StoreFiles
import com.mackseoul.vcardnative.data.profile.StoreException
import com.mackseoul.vcardnative.data.profile.StoreLocks
import com.mackseoul.vcardnative.domain.profile.ProfileValidator
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** The caller supplies a dedicated directory under Context.noBackupFilesDir, never filesDir. */
class AndroidStoreFiles(root: File) : StoreFiles {
    private val root = try {
        // Resolve only the trusted Context-owned parent (which can contain Android's legitimate
        // /data/user/0 -> /data/data alias). The dedicated store child is not a trusted alias.
        val absolute = root.absoluteFile
        val parent = requireNotNull(absolute.parentFile).canonicalFile
        require(absolute.name.isNotEmpty() && absolute.name != "." && absolute.name != "..")
        val child = File(parent, absolute.name)
        require(!Files.isSymbolicLink(child.toPath()) && child.canonicalFile == child)
        child
    } catch (_: Exception) {
        throw StoreException("Internal profile storage is unavailable.")
    }
    override val coordinationLock: Any = StoreLocks.forCanonicalRoot(this.root.path)

    init {
        try {
            if (!this.root.isDirectory && !this.root.mkdirs()) {
                throw StoreException("Internal profile storage is unavailable.")
            }
            if (!this.root.isDirectory || Files.isSymbolicLink(this.root.toPath())) {
                throw StoreException("Internal profile storage is unavailable.")
            }
        } catch (e: StoreException) {
            throw e
        } catch (_: Exception) {
            throw StoreException("Internal profile storage is unavailable.")
        }
        ProfileValidator.hostValidator = AndroidHostValidator
    }

    private fun file(path: String): File {
        if (path.isEmpty()) return root
        val parts = path.split('\\')
        require(parts.all { it.isNotEmpty() && it != "." && it != ".." &&
            it.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-." } })
        var result = root
        for (part in parts) {
            result = File(result, part)
            require(!Files.isSymbolicLink(result.toPath()))
        }
        require(result.canonicalPath.startsWith(root.path + File.separator))
        return result
    }

    override fun list(path: String): Set<String> = requireNotNull(file(path).list()) {
        "Cannot enumerate internal storage."
    }.toSet()

    override fun exists(path: String): Boolean = Files.exists(file(path).toPath(), LinkOption.NOFOLLOW_LINKS)

    override fun read(path: String, maximumBytes: Int): ByteArray {
        require(maximumBytes >= 0)
        val target = file(path)
        require(target.isFile && target.length() <= maximumBytes)
        return FileInputStream(target).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= maximumBytes)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    override fun createDirectory(path: String) {
        require(file(path).mkdir()) { "Cannot create storage directory." }
    }

    override fun writeNew(path: String, bytes: ByteArray) {
        Files.newOutputStream(file(path).toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
            it.write(bytes)
        }
    }

    override fun overwrite(path: String, bytes: ByteArray) {
        FileOutputStream(file(path), false).use { it.write(bytes) }
    }

    override fun syncFile(path: String) {
        FileOutputStream(file(path), true).use { it.fd.sync() }
    }

    override fun moveNew(source: String, destination: String) {
        // Callers hold the single-process coordination lock, so no other writer can create the
        // destination between this check and rename(2). ATOMIC_MOVE never degrades to copy+delete;
        // a cross-filesystem move fails with AtomicMoveNotSupportedException instead.
        val from = file(source).toPath()
        val to = file(destination).toPath()
        require(Files.exists(from, LinkOption.NOFOLLOW_LINKS) && !Files.exists(to, LinkOption.NOFOLLOW_LINKS))
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun isDirectory(path: String): Boolean = Files.isDirectory(file(path).toPath(), LinkOption.NOFOLLOW_LINKS)

    override fun syncDirectory(path: String) {
        // O_DIRECTORY is absent from the public Android OsConstants stub. Bionic's ARM/ARM64
        // fcntl ABI uses 040000; x86 and generic Linux ABIs use 0200000. Unknown ABIs fail closed.
        val directoryFlag = when (val machine = Os.uname().machine) {
            "aarch64" -> 0x4000
            "x86_64", "i686", "i386", "riscv64" -> 0x10000
            else -> if (machine.startsWith("arm")) 0x4000 else error("Unsupported filesystem ABI.")
        }
        val fd = Os.open(file(path).path, OsConstants.O_RDONLY or directoryFlag, 0)
        try {
            Os.fsync(fd)
        } finally {
            Os.close(fd)
        }
    }

    override fun delete(path: String) {
        Files.delete(file(path).toPath())
    }

    override fun usableSpace(): Long = root.usableSpace
    override fun allocationUnitBytes(): Long = Os.statvfs(root.path).f_frsize

}
