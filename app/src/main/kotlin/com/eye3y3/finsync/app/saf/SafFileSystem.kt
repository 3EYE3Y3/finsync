package com.eye3y3.finsync.app.saf

import okio.FileHandle
import okio.FileMetadata
import okio.FileNotFoundException
import okio.FileSystem
import okio.ForwardingSink
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.Source
import okio.buffer

/**
 * An Okio [FileSystem] over a granted SAF folder, so the sync engine runs unchanged on an SD card.
 * "/" is the granted folder. Folder listings are cached (SAF queries are slow) and dropped whenever
 * something in that folder changes. Like a card's exFAT, names match without regard to case when there
 * is no exact match. Creating, moving and deleting are done one at a time (two downloads creating the
 * same new folder at once would otherwise get "Artist" and "Artist (1)"); reading and writing data aren't.
 */
class SafFileSystem(private val tree: DocumentTree) : FileSystem() {
    private val lock = Any()
    private val listings = HashMap<String, List<DocumentTree.Doc>>()

    // --- lookups ---------------------------------------------------------------------------------

    private fun listing(dirId: String): List<DocumentTree.Doc> = synchronized(lock) { listings.getOrPut(dirId) { tree.children(dirId) } }

    private fun child(dirId: String, name: String): DocumentTree.Doc? {
        val kids = listing(dirId)
        return kids.firstOrNull { it.name == name } ?: kids.filter { it.name.equals(name, ignoreCase = true) }.singleOrNull()
    }

    private fun lookup(path: Path): DocumentTree.Doc? {
        var doc = tree.root
        for (seg in norm(path).segments) {
            if (!doc.isDirectory) return null
            doc = child(doc.id, seg) ?: return null
        }
        return doc
    }

    private fun need(path: Path) = lookup(path) ?: throw FileNotFoundException("no such file: $path")

    private fun parentOf(path: Path): DocumentTree.Doc {
        val parent = norm(path).parent ?: throw IOException("no parent: $path")
        return lookup(parent)?.takeIf { it.isDirectory } ?: throw FileNotFoundException("no such folder: $parent")
    }

    private fun changed(dirId: String) = synchronized(lock) { listings.remove(dirId) }

    private fun norm(path: Path): Path = ("/" + path.normalized().segments.joinToString("/")).toPath()

    // --- FileSystem ------------------------------------------------------------------------------

    override fun canonicalize(path: Path): Path = need(path).let { norm(path) }

    override fun metadataOrNull(path: Path): FileMetadata? = lookup(path)?.let {
        FileMetadata(isRegularFile = !it.isDirectory, isDirectory = it.isDirectory, size = if (it.isDirectory) null else it.size, lastModifiedAtMillis = it.lastModified)
    }

    override fun list(dir: Path): List<Path> = listOrNull(dir) ?: throw FileNotFoundException("no such folder: $dir")

    override fun listOrNull(dir: Path): List<Path>? {
        val d = lookup(dir)?.takeIf { it.isDirectory } ?: return null
        return listing(d.id).map { norm(dir) / it.name }.sorted()
    }

    override fun openReadOnly(file: Path): FileHandle = tree.openHandle(need(file).id, readWrite = false)

    override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle {
        val existing = lookup(file)
        if (existing != null && mustCreate) throw IOException("already exists: $file")
        if (existing == null && mustExist) throw FileNotFoundException("no such file: $file")
        val doc = existing ?: create(file)
        val parent = parentOf(file).id
        return ClosingHandle(tree.openHandle(doc.id, readWrite = true)) { changed(parent) }
    }

    override fun source(file: Path): Source = tree.source(need(file).id)

    override fun sink(file: Path, mustCreate: Boolean): Sink {
        val existing = lookup(file)
        if (existing != null && mustCreate) throw IOException("already exists: $file")
        if (existing?.isDirectory == true) throw IOException("is a folder: $file")
        val doc = existing ?: create(file)
        val parent = parentOf(file).id
        return closing(tree.sink(doc.id, append = false)) { changed(parent) }
    }

    override fun appendingSink(file: Path, mustExist: Boolean): Sink {
        val existing = lookup(file)
        if (existing == null && mustExist) throw FileNotFoundException("no such file: $file")
        val doc = existing ?: create(file)
        val parent = parentOf(file).id
        return closing(tree.sink(doc.id, append = true)) { changed(parent) }
    }

