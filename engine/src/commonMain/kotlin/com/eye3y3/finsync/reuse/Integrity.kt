package com.eye3y3.finsync.reuse

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Reads a whole card file before it is reused, to catch damage that sampled comparisons can miss.
 * FLAC: every frame's header CRC-8 and whole-frame CRC-16 must check out, and frames must run without
 * gaps from the end of the metadata to the end of the audio. MP3: the frame headers must chain from
 * start to end (MP3 has no checksum over the audio itself, so damage inside a frame can't be seen).
 * Other formats aren't checked here.
 */
object Integrity {
    private const val WINDOW = 1 shl 20
    private const val LOOKAHEAD = 32

    suspend fun check(r: ByteReader, l: AudioLayout): Boolean = when (l.format) {
        "flac" -> flac(r, l)
        "mp3" -> mp3(r, l)
        else -> true
    }

    private suspend fun flac(r: ByteReader, l: AudioLayout): Boolean {
        val start = l.payloadStart
        val end = l.payloadEnd
        if (end - start < 16) return false
        var frameStart = start
        var crc = 0          // CRC-16 of [frameStart, i)
        var lag1 = 0         // ... of [frameStart, i - 1)
        var lag2 = 0         // ... of [frameStart, i - 2)
        var b1 = 0           // byte at i - 1
        var b2 = 0           // byte at i - 2
        var pos = start
        while (pos < end) {
            val n = minOf(WINDOW.toLong(), end - pos).toInt()
            currentCoroutineContext().ensureActive()
            val buf = r.read(pos, minOf(n + LOOKAHEAD.toLong(), end - pos).toInt())
            if (buf.size < n) return false
            if (pos == start && !flacHeaderAt(buf, 0)) return false
            for (j in 0 until n) {
                val v = buf[j].toInt() and 0xFF
                // A new frame can start here: the previous frame's last two bytes are its CRC-16.
                if (v == 0xFF && pos + j - frameStart >= 16 && ((b2 shl 8) or b1) == lag2 && flacHeaderAt(buf, j)) {
                    frameStart = pos + j
                    crc = 0; lag1 = 0; lag2 = 0
                }
                lag2 = lag1
                lag1 = crc
                crc = ((crc shl 8) xor CRC16[((crc shr 8) xor v) and 0xFF]) and 0xFFFF
                b2 = b1
                b1 = v
            }
            pos += n
        }
        return ((b2 shl 8) or b1) == lag2
    }

    /** A FLAC frame header (sync code, valid fields, CRC-8) starts at b[p]. */
    private fun flacHeaderAt(b: ByteArray, p: Int): Boolean {
        fun at(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
        if (p + 6 > b.size) return false
        if (at(p) != 0xFF || (at(p + 1) and 0xFE) != 0xF8) return false
        val bs = at(p + 2) shr 4
        val sr = at(p + 2) and 0xF
        val ch = at(p + 3) shr 4
        val ss = (at(p + 3) shr 1) and 7
        if (bs == 0 || sr == 15 || ch > 10 || ss == 3 || (at(p + 3) and 1) != 0) return false
        var q = p + 4
        val first = at(q)
        val extra = when { first < 0x80 -> 0; first and 0xE0 == 0xC0 -> 1; first and 0xF0 == 0xE0 -> 2; first and 0xF8 == 0xF0 -> 3
            first and 0xFC == 0xF8 -> 4; first and 0xFE == 0xFC -> 5; first == 0xFE -> 6; else -> return false }
        q += 1
        repeat(extra) { if ((at(q) and 0xC0) != 0x80) return false; q++ }
        q += when (bs) { 6 -> 1; 7 -> 2; else -> 0 }
        q += when (sr) { 12 -> 1; 13, 14 -> 2; else -> 0 }
        if (q >= b.size) return false
        var crc = 0
        for (k in p until q) crc = CRC8[(crc xor at(k)) and 0xFF]
        return crc == at(q)
    }

    private suspend fun mp3(r: ByteReader, l: AudioLayout): Boolean {
        val end = l.payloadEnd
        var p = l.payloadStart
        var frames = 0
        var bufStart = -1L
        var buf = ByteArray(0)
        while (p + 4 <= end) {
            if (p < bufStart || p + 4 > bufStart + buf.size) {
                bufStart = p
                currentCoroutineContext().ensureActive()
                buf = r.read(p, minOf(WINDOW.toLong(), end - p).toInt())
                if (buf.size < 4) return false
            }
            val i = (p - bufStart).toInt()
            val len = mp3FrameLength(buf, i) ?: return false
            p += len
            frames++
        }
        // The last frame may be cut short by a few bytes; anything else means the chain broke.
        return frames > 0 && p - end < 2048
    }

    /** Length in bytes of the MPEG audio frame whose header is [h], or null if it isn't one. */
    fun mp3FrameLength(h: ByteArray, at: Int = 0): Int? {
        if (h.size < at + 4 || u(h[at]) != 0xFF || (u(h[at + 1]) and 0xE0) != 0xE0) return null
        val version = (u(h[at + 1]) shr 3) and 3
        val layer = (u(h[at + 1]) shr 1) and 3
        val bi = (u(h[at + 2]) shr 4) and 0xF
        val si = (u(h[at + 2]) shr 2) and 3
        if (version == 1 || layer == 0 || bi == 0 || bi == 15 || si == 3) return null
        val mpeg1 = version == 3
        val rate = intArrayOf(44100, 48000, 32000)[si] / when (version) { 3 -> 1; 2 -> 2; else -> 4 }
        val kbps = when {
            mpeg1 && layer == 3 -> intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)
            mpeg1 && layer == 2 -> intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
            mpeg1 -> intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
            layer == 3 -> intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
            else -> intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
        }[bi] * 1000
        val pad = (u(h[at + 2]) shr 1) and 1
        return when {
            layer == 3 -> (12 * kbps / rate + pad) * 4
            layer == 2 || mpeg1 -> 144 * kbps / rate + pad
            else -> 72 * kbps / rate + pad
        }
    }

    private val CRC8 = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor 0x07) and 0xFF else (c shl 1) and 0xFF }
        c
    }
    private val CRC16 = IntArray(256) { i ->
        var c = i shl 8
        repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x8005) and 0xFFFF else (c shl 1) and 0xFFFF }
        c
    }
}
