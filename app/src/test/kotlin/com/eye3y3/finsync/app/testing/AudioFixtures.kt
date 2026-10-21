// Copy of the engine's test helper (engine/src/commonTest/.../reuse/AudioFixtures.kt): Gradle can't share KMP test code with the app's tests.
package com.eye3y3.finsync.app.testing

import kotlin.random.Random

/** Small but well-formed FLAC and MP3 files, for matching tests. Only the parts FinSync reads are real. */
object AudioFixtures {
    fun audio(n: Int, seed: Int) = Random(seed).nextBytes(n)

    /** About [n] bytes of FLAC frames with valid headers (CRC-8) and CRC-16s; the data itself is random. */
    fun flacFrames(n: Int, seed: Int): ByteArray {
        val rnd = Random(seed)
        val out = ArrayList<Byte>()
        var frame = 0
        while (out.size < n) {
            val h = byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 0xC9.toByte(), 0x18, frame.toByte()) // 4096 samples, 44.1 kHz, stereo, 16 bit
            val header = h + crc8(h).toByte()
            val data = rnd.nextBytes(900 + rnd.nextInt(400)).map { if (it == 0xFF.toByte()) 0x7F else it }.toByteArray()
            val body = header + data
            val c = crc16(body)
            (body + byteArrayOf((c shr 8).toByte(), c.toByte())).forEach { out += it }
            frame = (frame + 1) and 0x7F
        }
        return out.toByteArray()
    }

    fun crc8(b: ByteArray): Int {
        var c = 0
        for (x in b) { c = c xor (x.toInt() and 0xFF); repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor 0x07) and 0xFF else (c shl 1) and 0xFF } }
        return c
    }

    fun crc16(b: ByteArray): Int {
        var c = 0
        for (x in b) { c = c xor ((x.toInt() and 0xFF) shl 8); repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x8005) and 0xFFFF else (c shl 1) and 0xFFFF } }
        return c
    }

    /** "fLaC", STREAMINFO, a VORBIS_COMMENT block holding [tags], optional PADDING, then [frames]. */
    fun flac(frames: ByteArray, tags: String, padding: Int = 0, samples: Long = 441_000, rate: Int = 44_100, md5Seed: Int = 1): ByteArray {
        val si = ByteArray(34)
        val packed = (rate.toLong() shl 44) or (1L shl 41) or (15L shl 36) or samples // 2 channels, 16 bits
        for (i in 0 until 8) si[10 + i] = (packed shr (56 - 8 * i)).toByte()
        Random(md5Seed).nextBytes(16).copyInto(si, 18)
        val comment = tags.encodeToByteArray()
        fun block(type: Int, last: Boolean, body: ByteArray) =
            byteArrayOf(((if (last) 0x80 else 0) or type).toByte(), (body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()) + body
        return "fLaC".encodeToByteArray() + block(0, false, si) + block(4, padding == 0, comment) +
            (if (padding > 0) block(1, true, ByteArray(padding)) else ByteArray(0)) + frames
    }

    /**
     * MPEG-1 Layer III audio: chained 417-byte frames (128 kbps, 44.1 kHz) of random data, the first
     * holding a Xing header that says [frameCount] frames. About [n] bytes, whole frames only.
     */
    fun mp3Frames(n: Int, seed: Int, frameCount: Int = 383): ByteArray {
        val b = audio(maxOf(1, n / 417) * 417, seed)
        for (f in 0 until b.size / 417) byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64).copyInto(b, f * 417)
        "Xing".encodeToByteArray().copyInto(b, 36)
        byteArrayOf(0, 0, 0, 1).copyInto(b, 40)
        byteArrayOf((frameCount shr 24).toByte(), (frameCount shr 16).toByte(), (frameCount shr 8).toByte(), frameCount.toByte()).copyInto(b, 44)
        return b
    }

    /** An ID3v2.4 tag of [tags] (plus [extra] padding), [frames], then optionally an APEv2 tag and an ID3v1 tag. */
    fun mp3(frames: ByteArray, tags: String, extra: Int = 0, ape: Boolean = false, id3v1: Boolean = false): ByteArray {
        val body = tags.encodeToByteArray() + ByteArray(extra)
        val n = body.size
        val header = "ID3".encodeToByteArray() + byteArrayOf(4, 0, 0, ((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(), ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte())
        var out = header + body + frames
        if (ape) {
            val items = "ITEMDATA0123".encodeToByteArray()
            val size = items.size + 32
            val footer = "APETAGEX".encodeToByteArray() + le(2000) + le(size) + le(1) + le(0) + ByteArray(8)
            out += items + footer
        }
        if (id3v1) out += "TAG".encodeToByteArray() + ByteArray(125) { 'x'.code.toByte() }
        return out
    }

    /** A file starting like an MP4 (M4A): FinSync can't separate its tags, so it matches only whole. */
    fun m4a(n: Int, seed: Int) = byteArrayOf(0, 0, 0, 0x20) + "ftypM4A ".encodeToByteArray() + audio(n, seed)

    private fun le(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
}

/** A [ByteReader] over bytes in memory. */
class BytesReader(private val b: ByteArray) : com.eye3y3.finsync.reuse.ByteReader {
    override val size = b.size.toLong()
    override suspend fun read(offset: Long, length: Int): ByteArray = b.copyOfRange(offset.toInt(), minOf(b.size.toLong(), offset + length).toInt())
}
