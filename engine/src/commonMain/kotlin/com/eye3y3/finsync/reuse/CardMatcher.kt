package com.eye3y3.finsync.reuse

import com.eye3y3.finsync.model.RemoteTrack
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlin.math.abs

/** An audio file on the card outside FinSync's own folder. [path] is relative to the folder FinSync was given ("/"). */
@Serializable
data class CardFile(val path: String, val size: Long)

/** A card file holding [track]'s audio: it becomes [target] instead of [track] being downloaded. */
data class Reuse(val track: RemoteTrack, val target: String, val source: CardFile, val server: AudioLayout, val card: AudioLayout) {
    /** Tags take the same room in both: the file is moved and its tags overwritten in place, no copy needed. */
    val inPlace get() = server.headerLength == card.headerLength && server.trailerLength == card.trailerLength
}

/** A card file that is a copy of a library track the mirror already has (or gets from another card file). */
@Serializable
data class Leftover(val source: CardFile, val trackId: String, val trackName: String)

data class CardReport(val reuses: List<Reuse>, val leftovers: List<Leftover>, val notInLibrary: List<CardFile>) {
    companion object { val EMPTY = CardReport(emptyList(), emptyList(), emptyList()) }
}

/** Opens a card file for reading. */
interface CardFiles {
    suspend fun <T> read(path: String, block: suspend (ByteReader) -> T): T
}

/**
 * Finds which card files hold the same recording as a library track, by content rather than name:
 * duration first (from the file's own headers and Jellyfin's run time), then the length of the audio
 * apart from its tags, FLAC's stored audio MD5, and finally sampled bytes of the audio compared with
 * the server's copy through Range requests. Formats whose tags can't be separated from the audio match
 * only when the whole file is identical.
 */
class CardMatcher(
    private val card: CardFiles,
    private val server: (RemoteTrack) -> ByteReader,
) {
    private val serverLayouts = HashMap<String, AudioLayout?>()

    /**
     * [tracks] is the whole library; [wanted] maps the ids of tracks the mirror still needs to their paths.
     * [progress] gets (files looked at, files in total); [onFile] gets each file and the track it holds (or null).
     */
    suspend fun match(
        files: List<CardFile>, tracks: List<RemoteTrack>, wanted: Map<String, String>,
        progress: (Int, Int) -> Unit = { _, _ -> }, onFile: (CardFile, RemoteTrack?) -> Unit = { _, _ -> },
    ): CardReport {
        val byFamily = tracks.groupBy { family(it.container) }
        val bySize = tracks.groupBy { it.size }
        val matches = ArrayList<Pair<CardFile, Triple<RemoteTrack, AudioLayout, AudioLayout>>>()
        val unmatched = ArrayList<CardFile>()
        files.forEachIndexed { i, f ->
            currentCoroutineContext().ensureActive() // reading the card doesn't suspend, so check for Stop here
            progress(i, files.size)
            val found = try {
                card.read(f.path) { r -> AudioLayouts.read(r)?.let { cl -> findTrack(r, cl, byFamily, bySize) } }
            } catch (e: Exception) {
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                null
            }
            if (found == null) unmatched += f else matches += f to found
            onFile(f, found?.first)
        }
        progress(files.size, files.size)
        // Several card copies of one track: reuse the one that needs no copying (same-sized tags), the rest are spares.
        val reuses = ArrayList<Reuse>()
        val leftovers = ArrayList<Leftover>()
        for ((_, group) in matches.groupBy { it.second.first.id }) {
            val t = group.first().second.first
            val options = group.map { (f, m) -> Reuse(t, wanted[t.id] ?: "", f, m.second, m.third) }
            val chosen = if (t.id in wanted) options.sortedWith(compareBy({ !it.inPlace }, { it.source.path })).first() else null
            if (chosen != null) reuses += chosen
            options.filter { it !== chosen }.forEach { leftovers += Leftover(it.source, t.id, t.name) }
        }
        return CardReport(reuses.sortedBy { it.target }, leftovers.sortedBy { it.source.path }, unmatched)
    }

    private suspend fun findTrack(
        r: ByteReader, cl: AudioLayout, byFamily: Map<String, List<RemoteTrack>>, bySize: Map<Long, List<RemoteTrack>>,
    ): Triple<RemoteTrack, AudioLayout, AudioLayout>? {
        val candidates = if (cl.separable && cl.durationMs != null) {
            val tolerance = if (cl.format == "flac") 150 else 1500
            byFamily[cl.format].orEmpty()
                .map { it to abs(it.runTimeTicks / 10_000 - cl.durationMs) }
                .filter { it.second <= tolerance }
                .sortedBy { it.second }.map { it.first }
        } else bySize[cl.size].orEmpty()
        for (t in candidates.take(MAX_CANDIDATES)) {
            val sr = server(t)
            val sl = serverLayout(t, sr) ?: continue
            if (sl.format != cl.format && cl.separable) continue
            if (sl.payloadLength != cl.payloadLength) continue
            if (!cl.separable && sl.size != cl.size) continue
            if (sl.audioMd5 != null && cl.audioMd5 != null && sl.audioMd5 != cl.audioMd5) continue
            if (samePayload(sr, sl, r, cl)) return Triple(t, sl, cl)
        }
        return null
    }

    private suspend fun serverLayout(t: RemoteTrack, r: ByteReader): AudioLayout? =
        if (serverLayouts.containsKey(t.id)) serverLayouts[t.id]
        else (try { AudioLayouts.read(r) } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            null
        }).also { serverLayouts[t.id] = it }

    companion object {
        const val MAX_CANDIDATES = 6
        const val SAMPLE = 16 * 1024

        fun family(container: String?): String {
            val c = container.orEmpty().lowercase()
            return when {
                "flac" in c -> "flac"
                c == "mp3" || c.startsWith("mp3") -> "mp3"
                else -> c
            }
        }

        /** Offsets (within the audio) of the samples compared: start, quarters and end. */
        fun sampleOffsets(length: Long): List<Long> {
            if (length <= SAMPLE) return listOf(0L)
            return listOf(0L, length / 4, length / 2, length * 3 / 4, length - SAMPLE).map { it.coerceIn(0, length - SAMPLE) }.distinct()
        }

        suspend fun samePayload(a: ByteReader, al: AudioLayout, b: ByteReader, bl: AudioLayout): Boolean {
            if (al.payloadLength != bl.payloadLength) return false
            for (o in sampleOffsets(al.payloadLength)) {
                val n = minOf(SAMPLE.toLong(), al.payloadLength - o).toInt()
                if (!a.read(al.payloadStart + o, n).contentEquals(b.read(bl.payloadStart + o, n))) return false
            }
            return true
        }
    }
}
