package com.mackseoul.vcardnative.data.profile

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

internal class JavaTestStoreFiles(val root: File) : StoreFiles {
    override val coordinationLock = StoreLocks.forCanonicalRoot(root.canonicalPath)
    var freeBytes = Long.MAX_VALUE
    var event = 0
    var failAt: Int? = null
    var failAfter = false
    var tearPointer = false
    /** Throw an Error (abrupt termination model) instead of an IOException at [failAt]. */
    var abrupt = false
    val events = mutableListOf<String>()

    init { check(root.mkdirs() || root.isDirectory) }
    private fun file(path: String): File = path.split('\\').filter { it.isNotEmpty() }
        .fold(root) { parent, child -> File(parent, child) }

    private fun <T> step(name: String, action: () -> T): T {
        event++
        events += name
        val fail = event == failAt
        if (fail && !failAfter) throw injected()
        val result = action()
        if (fail && failAfter) throw injected()
        return result
    }

    private fun injected(): Throwable = if (abrupt) InjectedTermination() else IOException("Injected failure")

    override fun list(path: String) = step("list:$path") { checkNotNull(file(path).list()).toSet() }
    override fun exists(path: String) = step("exists:$path") { file(path).exists() }
    override fun read(path: String, maximumBytes: Int) = step("read:$path") {
        FileInputStream(file(path)).use {
            val bytes = it.readNBytes(maximumBytes + 1)
            require(bytes.size <= maximumBytes)
            bytes
        }
    }
    override fun createDirectory(path: String) = step("mkdir:$path") { check(file(path).mkdir()); Unit }
    override fun writeNew(path: String, bytes: ByteArray) = step("writeNew:$path") {
        Files.newOutputStream(file(path).toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { it.write(bytes) }
    }
    override fun overwrite(path: String, bytes: ByteArray) = step("overwrite:$path") {
        FileOutputStream(file(path), false).use {
            if (tearPointer) {
                it.write(bytes.copyOf(bytes.size / 2))
                throw IOException("Injected torn pointer")
            }
            it.write(bytes)
        }
    }
    override fun syncFile(path: String) = step("fsync:$path") {
        FileOutputStream(file(path), true).use { it.fd.sync() }
    }
    override fun moveNew(source: String, destination: String) = step("move:$source") {
        check(file(source).exists() && !file(destination).exists())
        Files.move(file(source).toPath(), file(destination).toPath(), StandardCopyOption.ATOMIC_MOVE)
        Unit
    }
    override fun isDirectory(path: String) = step("isDirectory:$path") { file(path).isDirectory }
    override fun syncDirectory(path: String) = step("dirsync:$path") {
        // JVM Windows cannot open directory handles through FileChannel. File sync is real; this
        // boundary models directory sync only. Android's implementation uses Os.open/fsync/close.
        Unit
    }
    override fun delete(path: String) = step("delete:$path") { Files.delete(file(path).toPath()) }
    override fun usableSpace() = step("space") { freeBytes }
    override fun allocationUnitBytes() = step("allocationUnit") { 4096L }

    fun resetFaults() { event = 0; events.clear(); failAt = null; failAfter = false; tearPointer = false; abrupt = false }
    fun bytes(path: String): ByteArray = file(path).readBytes()
    fun put(path: String, bytes: ByteArray) { requireNotNull(file(path).parentFile).mkdirs(); file(path).writeBytes(bytes) }
    fun remove(path: String) { file(path).delete() }
    fun snapshot(): Map<String, ByteArray> = root.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(root).path to it.readBytes()
    }
    fun restore(snapshot: Map<String, ByteArray>) {
        if ("INIT" in snapshot) listOf("ptr", "gen", "blob", "stage").forEach { file(it).mkdirs() }
        snapshot.forEach { (path, bytes) -> put(path, bytes) }
    }
}

/** Models abrupt process termination: an Error, never an Exception the code may handle. */
internal class InjectedTermination : Error("Injected abrupt termination")
