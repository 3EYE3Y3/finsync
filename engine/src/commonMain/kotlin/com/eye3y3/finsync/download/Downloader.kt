package com.eye3y3.finsync.download

import com.eye3y3.finsync.jellyfin.FinSyncError
import com.eye3y3.finsync.jellyfin.JellyfinClient
import com.eye3y3.finsync.manifest.ManifestStore
import com.eye3y3.finsync.naming.Naming
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.delay
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use
import kotlin.coroutines.cancellation.CancellationException

/**
 * Downloads originals into `<dest>/.finsync/partial/<itemId>.part`, resuming with a Range request after
 * an interruption, and moves a file to its final path only once its size and first bytes are right.
 */
class Downloader(
    private val client: JellyfinClient,
    private val fs: FileSystem,
    private val partialDir: Path,
    private val retries: Int = 3,
    private val backoffMs: Long = 500,
) {
    constructor(client: JellyfinClient, fs: FileSystem, store: ManifestStore) : this(client, fs, store.partialDir)

    /** Fetches item [itemId] (expected to be [expectedSize] bytes) and puts it at [target]. [label] is used in messages. */
    suspend fun fetch(itemId: String, expectedSize: Long, target: Path, label: String = target.name) {
        fs.createDirectories(partialDir)
        val part = partialDir / "${tempName(itemId)}.part"
        var attempt = 0
        while (true) {
            try {
                attemptOnce(itemId, expectedSize, part, target, label)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: FinSyncError) {
                if (!e.retryable || attempt >= retries) throw e
            } catch (e: Exception) {
                if (attempt >= retries) throw FinSyncError.Problem("The download of $label was interrupted and could not be resumed.")
            }
            delay(backoffMs shl attempt)
            attempt++
        }
    }

    private suspend fun attemptOnce(itemId: String, expected: Long, part: Path, target: Path, label: String) {
        var have = sizeOf(part)
        if (have > expected) {
            fs.delete(part)
            have = 0
        }
        if (have < expected || !fs.exists(part)) {
            client.download(itemId, have) { status, _, body ->
                val append = status == 206 && have > 0
                val sink = (if (append) fs.appendingSink(part) else fs.sink(part)).buffer()
                var written = if (append) have else 0L
                sink.use {
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = body.readAvailable(buf, 0, buf.size)
                        if (n < 0) break
                        // Never more than the server said the file holds: a longer answer is wrong, and must not fill the card.
                        if (written + n > expected) { written += n; break }
                        it.write(buf, 0, n)
                        written += n
                    }
                }
                if (written > expected) { fs.delete(part); throw FinSyncError.BadDownload(label) }
            }
        }
        val got = sizeOf(part)
        if (got != expected) {
            if (got > expected) fs.delete(part) // too long can't be resumed; too short can
            throw FinSyncError.BadDownload(label)
        }
        val head = fs.read(part) { readByteArray(minOf(12L, got)) }
        if (!AudioSignature.looksRight(Naming.splitExtension(target.name).second, head)) {
            fs.delete(part)
            throw FinSyncError.BadDownload(label)
        }
        target.parent?.let { fs.createDirectories(it) }
        fs.atomicMove(part, target)
    }

    private fun sizeOf(p: Path): Long = if (fs.exists(p)) fs.metadata(p).size ?: 0L else 0L
}

/**
 * Checks a download by its first bytes, not its name: anything that starts like a known audio format
 * passes (an MP3 named `.m4a` is still an MP3). A file named as a known format that starts like none of
 * them is rejected; other names pass on size alone.
 */
object AudioSignature {
    private val KNOWN = setOf("flac", "mp3", "aac", "ogg", "oga", "opus", "m4a", "m4b", "mp4", "alac", "wav", "wv", "ape", "dff", "dsf", "aif", "aiff")

    /** The format the bytes [h] start like (flac, mp3, ogg, mp4, ...), or null. */
    fun detect(h: ByteArray): String? {
        fun at(i: Int, s: String) = h.size >= i + s.length && s.indices.all { h[i + it] == s[it].code.toByte() }
        fun sync() = h.size >= 2 && h[0] == 0xFF.toByte() && (h[1].toInt() and 0xE0) == 0xE0
        val asf = byteArrayOf(0x30, 0x26, 0xB2.toByte(), 0x75)
        return when {
            at(0, "fLaC") -> "flac"
            at(0, "ID3") -> "id3" // ID3 tags, then MP3 (or, rarely, FLAC/AAC) audio
            at(0, "OggS") -> "ogg"
            at(4, "ftyp") -> "mp4"
            at(0, "RIFF") && at(8, "WAVE") -> "wav"
            at(0, "FORM") && (at(8, "AIFF") || at(8, "AIFC")) -> "aiff"
            at(0, "wvpk") -> "wv"
            at(0, "MAC ") -> "ape"
            at(0, "FRM8") -> "dff"
            at(0, "DSD ") -> "dsf"
            h.size >= 4 && asf.indices.all { h[it] == asf[it] } -> "asf"
            sync() -> "mpeg" // MP3 frame or AAC ADTS
            else -> null
        }
    }

    fun looksRight(ext: String, h: ByteArray): Boolean = detect(h) != null || ext.lowercase() !in KNOWN
}

/**
 * A file name for temporary files of server item [id]. Jellyfin ids are plain hex, but they come from
 * the server, so anything else (a "../" or a "/") is replaced by a hash and can't name a path elsewhere.
 */
fun tempName(id: String): String =
    if (id.length in 1..64 && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }) id
    else "id-" + id.encodeToByteArray().fold(0xcbf29ce484222325uL) { h, b -> (h xor b.toUByte().toULong()) * 0x100000001b3uL }.toString(16)
