package com.eye3y3.finsync.playlist

import com.eye3y3.finsync.model.RemotePlaylist
import com.eye3y3.finsync.model.RemoteTrack
import com.eye3y3.finsync.naming.Naming

/** Builds `.m3u8` playlists that point at files inside the mirror. */
object Playlists {
    /** The playlist's file name at the root of the destination. */
    fun fileName(playlist: RemotePlaylist): String = Naming.safeFileName(playlist.name + ".m3u8")

    /**
     * UTF-8 text (no byte order mark) of the playlist, or null when none of its tracks is in the mirror.
     * [pathOf] maps item id to mirror path; tracks missing from it are left out.
     */
    fun render(playlist: RemotePlaylist, tracks: Map<String, RemoteTrack>, pathOf: Map<String, String>): String? {
        val present = playlist.trackIds.mapNotNull { id -> tracks[id]?.let { t -> pathOf[id]?.let { t to it } } }
        if (present.isEmpty()) return null
        return buildString {
            append("#EXTM3U\n")
            for ((t, path) in present) {
                val artist = t.artists.firstOrNull() ?: t.albumArtist ?: "Unknown Artist"
                append("#EXTINF:${t.runTimeTicks / 10_000_000},${oneLine(artist)} - ${oneLine(t.name)}\n")
                append(path).append('\n')
            }
        }
    }

    /** A short value that changes when the playlist text changes. */
    fun fingerprint(text: String): String = text.hashCode().toString()

    private fun oneLine(s: String) = s.replace('\n', ' ').replace('\r', ' ')
}
