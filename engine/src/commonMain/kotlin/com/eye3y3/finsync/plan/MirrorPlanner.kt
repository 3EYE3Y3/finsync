package com.eye3y3.finsync.plan

import com.eye3y3.finsync.extras.Sidecars
import com.eye3y3.finsync.manifest.Manifest
import com.eye3y3.finsync.manifest.ManifestEntry
import com.eye3y3.finsync.model.RemotePlaylist
import com.eye3y3.finsync.model.RemoteTrack
import com.eye3y3.finsync.naming.Naming
import com.eye3y3.finsync.playlist.Playlists

/**
 * Decides what a sync should do. A pure function: it only looks at the lists it is given.
 * Files that are not in the manifest are never planned for deletion or replacement.
 */
object MirrorPlanner {
    /** [disk] maps every relative path now on the destination (outside .finsync) to its size. */
    fun plan(
        tracks: List<RemoteTrack>,
        playlists: List<RemotePlaylist>,
        manifest: Manifest,
        disk: Map<String, Long>,
        options: PlanOptions = PlanOptions(),
    ): SyncPlan {
        val mirror = MirrorPathBuilder.build(tracks, options.root, options.libraryFolders)
        val pathOf = mirror.paths
        val selected = select(tracks, options).sortedBy { pathOf.getValue(it.id).lowercase() }
        val selectedIds = selected.mapTo(HashSet()) { it.id }

        val diskLower = disk.mapKeys { it.key.lowercase() }
        val ownerLower = manifest.files.entries.associate { it.key.lowercase() to it.value }
        val pathOfItem = manifest.files.entries.filter { it.value.kind == Manifest.KIND_TRACK }.associate { it.value.itemId to it.key }

        val downloads = ArrayList<Download>()
        val adopts = ArrayList<Adopt>()
        val moves = ArrayList<Move>()
        val conflicts = ArrayList<Conflict>()
        for (t in selected) {
            val p = pathOf.getValue(t.id)
            val old = pathOfItem[t.id]
            val onDisk = diskLower[p.lowercase()]
            val owner = ownerLower[p.lowercase()]
            when {
                old == null -> when {
                    owner != null -> downloads += Download(t, p, replace = onDisk != null)
                    onDisk == null -> downloads += Download(t, p, replace = false)
                    onDisk == t.size -> adopts += Adopt(t, p)
                    else -> conflicts += Conflict(p, t.id, t.name)
                }
                old == p -> if (onDisk == null) downloads += Download(t, p, false)
                else if (manifest.files.getValue(p).changeKey != t.changeKey) downloads += Download(t, p, true)
                diskLower[old.lowercase()] == null ->
                    if (onDisk != null && owner == null) conflicts += Conflict(p, t.id, t.name) else downloads += Download(t, p, replace = onDisk != null)
                old.lowercase() != p.lowercase() && (onDisk != null || owner != null) -> conflicts += Conflict(p, t.id, t.name)
                else -> {
                    moves += Move(t, old, p)
                    if (manifest.files.getValue(old).changeKey != t.changeKey) downloads += Download(t, p, true)
                }
            }
        }
        val conflictIds = conflicts.mapTo(HashSet()) { it.itemId }
        val usable = selected.filter { it.id !in conflictIds }
        val refreshed = downloads.filter { it.replace }.mapTo(HashSet()) { it.track.id }

        // Cover pictures: one per album folder, unless a file FinSync doesn't own is already there.
        val wantedArt = LinkedHashMap<String, String>()
        for (t in usable) if (t.albumHasArt && t.albumId != null) wantedArt.putIfAbsent(Sidecars.coverPath(pathOf.getValue(t.id)), t.albumId)
        val art = wantedArt.filter { (path, albumId) -> needsWrite(path, albumId, Manifest.KIND_ART, manifest, diskLower) }
            .map { WriteArt(it.value, it.key) }

        // Lyrics sit next to the track.
        val wantedLyrics = LinkedHashMap<String, String>()
        for (t in usable) if (t.hasLyrics) wantedLyrics.putIfAbsent(Sidecars.lyricsPath(pathOf.getValue(t.id)), t.id)
        val lyrics = wantedLyrics.filter { (path, id) -> id in refreshed || needsWrite(path, id, Manifest.KIND_LYRICS, manifest, diskLower) }
            .map { WriteLyrics(it.value, it.key) }

        // Playlists only list tracks that will be in the mirror.
        val mirrored = usable.associateBy { it.id }
        val listPaths = Naming.resolveClashes(playlists.map { it.id to Playlists.fileName(it) })
        val wantedLists = LinkedHashMap<String, String>()
        val writes = ArrayList<WritePlaylist>()
        for (pl in playlists) {
            val content = Playlists.render(pl, mirrored, pathOf) ?: continue
            val path = listPaths.getValue(pl.id)
            wantedLists[path] = pl.id
            val entry = manifest.files[path]
            val onDisk = diskLower[path.lowercase()]
            when {
                entry != null && entry.kind == Manifest.KIND_PLAYLIST ->
                    if (onDisk == null || entry.changeKey != Playlists.fingerprint(content)) writes += WritePlaylist(pl.id, pl.name, path, content)
                onDisk != null -> conflicts += Conflict(path, pl.id, pl.name)
                else -> writes += WritePlaylist(pl.id, pl.name, path, content)
            }
        }

        // Deletes: only manifest entries whose reason to exist is gone, and never a path something else now wants.
        val wanted = HashSet<String>()
        selected.mapTo(wanted) { pathOf.getValue(it.id).lowercase() }
        for (m in listOf(wantedArt, wantedLyrics, wantedLists)) m.keys.mapTo(wanted) { it.lowercase() }
        val deletes = manifest.files.entries.filter { (path, e) ->
            path.lowercase() !in wanted && when (e.kind) {
                Manifest.KIND_TRACK -> e.itemId !in selectedIds
                Manifest.KIND_ART -> path !in wantedArt
                Manifest.KIND_LYRICS -> path !in wantedLyrics
                else -> path !in wantedLists
            }
        }.map { Delete(it.key, it.value) }

        val trackDeletes = deletes.count { it.entry.kind == Manifest.KIND_TRACK }
        val have = manifest.tracks().size
        val brake = when {
            have > 0 && tracks.isEmpty() -> "the server returned no tracks, which looks like a mistake"
            trackDeletes > options.brakeFraction * have ->
                "this would delete $trackDeletes of $have synced tracks, more than ${(options.brakeFraction * 100).toInt()}%"
            else -> null
        }

        val totals = PlanTotals(
            download = Tally(downloads.size, downloads.sumOf { it.track.size }),
            adopt = Tally(adopts.size, adopts.sumOf { it.track.size }),
            move = Tally(moves.size, moves.sumOf { it.track.size }),
            delete = Tally(deletes.size, deletes.sumOf { it.entry.size }),
            art = Tally(art.size),
            lyrics = Tally(lyrics.size),
            playlists = Tally(writes.size, writes.sumOf { it.content.length.toLong() }),
            conflicts = Tally(conflicts.size, conflicts.sumOf { disk[it.path] ?: 0L }),
            bytesNeeded = downloads.sumOf { it.track.size } + writes.sumOf { it.content.length.toLong() },
            namesChanged = selected.count { it.id in mirror.changed },
        )
        return SyncPlan(downloads, adopts, moves, deletes, art, lyrics, writes, conflicts, totals, brake, options.allowLargeDelete)
    }

