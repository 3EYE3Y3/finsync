package com.eye3y3.finsync.plan

import com.eye3y3.finsync.model.RemoteTrack
import com.eye3y3.finsync.naming.Naming
import com.eye3y3.finsync.naming.TrackMeta

/** Item id to destination path, and which items had their server path changed by the naming rules. */
class MirrorPaths(val paths: Map<String, String>, val changed: Set<String>)

/** Works out where each track goes on the destination. */
object MirrorPathBuilder {
    /**
     * Normal mode follows the server's folders below a root: [root] if given, else the longest of the
     * server's [libraryFolders] that holds the track, else the longest folder common to all tracks.
     * Tracks without a usable path get the tidy layout.
     */
    fun build(tracks: List<RemoteTrack>, root: String?, libraryFolders: List<String>): MirrorPaths {
        val common = commonFolder(tracks.mapNotNull { it.serverPath }.map(::norm))
        val folders = libraryFolders.map(::norm)
        val discCounts = tracks.filter { it.albumId != null }.groupBy { it.albumId }
            .mapValues { (_, ts) -> ts.maxOf { it.discNumber ?: 1 } }
        val raw = HashMap<String, String>()
        val first = tracks.map { t ->
            val path = t.serverPath?.let(::norm)
            val base = root?.let(::norm) ?: path?.let { p -> folders.filter { p.startsWith("$it/") }.maxByOrNull { it.length } } ?: common
            val rel = path?.let { relativeTo(base, it) }
            val safe = rel?.let { Naming.safeRelativePath(it) }?.takeIf { it.isNotEmpty() }
            if (rel != null && safe != null) raw[t.id] = rel
            t.id to (safe ?: tidy(t, discCounts[t.albumId]))
        }
        val final = Naming.resolveClashes(first)
        return MirrorPaths(final, raw.filter { (id, rel) -> final[id] != rel }.keys)
    }

    private fun norm(p: String) = p.replace('\\', '/').trimEnd('/')

    private fun relativeTo(base: String, path: String): String? = when {
        base.isEmpty() -> path.trimStart('/')
        path.startsWith("$base/") -> path.substring(base.length + 1)
        else -> null
    }

    private fun commonFolder(paths: List<String>): String {
        val dirs = paths.map { it.substringBeforeLast('/', "").split('/') }
        if (dirs.isEmpty()) return ""
        var common = dirs.first()
        for (d in dirs.drop(1)) common = common.zip(d).takeWhile { (a, b) -> a == b }.map { it.first }
        return common.joinToString("/")
    }

    private fun tidy(t: RemoteTrack, discCount: Int?): String {
        val fromPath = t.serverPath?.let { Naming.splitExtension(norm(it).substringAfterLast('/')).second }
        val ext = fromPath?.takeIf { it.isNotEmpty() } ?: t.container?.substringBefore(',')?.let { if (it == "mov") "m4a" else it } ?: "bin"
        return Naming.tidyPath(
            TrackMeta(t.albumArtist, t.artists.firstOrNull(), t.album, t.year, t.trackNumber, t.discNumber, t.discCount ?: discCount, t.name, ext),
        )
    }
}
