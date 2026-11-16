package com.mackseoul.vcardnative.data.profile

import java.io.IOException

/**
 * In-memory filesystem separating volatile from durable state. File content becomes durable only
 * after syncFile; a directory's entries (create, unlink) only after syncDirectory of that directory.
 * A rename is one journaled metadata transaction: syncing either parent commits both of its sides,
 * so a crash never shows a directory under both names or under neither (as on ext4/f2fs). [crash] discards everything volatile, like a kernel losing its page cache. This is
 * a software model of POSIX fsync semantics, not evidence of hardware power-loss behaviour.
 */
internal class DurableModelStoreFiles : StoreFiles {
    private sealed class Node
    private class FileNode(var volatile: ByteArray, var durable: ByteArray = ByteArray(0)) : Node()
    private class DirNode : Node() {
        val volatile = sortedMapOf<String, Node>()
        var durable = sortedMapOf<String, Node>()
    }

    private val root = DirNode()
    private class Rename(val from: DirNode, val fromName: String, val to: DirNode, val toName: String, val node: Node)
    private val renames = mutableListOf<Rename>()
    override var coordinationLock: Any = Any()
        private set
    var freeBytes = Long.MAX_VALUE
    var event = 0
    var failAt: Int? = null
    var failAfter = false
    var abrupt = false
    val events = mutableListOf<String>()

    private fun parts(path: String) = if (path.isEmpty()) emptyList() else path.split('\\').also { p ->
        require(p.all { it.isNotEmpty() && it != "." && it != ".." })
    }
    private fun node(path: String): Node? {
        var current: Node = root
        for (part in parts(path)) current = (current as? DirNode)?.volatile?.get(part) ?: return null
        return current
    }
    private fun dir(path: String) = node(path) as? DirNode ?: throw IOException("Not a directory: $path")
    private fun parentOf(path: String): Pair<DirNode, String> {
        val p = parts(path)
        require(p.isNotEmpty())
        return dir(p.dropLast(1).joinToString("\\")) to p.last()
    }

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

    override fun list(path: String) = step("list:$path") { dir(path).volatile.keys.toSet() }
    override fun exists(path: String) = step("exists:$path") { node(path) != null }
    override fun isDirectory(path: String) = step("isDirectory:$path") { node(path) is DirNode }
    override fun read(path: String, maximumBytes: Int) = step("read:$path") {
        val file = node(path) as? FileNode ?: throw IOException("Not a file: $path")
        require(file.volatile.size <= maximumBytes)
        file.volatile.copyOf()
    }
    override fun createDirectory(path: String) = step("mkdir:$path") {
        val (parent, name) = parentOf(path)
        if (name in parent.volatile) throw IOException("Exists")
        parent.volatile[name] = DirNode()
        Unit
    }
    override fun writeNew(path: String, bytes: ByteArray) = step("writeNew:$path") {
        val (parent, name) = parentOf(path)
        if (name in parent.volatile) throw IOException("Exists")
        parent.volatile[name] = FileNode(bytes.copyOf())
        Unit
    }
    override fun overwrite(path: String, bytes: ByteArray) = step("overwrite:$path") {
        // O_CREAT|O_TRUNC like FileOutputStream: a created entry is durable only after its directory sync.
        when (val existing = node(path)) {
            is FileNode -> existing.volatile = bytes.copyOf()
            null -> parentOf(path).let { (parent, name) -> parent.volatile[name] = FileNode(bytes.copyOf()) }
            else -> throw IOException("Is a directory")
        }
    }
    override fun syncFile(path: String) = step("fsync:$path") {
        val file = node(path) as? FileNode ?: throw IOException("Missing")
        file.durable = file.volatile.copyOf()
    }
    override fun moveNew(source: String, destination: String) = step("move:$source->$destination") {
        val (from, fromName) = parentOf(source)
        val (to, toName) = parentOf(destination)
        val moving = from.volatile[fromName] ?: throw IOException("Missing")
        if (toName in to.volatile) throw IOException("Destination exists")
        from.volatile.remove(fromName)
        to.volatile[toName] = moving
        renames += Rename(from, fromName, to, toName, moving)
        Unit
    }
    override fun syncDirectory(path: String) = step("dirsync:$path") {
        val d = dir(path)
        d.durable = sortedMapOf<String, Node>().also { it.putAll(d.volatile) }
        val committed = renames.filter { it.from === d || it.to === d }
        for (rename in committed) {
            if (rename.from.durable[rename.fromName] === rename.node) rename.from.durable.remove(rename.fromName)
            rename.to.durable[rename.toName] = rename.node
        }
        renames.removeAll(committed)
        Unit
    }
    override fun delete(path: String) = step("delete:$path") {
        val (parent, name) = parentOf(path)
        val target = parent.volatile[name] ?: throw IOException("Missing")
        if (target is DirNode && target.volatile.isNotEmpty()) throw IOException("Not empty")
        parent.volatile.remove(name)
        Unit
    }
    override fun usableSpace() = step("space") { freeBytes }
    override fun allocationUnitBytes() = step("allocationUnit") { 4096L }

    /** Power loss followed by a new process: only durable state survives; locks are new. */
    fun crash() {
        fun revert(node: Node, seen: MutableSet<Node>) {
            if (!seen.add(node)) return
            when (node) {
                is FileNode -> node.volatile = node.durable.copyOf()
                is DirNode -> {
                    node.volatile.clear()
                    node.volatile.putAll(node.durable)
                    node.durable.values.forEach { revert(it, seen) }
                }
            }
        }
        revert(root, mutableSetOf())
        renames.clear()
        coordinationLock = Any()
        resetFaults()
    }

    /** Clean process restart without data loss: in-memory uncertainty disappears. */
    fun restartProcess() { coordinationLock = Any(); resetFaults() }

    fun resetFaults() { event = 0; events.clear(); failAt = null; failAfter = false; abrupt = false }

    /** Fault-free helpers for test setup and assertions (durable immediately). */
    fun put(path: String, bytes: ByteArray) {
        val p = parts(path)
        var current = root
        for (part in p.dropLast(1)) {
            current = (current.volatile.getOrPut(part) { DirNode() } as DirNode).also { current.durable[part] = it }
        }
        val file = FileNode(bytes.copyOf(), bytes.copyOf())
        current.volatile[p.last()] = file
        current.durable[p.last()] = file
    }
    fun mkdirs(path: String) {
        var current = root
        for (part in parts(path)) {
            current = (current.volatile.getOrPut(part) { DirNode() } as DirNode).also { current.durable[part] = it }
        }
    }
    fun bytes(path: String): ByteArray = (node(path) as FileNode).volatile.copyOf()
    fun has(path: String) = node(path) != null
    fun snapshot(path: String = ""): Map<String, List<Byte>> {
        val result = sortedMapOf<String, List<Byte>>()
        fun walk(prefix: String, node: Node) {
            when (node) {
                is FileNode -> result[prefix] = node.volatile.toList()
                is DirNode -> {
                    if (prefix.isNotEmpty()) result["$prefix\\"] = emptyList()
                    node.volatile.forEach { (name, child) -> walk(if (prefix.isEmpty()) name else "$prefix\\$name", child) }
                }
            }
        }
        node(path)?.let { walk(path, it) }
        return result
    }
}
