package com.mackseoul.vcardnative.data.profile

/**
 * Only the coordinator uses these relative paths. Each method is an independent fault-injection
 * boundary. writeNew/moveNew must never replace an existing immutable object. read must enforce
 * the bound while reading, not only trust an initial file length. syncDirectory must really fsync.
 * moveNew is a same-filesystem atomic rename of a file or a whole directory; it must never replace an
 * existing destination and must never fall back to copy+delete.
 */
interface StoreFiles {
    fun list(path: String): Set<String>
    fun exists(path: String): Boolean
    fun read(path: String, maximumBytes: Int): ByteArray
    fun createDirectory(path: String)
    fun writeNew(path: String, bytes: ByteArray)
    fun overwrite(path: String, bytes: ByteArray)
    fun syncFile(path: String)
    fun moveNew(source: String, destination: String)
    fun syncDirectory(path: String)
    fun delete(path: String)
    fun isDirectory(path: String): Boolean
    fun usableSpace(): Long
    fun allocationUnitBytes(): Long = 4096

    /** Shared across repository instances using the same store. Android is single-process only. */
    val coordinationLock: Any

    /** Identity of the directory whose in-memory reference-uncertainty is tracked. */
    val uncertaintyKey: Any get() = coordinationLock
}

private data class ScopeKey(val parent: Any, val prefix: String)

/** A view of one generated subdirectory sharing the parent's lock; it never escapes [prefix]. */
internal class ScopedStoreFiles(private val parent: StoreFiles, private val prefix: String) : StoreFiles {
    init { require(prefix.isNotEmpty()) }
    private fun p(path: String) = if (path.isEmpty()) prefix else "$prefix\\$path"
    override fun list(path: String) = parent.list(p(path))
    override fun exists(path: String) = parent.exists(p(path))
    override fun read(path: String, maximumBytes: Int) = parent.read(p(path), maximumBytes)
    override fun createDirectory(path: String) = parent.createDirectory(p(path))
    override fun writeNew(path: String, bytes: ByteArray) = parent.writeNew(p(path), bytes)
    override fun overwrite(path: String, bytes: ByteArray) = parent.overwrite(p(path), bytes)
    override fun syncFile(path: String) = parent.syncFile(p(path))
    override fun moveNew(source: String, destination: String) = parent.moveNew(p(source), p(destination))
    override fun syncDirectory(path: String) = parent.syncDirectory(p(path))
    override fun delete(path: String) = parent.delete(p(path))
    override fun isDirectory(path: String) = parent.isDirectory(p(path))
    override fun usableSpace() = parent.usableSpace()
    override fun allocationUnitBytes() = parent.allocationUnitBytes()
    override val coordinationLock: Any get() = parent.coordinationLock
    override val uncertaintyKey: Any get() = ScopeKey(parent.uncertaintyKey, prefix)
}
