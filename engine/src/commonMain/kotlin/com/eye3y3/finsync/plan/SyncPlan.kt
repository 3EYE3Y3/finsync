package com.eye3y3.finsync.plan

import com.eye3y3.finsync.manifest.ManifestEntry
import com.eye3y3.finsync.model.RemoteTrack
import com.eye3y3.finsync.reuse.CardReport

/** How to choose what to sync. */
data class PlanOptions(
    /** Server folder the mirror is relative to; null works it out. */
    val root: String? = null,
    /** Library folders as the server knows them (admins only). */
    val libraryFolders: List<String> = emptyList(),
    val brakeFraction: Double = 0.10,
    val allowLargeDelete: Boolean = false,
    /** Stop after whole albums add up to this many bytes (for small test syncs). */
    val maxBytes: Long? = null,
    /** Only these artists (album artist or track artist, ignoring case). */
    val artists: List<String> = emptyList(),
)

data class Download(val track: RemoteTrack, val path: String, val replace: Boolean)
data class Adopt(val track: RemoteTrack, val path: String)
data class Move(val track: RemoteTrack, val from: String, val to: String)
data class Delete(val path: String, val entry: ManifestEntry)
data class WriteArt(val albumId: String, val path: String)
data class WriteLyrics(val trackId: String, val path: String)
data class WritePlaylist(val playlistId: String, val name: String, val path: String, val content: String)

/** A file FinSync will not touch because it isn't FinSync's. */
data class Conflict(val path: String, val itemId: String, val name: String)

data class Tally(val files: Int = 0, val bytes: Long = 0)

data class PlanTotals(
    val download: Tally, val adopt: Tally, val move: Tally, val delete: Tally,
    val art: Tally, val lyrics: Tally, val playlists: Tally, val conflicts: Tally,
    /** Bytes that must be free to carry the plan out (before the safety margin). */
    val bytesNeeded: Long,
    /** Tracks whose destination name differs from the server's because of the naming rules. */
    val namesChanged: Int,
    /** Tracks taken from music already on the card instead of downloaded. */
    val reuse: Tally = Tally(),
)

/** Everything a sync would do. Nothing in a plan touches the disk. */
data class SyncPlan(
    val downloads: List<Download>,
    val adopts: List<Adopt>,
    val moves: List<Move>,
    val deletes: List<Delete>,
    val art: List<WriteArt>,
    val lyrics: List<WriteLyrics>,
    val playlists: List<WritePlaylist>,
    val conflicts: List<Conflict>,
    val totals: PlanTotals,
    /** Why the deletion brake tripped, or null. */
    val brakeReason: String?,
    val allowLargeDelete: Boolean,
    val serverId: String = "",
    val userId: String = "",
    val libraryId: String = "",
    /** Music already on the card: what is reused, what is a spare copy, what isn't in the library. */
    val card: CardReport = CardReport.EMPTY,
) {
    val brakeTripped get() = brakeReason != null

    /** False when the brake tripped and the user has not agreed to a large delete. */
    val executable get() = brakeReason == null || allowLargeDelete

    val isEmpty get() = totals.let { listOf(it.download, it.adopt, it.move, it.delete, it.art, it.lyrics, it.playlists, it.reuse).all { t -> t.files == 0 } }
}
