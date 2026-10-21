package com.eye3y3.finsync.model

/** One audio file on the Jellyfin server. [changeKey] is "<size>:<Etag or DateCreated>". */
data class RemoteTrack(
    val id: String,
    val name: String,
    val album: String?,
    val albumId: String?,
    val albumArtist: String?,
    val artists: List<String>,
    val trackNumber: Int?,
    val discNumber: Int?,
    val discCount: Int?,
    val year: Int?,
    val serverPath: String?,
    val size: Long,
    val container: String?,
    val changeKey: String,
    val runTimeTicks: Long,
    val hasLyrics: Boolean,
    /** True when the album has a primary picture on the server. */
    val albumHasArt: Boolean = false,
)

/** A server playlist: its name and the ids of its tracks, in order. */
data class RemotePlaylist(val id: String, val name: String, val trackIds: List<String>)
