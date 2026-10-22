package com.eye3y3.finsync.reuse

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.eye3y3.finsync.download.AudioSignature

/**
 * Where a file's audio data sits, apart from its tags. Retagging a FLAC rewrites only its metadata
 * blocks, and retagging an MP3 only its ID3/APE tags, so two copies of the same recording with
 * different tags have the same [payloadLength] and identical payload bytes. For other formats
 * ([separable] false) the payload is the whole file.
 */
data class AudioLayout(
    val format: String,
    val size: Long,
    val payloadStart: Long,
    val payloadEnd: Long,
    /** Playing time worked out from the file itself, or null when it can't be. */
    val durationMs: Long?,
    /** FLAC only: the MD5 of the decoded audio stored in STREAMINFO (hex), or null if not set. */
    val audioMd5: String? = null,
    val totalSamples: Long? = null,
) {
    val payloadLength get() = payloadEnd - payloadStart
    val separable get() = format == "flac" || format == "mp3"
    val headerLength get() = payloadStart
    val trailerLength get() = size - payloadEnd
}

object AudioLayouts {
    private const val MAX_TAGS = 16
    private const val MAX_FLAC_BLOCKS = 1024
    private const val MAX_HEADER_BYTES = 64L shl 20

    /** Reads [r]'s layout, or null if it isn't recognisable audio. */
    suspend fun read(r: ByteReader): AudioLayout? {
        val size = r.size
        if (size < 16) return null
        var off = 0L
        var tags = 0
        while (true) { // ID3v2 tags (there may be more than one) before FLAC or MP3 audio
            // A crafted file could chain endless empty tags: real files have one or two.
            if (++tags > MAX_TAGS) return null
            currentCoroutineContext().ensureActive()
            val h = r.read(off, 10)
            if (h.size < 10 || !h.startsWith("ID3")) break
            off += 10 + syncsafe(h, 6) + if ((h[5].toInt() and 0x10) != 0) 10 else 0
            if (off >= size) return null
        }
        val head = r.read(off, 4)
        return when {
            head.startsWith("fLaC") -> flac(r, off)
            // Only exactly where the audio must start: random bytes elsewhere can look like a frame header.
            Frame.parse(head) != null -> mp3(r, off)
            else -> AudioSignature.detect(r.read(0, 12))?.let { AudioLayout(it, size, 0, size, null) }
        }
    }

    private suspend fun flac(r: ByteReader, start: Long): AudioLayout? {
        var p = start + 4
        var md5: String? = null
        var samples: Long? = null
        var rate = 0
        var blocks = 0
        while (true) {
            // Real FLAC files have a handful of metadata blocks; a crafted one could have millions of empty ones.
            if (++blocks > MAX_FLAC_BLOCKS || p - start > MAX_HEADER_BYTES) return null
            currentCoroutineContext().ensureActive()
            val h = r.read(p, 4)
            if (h.size < 4) return null
            val last = (h[0].toInt() and 0x80) != 0
            val type = h[0].toInt() and 0x7F
            val len = (u(h[1]) shl 16) or (u(h[2]) shl 8) or u(h[3])
            if (type == 0 && len >= 34) {
                val si = r.read(p + 4, 34)
                rate = (u(si[10]) shl 12) or (u(si[11]) shl 4) or (u(si[12]) shr 4)
                samples = ((u(si[13]).toLong() and 0x0F) shl 32) or (u(si[14]).toLong() shl 24) or (u(si[15]).toLong() shl 16) or
                    (u(si[16]).toLong() shl 8) or u(si[17]).toLong()
                md5 = si.copyOfRange(18, 34).takeIf { b -> b.any { it != 0.toByte() } }?.toHex()
            }
            p += 4 + len
            if (p > r.size) return null
            if (last) break
        }
        val end = if (r.size - p >= 128 && r.read(r.size - 128, 3).startsWith("TAG")) r.size - 128 else r.size
        val duration = if (rate > 0 && samples != null && samples > 0) samples * 1000 / rate else null
        return AudioLayout("flac", r.size, p, end, duration, md5, samples)
    }