    /** True when FinSync should (re)write [path]: it is ours but gone or for another item, or nothing is there. */
    private fun needsWrite(path: String, itemId: String, kind: String, manifest: Manifest, diskLower: Map<String, Long>): Boolean {
        val entry: ManifestEntry? = manifest.files[path]
        val present = path.lowercase() in diskLower
        return when {
            entry != null && entry.kind == kind -> !present || entry.itemId != itemId
            else -> !present
        }
    }

    private fun select(tracks: List<RemoteTrack>, o: PlanOptions): List<RemoteTrack> {
        var list = tracks
        if (o.artists.isNotEmpty()) {
            val names = o.artists.mapTo(HashSet()) { it.lowercase() }
            list = list.filter { t -> (listOfNotNull(t.albumArtist) + t.artists).any { it.lowercase() in names } }
        }
        val limit = o.maxBytes ?: return list
        val albums = list.groupBy { Triple(it.albumArtist ?: it.artists.firstOrNull() ?: "", it.album ?: "", it.albumId ?: "") }
            .toSortedMap(compareBy({ it.first.lowercase() }, { it.second.lowercase() }, { it.third }))
        val out = ArrayList<RemoteTrack>()
        var total = 0L
        for (album in albums.values) {
            val size = album.sumOf { it.size }
            if (total + size > limit) break
            total += size
            out += album
        }
        return out
    }
}
