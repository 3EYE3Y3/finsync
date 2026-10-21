package com.eye3y3.finsync.playlist

import com.eye3y3.finsync.extras.Sidecars
import com.eye3y3.finsync.jellyfin.LyricLine
import com.eye3y3.finsync.model.RemotePlaylist
import com.eye3y3.finsync.model.RemoteTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaylistsTest {
    private fun tr(id: String, name: String, artist: String?, secs: Long) =
        RemoteTrack(id, name, null, null, null, listOfNotNull(artist), null, null, null, null, null, 1, "flac", "k", secs * 10_000_000, false)

    private val tracks = mapOf("1" to tr("1", "Song One", "Band", 215), "2" to tr("2", "Two\nlines", null, 61))
    private val paths = mapOf("1" to "Band/Al/01 Song One.flac", "2" to "x/02 Two lines.flac")

    @Test fun rendersExtinfAndRelativePaths() {
        val text = Playlists.render(RemotePlaylist("p", "Mix", listOf("1", "2")), tracks, paths)
        assertEquals(
            "#EXTM3U\n#EXTINF:215,Band - Song One\nBand/Al/01 Song One.flac\n#EXTINF:61,Unknown Artist - Two lines\nx/02 Two lines.flac\n",
            text,
        )
    }

    @Test fun leavesOutTracksNotInTheMirrorAndHasNoBom() {
        val text = Playlists.render(RemotePlaylist("p", "Mix", listOf("9", "1")), tracks, paths)!!
        assertEquals(1, text.lines().count { it.endsWith(".flac") })
        assertEquals('#', text[0])
    }

    @Test fun emptyPlaylistRendersNothing() {
        assertNull(Playlists.render(RemotePlaylist("p", "Mix", listOf("9")), tracks, paths))
    }

    @Test fun fileNameIsSafeAndKeepsExtension() {
        assertEquals("AC_DC Mix.m3u8", Playlists.fileName(RemotePlaylist("p", "AC/DC Mix", emptyList())))
        assertEquals("a".repeat(95) + ".m3u8", Playlists.fileName(RemotePlaylist("p", "a".repeat(120), emptyList())))
    }

    @Test fun fingerprintChangesWithText() {
        assertEquals(Playlists.fingerprint("a"), Playlists.fingerprint("a"))
        assert(Playlists.fingerprint("a") != Playlists.fingerprint("b"))
    }

    @Test fun coverAndLyricsPathsSitNextToTracks() {
        assertEquals("A/B/cover.jpg", Sidecars.coverPath("A/B/01 x.flac"))
        assertEquals("cover.jpg", Sidecars.coverPath("x.flac"))
        assertEquals("A/B/01 x.lrc", Sidecars.lyricsPath("A/B/01 x.flac"))
        assertEquals("A/Mr. B.lrc", Sidecars.lyricsPath("A/Mr. B"))
    }

    @Test fun lrcTimestamps() {
        val lrc = Sidecars.toLrc(listOf(LyricLine("Hello", 0), LyricLine("World", 83_450L * 10_000), LyricLine("plain", null)))
        assertEquals("[00:00.00]Hello\n[01:23.45]World\nplain\n", lrc)
    }
}