    private suspend fun mp3(r: ByteReader, start: Long): AudioLayout {
        var end = r.size
        if (end - start >= 128 && r.read(end - 128, 3).startsWith("TAG")) end -= 128
        if (end - start >= 15 && r.read(end - 9, 9).startsWith("LYRICS200")) {
            val n = r.read(end - 15, 6).decodeToString().trim().toLongOrNull()
            if (n != null && end - 15 - n > start) end -= n + 15
        }
        if (end - start >= 32) {
            val f = r.read(end - 32, 32)
            if (f.startsWith("APETAGEX")) {
                val tagSize = le32(f, 12)
                val hasHeader = (le32(f, 20) and 0x80000000L) != 0L
                val cut = tagSize + if (hasHeader) 32 else 0
                if (end - cut > start) end -= cut
            }
        }
        val frame = Frame.parse(r.read(start, 4))
        val duration = frame?.let { fr ->
            val x = r.read(start, 200)
            val xing = 4 + if (fr.mpeg1) (if (fr.mono) 17 else 32) else (if (fr.mono) 9 else 17)
            val frames = when {
                x.at(xing, "Xing") || x.at(xing, "Info") -> if ((be32(x, xing + 4) and 1L) != 0L) be32(x, xing + 8) else null
                x.at(36, "VBRI") -> be32(x, 36 + 14)
                else -> null
            }
            if (frames != null && frames > 0) frames * fr.samplesPerFrame * 1000 / fr.sampleRate
            else if (fr.bitrateKbps > 0) (end - start) * 8 / fr.bitrateKbps
            else null
        }
        return AudioLayout("mp3", r.size, start, end, duration)
    }

    private class Frame(val mpeg1: Boolean, val mono: Boolean, val bitrateKbps: Int, val sampleRate: Int, val samplesPerFrame: Int) {
        companion object {
            private val SR = intArrayOf(44100, 48000, 32000)
            private val V1L1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)
            private val V1L2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
            private val V1L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
            private val V2L1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
            private val V2L23 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

            fun parse(h: ByteArray): Frame? {
                if (h.size < 4 || u(h[0]) != 0xFF || (u(h[1]) and 0xE0) != 0xE0) return null
                val version = (u(h[1]) shr 3) and 3 // 0 = 2.5, 2 = 2, 3 = 1
                val layer = (u(h[1]) shr 1) and 3   // 1 = III, 2 = II, 3 = I
                val bi = (u(h[2]) shr 4) and 0xF
                val si = (u(h[2]) shr 2) and 3
                if (version == 1 || layer == 0 || bi == 15 || si == 3) return null
                val mpeg1 = version == 3
                val rate = SR[si] / when (version) { 3 -> 1; 2 -> 2; else -> 4 }
                val table = when {
                    mpeg1 && layer == 3 -> V1L1
                    mpeg1 && layer == 2 -> V1L2
                    mpeg1 -> V1L3
                    layer == 3 -> V2L1
                    else -> V2L23
                }
                val spf = when (layer) { 3 -> 384; 2 -> 1152; else -> if (mpeg1) 1152 else 576 }
                return Frame(mpeg1, ((u(h[3]) shr 6) and 3) == 3, table[bi], rate, spf)
            }
        }
    }
}

internal fun u(b: Byte) = b.toInt() and 0xFF
private fun syncsafe(b: ByteArray, i: Int) = (u(b[i]) shl 21) or (u(b[i + 1]) shl 14) or (u(b[i + 2]) shl 7) or u(b[i + 3])
private fun le32(b: ByteArray, i: Int) = u(b[i]).toLong() or (u(b[i + 1]).toLong() shl 8) or (u(b[i + 2]).toLong() shl 16) or (u(b[i + 3]).toLong() shl 24)
private fun be32(b: ByteArray, i: Int) = if (b.size < i + 4) 0L else
    (u(b[i]).toLong() shl 24) or (u(b[i + 1]).toLong() shl 16) or (u(b[i + 2]).toLong() shl 8) or u(b[i + 3]).toLong()
private fun ByteArray.at(i: Int, s: String) = size >= i + s.length && s.indices.all { this[i + it] == s[it].code.toByte() }
internal fun ByteArray.startsWith(s: String) = at(0, s)
internal fun ByteArray.toHex() = joinToString("") { (u(it) + 0x100).toString(16).substring(1) }
