package com.eye3y3.finsync.reuse

import com.eye3y3.finsync.jellyfin.JellyfinClient
import okio.FileHandle

/** Random access to a file's bytes. [read] returns fewer than [length] bytes only at the end of the file. */
interface ByteReader {
    val size: Long
    suspend fun read(offset: Long, length: Int): ByteArray
}

/** A file on the card (or any Okio file system). */
class FileByteReader(private val handle: FileHandle) : ByteReader {
    override val size: Long = handle.size()

    override suspend fun read(offset: Long, length: Int): ByteArray {
        val n = minOf(length.toLong(), size - offset).coerceAtLeast(0).toInt()
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = handle.read(offset + got, out, got, n - got)
            if (r <= 0) break
            got += r
        }
        return if (got == n) out else out.copyOf(got)
    }
}

/**
 * A track on the server, read with Range requests. The first [HEAD] and last [TAIL] bytes are fetched
 * once and kept, because tag parsing reads many small pieces there.
 */
class ServerByteReader(private val client: JellyfinClient, private val itemId: String, override val size: Long) : ByteReader {
    private var head: ByteArray? = null
    private var tail: ByteArray? = null
    private val tailStart = maxOf(0L, size - TAIL)

    override suspend fun read(offset: Long, length: Int): ByteArray {
        val end = minOf(size, offset + length)
        if (offset >= end) return ByteArray(0)
        if (end <= HEAD) {
            val h = head ?: client.readRange(itemId, 0, minOf(HEAD.toLong(), size).toInt()).also { head = it }
            return h.copyOfRange(offset.toInt(), minOf(end, h.size.toLong()).toInt())
        }
        if (offset >= tailStart) {
            val t = tail ?: client.readRange(itemId, tailStart, (size - tailStart).toInt()).also { tail = it }
            return t.copyOfRange((offset - tailStart).toInt(), minOf((end - tailStart).toInt(), t.size))
        }
        return client.readRange(itemId, offset, (end - offset).toInt())
    }

    companion object {
        const val HEAD = 64 * 1024
        const val TAIL = 256
    }
}
