package com.eye3y3.finsync.app.saf

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import okio.FileHandle
import okio.Sink
import okio.Source
import okio.sink
import okio.source
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileNotFoundException
import java.nio.ByteBuffer

/** [DocumentTree] for a folder the user granted with ACTION_OPEN_DOCUMENT_TREE. */
class ContentResolverTree(private val context: Context, private val treeUri: Uri) : DocumentTree {
    private val cr = context.contentResolver
    private val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)

    private fun uri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)

    override val root: DocumentTree.Doc = docFor(DocumentsContract.getTreeDocumentId(treeUri))
        ?: throw FileNotFoundException("The chosen folder isn't available. Is the SD card in?")

    private fun docFor(id: String): DocumentTree.Doc? =
        cr.query(uri(id), cols, null, null, null)?.use { c -> if (c.moveToFirst()) row(c) else null }

    private fun row(c: android.database.Cursor) = DocumentTree.Doc(
        id = c.getString(0),
        name = c.getString(1) ?: "",
        isDirectory = c.getString(2) == Document.MIME_TYPE_DIR,
        size = if (c.isNull(3)) 0L else c.getLong(3),
        lastModified = if (c.isNull(4)) null else c.getLong(4),
    )

    override fun children(dirId: String): List<DocumentTree.Doc> {
        val u = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId)
        val out = ArrayList<DocumentTree.Doc>()
        cr.query(u, cols, null, null, null)?.use { c -> while (c.moveToNext()) out += row(c) }
        return out
    }

    override fun createFile(parentId: String, name: String): DocumentTree.Doc {
        // A MIME type matching the extension stops providers from adding an extension of their own.
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val made = DocumentsContract.createDocument(cr, uri(parentId), mime, name) ?: throw FileNotFoundException("couldn't create $name")
        return docFor(DocumentsContract.getDocumentId(made)) ?: throw FileNotFoundException("couldn't create $name")
    }

    override fun createDirectory(parentId: String, name: String): DocumentTree.Doc {
        val made = DocumentsContract.createDocument(cr, uri(parentId), Document.MIME_TYPE_DIR, name) ?: throw FileNotFoundException("couldn't create $name")
        return docFor(DocumentsContract.getDocumentId(made)) ?: throw FileNotFoundException("couldn't create $name")
    }

    override fun rename(id: String, name: String): DocumentTree.Doc? = try {
        val renamed = DocumentsContract.renameDocument(cr, uri(id), name)
        docFor(if (renamed != null) DocumentsContract.getDocumentId(renamed) else id)
    } catch (e: Exception) {
        null
    }

    override fun move(id: String, fromParentId: String, toParentId: String): DocumentTree.Doc? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        return try {
            val moved = DocumentsContract.moveDocument(cr, uri(id), uri(fromParentId), uri(toParentId)) ?: return null
            docFor(DocumentsContract.getDocumentId(moved))
        } catch (e: UnsupportedOperationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    override fun delete(id: String): Boolean = try { DocumentsContract.deleteDocument(cr, uri(id)) } catch (e: Exception) { false }

    override fun openHandle(id: String, readWrite: Boolean): FileHandle {
        val pfd = cr.openFileDescriptor(uri(id), if (readWrite) "rw" else "r") ?: throw FileNotFoundException("couldn't open $id")
        return ChannelHandle(pfd, readWrite)
    }

    override fun source(id: String): Source = (cr.openInputStream(uri(id)) ?: throw FileNotFoundException("couldn't read $id")).source()

    override fun sink(id: String, append: Boolean): Sink =
        (cr.openOutputStream(uri(id), if (append) "wa" else "wt") ?: throw FileNotFoundException("couldn't write $id")).sink()

    override fun freeBytes(): Long? = volumeDirectory()?.let { dir -> try { StatFs(dir.path).availableBytes } catch (e: Exception) { null } }

    /** The mounted folder of the volume the tree is on (its id starts "<volume uuid>:" or "primary:"). */
    private fun volumeDirectory(): File? {
        val volume = DocumentsContract.getTreeDocumentId(treeUri).substringBefore(':')
        val sm = context.getSystemService(StorageManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            sm.storageVolumes.firstOrNull { if (volume == "primary") it.isPrimary else it.uuid.equals(volume, ignoreCase = true) }?.directory?.let { return it }
        }
        return if (volume == "primary") android.os.Environment.getExternalStorageDirectory() else File("/storage/$volume").takeIf { it.exists() }
    }

    /** Positional reads and writes on a SAF file descriptor. */
    private class ChannelHandle(private val pfd: ParcelFileDescriptor, readWrite: Boolean) : FileHandle(readWrite) {
        private val input = FileInputStream(pfd.fileDescriptor).channel
        private val output = if (readWrite) FileOutputStream(pfd.fileDescriptor).channel else null

        override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int {
            val n = input.read(ByteBuffer.wrap(array, arrayOffset, byteCount), fileOffset)
            return if (n <= 0 && byteCount > 0) -1 else n
        }

        override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) {
            val buf = ByteBuffer.wrap(array, arrayOffset, byteCount)
            var pos = fileOffset
            while (buf.hasRemaining()) pos += output!!.write(buf, pos)
        }

        override fun protectedFlush() { output?.force(false) }
        override fun protectedResize(size: Long) {
            val out = output!!
            if (size < out.size()) out.truncate(size) else if (size > out.size()) out.write(ByteBuffer.wrap(ByteArray(1)), size - 1)
        }
        override fun protectedSize(): Long = input.size()
        override fun protectedClose() { pfd.close() }
    }
}