    override fun createDirectory(dir: Path, mustCreate: Boolean) {
        synchronized(lock) {
            val existing = lookup(dir)
            if (existing != null) {
                if (mustCreate || !existing.isDirectory) throw IOException("already exists: $dir")
                return
            }
            val parent = parentOf(dir)
            val name = norm(dir).name
            val made = tree.createDirectory(parent.id, name)
            changed(parent.id)
            if (made.name != name) {
                tree.delete(made.id)
                throw IOException("the card wouldn't create $dir (it offered ${made.name})")
            }
        }
    }

    override fun atomicMove(source: Path, target: Path) {
        synchronized(lock) {
            val from = norm(source)
            val to = norm(target)
            if (from == to) return
            val doc = need(from)
            val fromParent = parentOf(from)
            val toParent = parentOf(to)
            val name = to.name
            val sameFolder = fromParent.id == toParent.id
            val existing = lookup(to)
            if (existing != null && existing.id != doc.id) {
                if (existing.isDirectory && listing(existing.id).isNotEmpty()) throw IOException("folder not empty: $to")
                tree.delete(existing.id)
                changed(toParent.id)
            }
            var moved = doc
            if (!sameFolder) {
                val m = tree.move(doc.id, fromParent.id, toParent.id)
                if (m == null) copyThenDelete(doc, toParent, name)
                changed(fromParent.id); changed(toParent.id)
                moved = m ?: return
            }
            if (moved.name != name) {
                // A case-only rename can clash with itself on exFAT: go through a temporary name.
                if (moved.name.equals(name, ignoreCase = true)) moved = tree.rename(moved.id, "$name.finsync-rename") ?: throw IOException("rename failed: $from")
                moved = tree.rename(moved.id, name) ?: throw IOException("rename failed: $from -> $to")
                changed(toParent.id)
                if (moved.name != name) throw IOException("the card named it ${moved.name} instead of $name")
            }
            synchronized(lock) { if (doc.isDirectory) listings.clear() }
        }
    }

    override fun delete(path: Path, mustExist: Boolean) {
        synchronized(lock) {
            val doc = lookup(path) ?: if (mustExist) throw FileNotFoundException("no such file: $path") else return
            if (doc.isDirectory && listing(doc.id).isNotEmpty()) throw IOException("folder not empty: $path")
            val parent = parentOf(path)
            if (!tree.delete(doc.id)) throw IOException("couldn't delete $path")
            changed(parent.id)
            changed(doc.id)
        }
    }

    override fun createSymlink(source: Path, target: Path) = throw IOException("links aren't supported on SD cards")

    // --- helpers ---------------------------------------------------------------------------------

    private fun create(file: Path): DocumentTree.Doc = synchronized(lock) {
        lookup(file)?.let { return it } // made by another download meanwhile
        val parent = parentOf(file)
        val name = norm(file).name
        val made = tree.createFile(parent.id, name)
        changed(parent.id)
        if (made.name != name) {
            tree.delete(made.id)
            changed(parent.id)
            throw IOException("the card wouldn't create $file (it offered ${made.name})")
        }
        return made
    }

    /** For providers that can't move between folders: copy, then delete the original. */
    private fun copyThenDelete(doc: DocumentTree.Doc, toParent: DocumentTree.Doc, name: String) {
        if (doc.isDirectory) throw IOException("can't move a folder on this card")
        val made = tree.createFile(toParent.id, name)
        if (made.name != name) { tree.delete(made.id); throw IOException("the card wouldn't create $name") }
        tree.source(doc.id).use { src -> tree.sink(made.id, append = false).buffer().use { it.writeAll(src) } }
        if (!tree.delete(doc.id)) throw IOException("copied, but couldn't remove the original ${doc.name}")
    }

    private fun closing(sink: Sink, onClose: () -> Unit): Sink = object : ForwardingSink(sink) {
        override fun close() = try { super.close() } finally { onClose() }
    }

    /** Freshens the folder listing (sizes) when a read-write handle is closed. */
    private class ClosingHandle(private val inner: FileHandle, private val onClose: () -> Unit) : FileHandle(inner.readWrite) {
        override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) = inner.read(fileOffset, array, arrayOffset, byteCount)
        override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) = inner.write(fileOffset, array, arrayOffset, byteCount)
        override fun protectedFlush() = inner.flush()
        override fun protectedResize(size: Long) = inner.resize(size)
        override fun protectedSize() = inner.size()
        override fun protectedClose() = try { inner.close() } finally { onClose() }
    }
}
