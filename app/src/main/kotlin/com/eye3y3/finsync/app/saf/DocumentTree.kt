package com.eye3y3.finsync.app.saf

import okio.FileHandle
import okio.Sink
import okio.Source

/**
 * The few Storage Access Framework operations FinSync needs, on a folder the user granted (a "tree").
 * Kept free of Android types so [SafFileSystem] can be tested on the JVM with a fake tree.
 */
interface DocumentTree {
    data class Doc(val id: String, val name: String, val isDirectory: Boolean, val size: Long, val lastModified: Long?)

    val root: Doc
    fun children(dirId: String): List<Doc>
    /** A new empty file. Providers may change [name] if it is taken, so callers check the result's name. */
    fun createFile(parentId: String, name: String): Doc
    fun createDirectory(parentId: String, name: String): Doc
    /** Renames; the id may change. Null if the provider refused. */
    fun rename(id: String, name: String): Doc?
    /** Moves into another folder, keeping the name. Null when the provider can't move. */
    fun move(id: String, fromParentId: String, toParentId: String): Doc?
    /** Deletes a file, or a folder with everything in it. */
    fun delete(id: String): Boolean
    fun openHandle(id: String, readWrite: Boolean): FileHandle
    fun source(id: String): Source
    /** Writing from the start (the file is cut to nothing first) or appending. */
    fun sink(id: String, append: Boolean): Sink
    /** Free space on the volume, or null if unknown. */
    fun freeBytes(): Long?
}
