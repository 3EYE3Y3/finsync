package com.eye3y3.finsync.plan

import com.eye3y3.finsync.manifest.Manifest
import com.eye3y3.finsync.manifest.ManifestEntry
import com.eye3y3.finsync.model.RemotePlaylist
import com.eye3y3.finsync.model.RemoteTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MirrorPlannerTest {
    private fun track(
        id: String, path: String?, size: Long = 100, key: String = "$size:e", artist: String = "Art", album: String? = "Alb",
        albumId: String? = "al-$album", art: Boolean = false, lyrics: Boolean = false, n: Int = 1, disc: Int? = null,
    ) = RemoteTrack(
        id, "Song $id", album, albumId, artist, listOf(artist), n, disc, null, 2001, path, size, "flac", key, 1800000000, lyrics, art,
    )

    private val lib = "/media/Music"
    private fun t(id: String, rel: String, size: Long = 100, key: String = "$size:e") = track(id, "$lib/$rel", size, key)

    private fun manifest(vararg e: Triple<String, String, Long>): Manifest {
        val m = Manifest()
        for ((path, id, size) in e) m.files[path] = ManifestEntry(id, "track", size, "$size:e")
        return m
    }

    private fun plan(tracks: List<RemoteTrack>, m: Manifest = Manifest(), disk: Map<String, Long> = emptyMap(), pl: List<RemotePlaylist> = emptyList(), o: PlanOptions = PlanOptions()) =
        MirrorPlanner.plan(tracks, pl, m, disk, if (o.root == null && o.libraryFolders.isEmpty()) o.copy(libraryFolders = listOf(lib)) else o)

    @Test fun newTrackIsDownloadedToMirroredPath() {
        val p = plan(listOf(t("1", "Art/Alb/01 a.flac"), t("2", "Art/Alb/02 b.flac")))
        assertEquals(listOf("Art/Alb/01 a.flac", "Art/Alb/02 b.flac"), p.downloads.map { it.path })
        assertTrue(p.downloads.none { it.replace })
        assertEquals(200, p.totals.bytesNeeded)
        assertFalse(p.brakeTripped)
    }

    @Test fun mirrorUsesLongestCommonFolderWithoutRootOption() {
        val p = plan(listOf(track("1", "/a/b/Art/x/1.flac"), track("2", "/a/b/Art/y/2.flac")))
        assertEquals(listOf("x/1.flac", "y/2.flac"), p.downloads.map { it.path })
    }

    @Test fun rootOptionAndLibraryFoldersChooseTheBase() {
        val tracks = listOf(track("1", "/m/Music/Art/1.flac"), track("2", "/m/Other/Art/2.flac"))
        assertEquals(listOf("Music/Art/1.flac", "Other/Art/2.flac"), plan(tracks, o = PlanOptions(root = "/m")).downloads.map { it.path })
        val withFolders = plan(tracks, o = PlanOptions(libraryFolders = listOf("/m/Music", "/m/Other", "/m")))
        assertEquals(listOf("Art/1.flac", "Art/2.flac"), withFolders.downloads.map { it.path })
    }

    @Test fun trackOutsideRootOrWithoutPathUsesTidyLayout() {
        val p = plan(listOf(track("1", "/elsewhere/x.flac"), track("2", null, album = "Disc", n = 3)), o = PlanOptions(root = lib))
        assertEquals(setOf("Art/Disc (2001)/03 Song 2.flac", "Art/Alb (2001)/01 Song 1.flac"), p.downloads.map { it.path }.toSet())
    }

    @Test fun unsafeNamesAreChangedAndCounted() {
        val p = plan(listOf(t("1", "AC/DC?/01 x.FLAC"), t("2", "Fine/02 y.flac")))
        assertEquals(1, p.totals.namesChanged)
        assertTrue("AC_DC_/01 x.flac" in p.downloads.map { it.path } || p.downloads.any { it.path.endsWith("01 x.flac") })
    }

    @Test fun caseClashesGetSuffix() {
        val p = plan(listOf(t("a", "ABBA/x.flac"), t("b", "Abba/x.flac")))
        assertEquals(setOf("ABBA/x.flac", "Abba/x (2).flac"), p.downloads.map { it.path }.toSet())
    }

    @Test fun unchangedTrackNeedsNothing() {
        val p = plan(listOf(t("1", "A/1.flac")), manifest(Triple("A/1.flac", "1", 100)), mapOf("A/1.flac" to 100L))
        assertTrue(p.isEmpty)
        assertTrue(p.conflicts.isEmpty())
    }

    @Test fun changedKeyRedownloadsOverOurOwnFile() {
        val p = plan(listOf(t("1", "A/1.flac", 100, "100:new")), manifest(Triple("A/1.flac", "1", 100)), mapOf("A/1.flac" to 100L))
        assertEquals(listOf(Download(p.downloads[0].track, "A/1.flac", true)), p.downloads)
    }

    @Test fun missingOwnFileIsDownloadedAgain() {
        val p = plan(listOf(t("1", "A/1.flac")), manifest(Triple("A/1.flac", "1", 100)))
        assertEquals(1, p.downloads.size)
        assertFalse(p.downloads[0].replace)
    }

    @Test fun adoptWhenFileExistsWithEqualSize() {
        val p = plan(listOf(t("1", "A/1.flac", 555)), disk = mapOf("A/1.flac" to 555L))
        assertEquals(listOf("A/1.flac"), p.adopts.map { it.path })
        assertTrue(p.downloads.isEmpty() && p.conflicts.isEmpty())
        assertEquals(0, p.totals.bytesNeeded)
    }

    @Test fun adoptMatchesPathIgnoringCase() {
        val p = plan(listOf(t("1", "A/1.flac", 5)), disk = mapOf("a/1.FLAC" to 5L))
        assertEquals(1, p.adopts.size)
    }

    @Test fun differentSizeIsAConflictAndNeverOverwritten() {
        val p = plan(listOf(t("1", "A/1.flac", 555)), disk = mapOf("A/1.flac" to 1L))
        assertEquals(listOf("A/1.flac"), p.conflicts.map { it.path })
        assertTrue(p.downloads.isEmpty() && p.adopts.isEmpty())
    }

    @Test fun conflictedTrackIsNotInPlaylistsOrExtras() {
        val tr = track("1", "$lib/A/1.flac", 555, art = true, lyrics = true)
        val p = plan(listOf(tr), disk = mapOf("A/1.flac" to 1L), pl = listOf(RemotePlaylist("p", "Mix", listOf("1"))))
        assertTrue(p.art.isEmpty() && p.lyrics.isEmpty() && p.playlists.isEmpty())
    }

    @Test fun renamedItemIsMovedNotDownloaded() {
        val p = plan(listOf(t("1", "New/1.flac")), manifest(Triple("Old/1.flac", "1", 100)), mapOf("Old/1.flac" to 100L))
        assertEquals(listOf(Move(p.moves[0].track, "Old/1.flac", "New/1.flac")), p.moves)
        assertTrue(p.downloads.isEmpty() && p.deletes.isEmpty())
    }

    @Test fun movedAndChangedItemIsMovedThenDownloaded() {
        val p = plan(listOf(t("1", "New/1.flac", 100, "100:v2")), manifest(Triple("Old/1.flac", "1", 100)), mapOf("Old/1.flac" to 100L))
        assertEquals(1, p.moves.size)
        assertEquals("New/1.flac", p.downloads.single().path)
    }

    @Test fun moveOntoSomeoneElsesFileIsAConflict() {
        val p = plan(listOf(t("1", "New/1.flac")), manifest(Triple("Old/1.flac", "1", 100)), mapOf("Old/1.flac" to 100L, "New/1.flac" to 7L))
        assertTrue(p.moves.isEmpty())
        assertEquals(listOf("New/1.flac"), p.conflicts.map { it.path })
    }

    @Test fun goneItemsAreDeletedOnlyIfInManifest() {
        val tracks = (1..20).map { t("k$it", "A/$it.flac") } + t("keep", "A/keep.flac")
        val m = manifest(Triple("A/keep.flac", "keep", 100), Triple("A/gone.flac", "gone", 50))
        val disk = mapOf("A/keep.flac" to 100L, "A/gone.flac" to 50L, "A/users own.flac" to 9L, "notes.txt" to 3L)
        val p = plan(tracks, m, disk)
        assertEquals(listOf("A/gone.flac"), p.deletes.map { it.path })
        assertTrue(p.deletes.none { it.path == "A/users own.flac" || it.path == "notes.txt" })
    }

    @Test fun filesNotInTheManifestAreNeverDeletedOrOverwrittenWhateverTheServerSays() {
        val disk = mapOf("A/mine.flac" to 10L, "B/other.mp3" to 20L, "cover.jpg" to 5L)
        // Empty server answer with an empty manifest: nothing to do at all.
        val p = plan(emptyList(), Manifest(), disk)
        assertTrue(p.deletes.isEmpty() && p.downloads.isEmpty() && !p.brakeTripped)
        // Server has a track at an unowned path with a different size: conflict, no replace.
        val q = plan(listOf(t("1", "A/mine.flac", 99)), Manifest(), disk)
        assertTrue(q.downloads.isEmpty() && q.deletes.isEmpty())
        assertEquals(1, q.conflicts.size)
    }

    @Test fun deletedPathIsNotRemovedWhenAnotherItemTakesItOver() {
        val m = manifest(Triple("A/1.flac", "old", 100))
        val p = plan(listOf(t("new", "A/1.flac"), t("x", "A/x.flac")), m, mapOf("A/1.flac" to 100L))
        assertTrue(p.deletes.isEmpty())
        assertEquals(true, p.downloads.single { it.path == "A/1.flac" }.replace)
    }

    @Test fun brakeTripsWhenMoreThanTenPercentWouldBeDeleted() {
        val m = Manifest()
        for (i in 1..20) m.files["A/$i.flac"] = ManifestEntry("i$i", "track", 1, "1:e")
        val keep = (3..20).map { t("i$it", "A/$it.flac", 1) } // 2 of 20 deleted = exactly 10%
        assertFalse(plan(keep, m).brakeTripped)
        val p = plan(keep.drop(1), m) // 3 of 20 = 15%
        assertTrue(p.brakeTripped)
        assertFalse(p.executable)
        assertEquals(3, p.deletes.size)
        assertTrue(plan(keep.drop(1), m, o = PlanOptions(allowLargeDelete = true)).executable)
        assertTrue(plan(keep.drop(1), m, o = PlanOptions(brakeFraction = 0.2)).executable)
    }

    @Test fun brakeTripsOnEmptyServerResponse() {
        val m = manifest(Triple("A/1.flac", "1", 100))
        val p = plan(emptyList(), m)
        assertTrue(p.brakeTripped)
        assertTrue("no tracks" in p.brakeReason!!)
        assertEquals(1, p.deletes.size)
        assertFalse(p.executable)
    }

    @Test fun artIsPlannedPerFolderUnlessAForeignCoverExists() {
        val a = listOf(track("1", "$lib/A/Al/1.flac", art = true), track("2", "$lib/A/Al/2.flac", art = true, n = 2))
        assertEquals(listOf(WriteArt("al-Alb", "A/Al/cover.jpg")), plan(a).art)
        assertTrue(plan(a, disk = mapOf("A/Al/cover.jpg" to 3L)).art.isEmpty())
        val ours = Manifest().apply { files["A/Al/cover.jpg"] = ManifestEntry("al-Alb", "art", 3) }
        assertTrue(plan(a, ours, mapOf("A/Al/cover.jpg" to 3L)).art.isEmpty())
        assertEquals(1, plan(a, ours).art.size)
        assertTrue(plan(listOf(track("1", "$lib/A/Al/1.flac"))).art.isEmpty())
    }

    @Test fun lyricsAreWrittenNextToTracks() {
        val p = plan(listOf(track("1", "$lib/A/Al/1.flac", lyrics = true), track("2", "$lib/A/Al/2.flac")))
        assertEquals(listOf(WriteLyrics("1", "A/Al/1.lrc")), p.lyrics)
        assertTrue(plan(listOf(track("1", "$lib/A/Al/1.flac", lyrics = true)), disk = mapOf("A/Al/1.lrc" to 4L)).lyrics.isEmpty())
    }

    @Test fun playlistsCoverOnlyMirroredTracksAndSkipExistingForeignFiles() {
        val tracks = listOf(t("1", "A/1.flac"), t("2", "A/2.flac"))
        val pl = listOf(RemotePlaylist("p", "Road/Trip", listOf("2", "gone", "1")))
        val p = plan(tracks, pl = pl)
        val w = p.playlists.single()
        assertEquals("Road_Trip.m3u8", w.path)
        assertEquals(listOf("A/2.flac", "A/1.flac"), w.content.lines().filter { it.endsWith(".flac") })
        val q = plan(tracks, disk = mapOf("Road_Trip.m3u8" to 1L), pl = pl)
        assertTrue(q.playlists.isEmpty())
        assertEquals("Road_Trip.m3u8", q.conflicts.single().path)
        val ours = Manifest().apply { files["Road_Trip.m3u8"] = ManifestEntry("p", "playlist", 1, com.eye3y3.finsync.playlist.Playlists.fingerprint(w.content)) }
        assertTrue(plan(tracks, ours, mapOf("Road_Trip.m3u8" to 1L), pl).playlists.isEmpty())
    }

    @Test fun staleExtrasAreDeletedButOnlyOwnedOnes() {
        val m = Manifest().apply {
            files["A/cover.jpg"] = ManifestEntry("al", "art", 3)
            files["Old.m3u8"] = ManifestEntry("p", "playlist", 3, "x")
        }
        val p = plan(emptyList(), m, mapOf("A/cover.jpg" to 3L, "Old.m3u8" to 3L, "Mine.m3u8" to 9L))
        assertEquals(setOf("A/cover.jpg", "Old.m3u8"), p.deletes.map { it.path }.toSet())
    }

    @Test fun artistFilter() {
        val tracks = listOf(track("1", "$lib/X/1.flac", artist = "X"), track("2", "$lib/Y/2.flac", artist = "Y"))
        val p = plan(tracks, o = PlanOptions(artists = listOf("y")))
        assertEquals(listOf("2"), p.downloads.map { it.track.id })
    }

    @Test fun sizeFilterTakesWholeAlbumsInOrder() {
        val tracks = listOf(
            track("1", "$lib/A/B1/1.flac", 40, artist = "A", album = "B1"), track("2", "$lib/A/B1/2.flac", 40, artist = "A", album = "B1", n = 2),
            track("3", "$lib/A/B2/1.flac", 30, artist = "A", album = "B2"), track("4", "$lib/Z/C/1.flac", 5, artist = "Z", album = "C"),
        )
        val p = plan(tracks, o = PlanOptions(maxBytes = 100))
        assertEquals(setOf("1", "2"), p.downloads.map { it.track.id }.toSet()) // B2 would overshoot (110), so we stop there
        assertEquals(setOf("1", "2", "3"), plan(tracks, o = PlanOptions(maxBytes = 110)).downloads.map { it.track.id }.toSet())
    }

    @Test fun itemsOutsideTheFilterAreDeletedButBrakeProtects() {
        val m = manifest(Triple("X/1.flac", "1", 100), Triple("Y/2.flac", "2", 100))
        val tracks = listOf(track("1", "$lib/X/1.flac", artist = "X"), track("2", "$lib/Y/2.flac", artist = "Y"))
        val p = plan(tracks, m, mapOf("X/1.flac" to 100L, "Y/2.flac" to 100L), o = PlanOptions(artists = listOf("X")))
        assertEquals(listOf("Y/2.flac"), p.deletes.map { it.path })
        assertTrue(p.brakeTripped)
    }

    @Test fun multiDiscAlbumsInTidyModeGetDiscPrefix() {
        val tracks = listOf(track("1", null, disc = 1), track("2", null, disc = 2, n = 1))
        val paths = plan(tracks).downloads.map { it.path }.toSet()
        assertEquals(setOf("Art/Alb (2001)/01-01 Song 1.flac", "Art/Alb (2001)/02-01 Song 2.flac"), paths)
    }

    @Test fun planningTwiceGivesTheSamePlan() {
        val tracks = (1..5).map { t("$it", "A/$it.flac") }
        assertEquals(plan(tracks), plan(tracks.reversed()))
        assertNull(plan(tracks).brakeReason)
        assertNotNull(plan(tracks).totals)
    }
}
