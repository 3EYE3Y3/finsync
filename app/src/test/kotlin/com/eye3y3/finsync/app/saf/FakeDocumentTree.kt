package com.eye3y3.finsync.app.saf

import okio.Buffer
import okio.FileHandle
import okio.Sink
import okio.Source
import okio.Timeout

/**
 * An in-memory SAF tree that behaves like Android's ExternalStorageProvider on an exFAT card:
 * document ids are "card:<path>" (so they change on rename and move), names are case-insensitive,
 * creating a name that is taken gives "name (1).ext", renaming onto a taken name fails, deleting a
 * folder deletes everything in it, and moving can be switched off ([canMove]) like on older providers.
 */
class FakeDocumentTree(var canMove: Boolean = true, var free: Long? = 1L shl 40) : DocumentTree {
    private class Node(var name: String, val dir: Boolean, var parent: Node?) {
        val kids = ArrayList<Node>()
        var data = ByteArray(0)
        val path: String get() = parent?.let { p -> (if (p.parent == null) "" else p.path + "/") + name } ?: ""
    }

    private val rootNode = Node("", true, null)
    var moves = 0

    private fun id(n: Node) = "card:" + n.path
    private fun doc(n: Node) = DocumentTree.Doc(id(n), n.name, n.dir, n.data.size.toLong(), 0L)
    private fun node(id: String): Node {
        val path = id.removePrefix("card:")
        var n = rootNode
        if (path.isNotEmpty()) for (seg in path.split("/")) n = n.kids.firstOrNull { it.name.equals(seg, true) } ?: error("no document $id")
        return n
    }

    private fun unique(parent: Node, name: String): String {
        if (parent.kids.none { it.name.equals(name, true) }) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        var i = 1
        while (true) {
            val candidate = name.substring(0, dot) + " ($i)" + name.substring(dot)
            if (parent.kids.none { it.name.equals(candidate, true) }) return candidate
            i++
        }
    }

    override val root get() = doc(rootNode)
    override fun children(dirId: String) = node(dirId).kids.map(::doc)

    override fun createFile(parentId: String, name: String): DocumentTree.Doc {
        val p = node(parentId)
        return doc(Node(unique(p, name), false, p).also { p.kids += it })
    }

    override fun createDirectory(parentId: String, name: String): DocumentTree.Doc {
        val p = node(parentId)
        return doc(Node(unique(p, name), true, p).also { p.kids += it })
    }

    override fun rename(id: String, name: String): DocumentTree.Doc? {
        val n = node(id)
        if (n.parent!!.kids.any { it !== n && it.name.equals(name, true) }) return null
        n.name = name
        return doc(n)
    }

    override fun move(id: String, fromParentId: String, toParentId: String): DocumentTree.Doc? {
        if (!canMove) return null
        val n = node(id)
        val to = node(toParentId)
        if (to.kids.any { it.name.equals(n.name, true) }) return null
        n.parent!!.kids.remove(n)
        n.parent = to
        to.kids += n
        moves++
        return doc(n)
    }

    override fun delete(id: String): Boolean {
        val n = node(id)
        n.parent?.kids?.remove(n) ?: return false
        return true
    }

    override fun openHandle(id: String, readWrite: Boolean): FileHandle = Handle(node(id), readWrite)
    override fun source(id: String): Source = Buffer().write(node(id).data)
    override fun sink(id: String, append: Boolean): Sink {
        val n = node(id)
        val buf = Buffer()
        if (append) buf.write(n.data)
        return object : Sink {
            override fun write(source: Buffer, byteCount: Long) = buf.write(source, byteCount)
            override fun flush() {}
            override fun timeout() = Timeout.NONE
            override fun close() { n.data = buf.readByteArray() }
        }
    }

    override fun freeBytes() = free

    // Helpers for tests.
    fun put(path: String, bytes: ByteArray) {
        var n = rootNode
        val segs = path.split("/")
        for (seg in segs.dropLast(1)) n = n.kids.firstOrNull { it.name == seg } ?: Node(seg, true, n).also { n.kids += it }
        val f = n.kids.firstOrNull { it.name == segs.last() } ?: Node(segs.last(), false, n).also { n.kids += it }
        f.data = bytes
    }

    fun read(path: String): ByteArray? = try { node("card:$path").takeIf { !it.dir }?.data } catch (e: IllegalStateException) { null }
    fun exists(path: String) = try { node("card:$path"); true } catch (e: IllegalStateException) { false }
    fun files(): List<String> {
        val out = ArrayList<String>()
        fun walk(n: Node) { for (k in n.kids) if (k.dir) walk(k) else out += k.path }
        walk(rootNode)
        return out.sorted()
    }

    private class Handle(private val n: Node, rw: Boolean) : FileHandle(rw) {
        override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int {
            if (fileOffset >= n.data.size) return -1
            val k = minOf(byteCount.toLong(), n.data.size - fileOffset).toInt()
            n.data.copyInto(array, arrayOffset, fileOffset.toInt(), fileOffset.toInt() + k)
            return k
        }
        override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) {
            val end = (fileOffset + byteCount).toInt()
            if (end > n.data.size) n.data = n.data.copyOf(end)
            array.copyInto(n.data, fileOffset.toInt(), arrayOffset, arrayOffset + byteCount)
        }
        override fun protectedFlush() {}
        override fun protectedResize(size: Long) { n.data = n.data.copyOf(size.toInt()) }
        override fun protectedSize() = n.data.size.toLong()
        override fun protectedClose() {}
    }
}
