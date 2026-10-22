package com.eye3y3.finsync.sync

import com.eye3y3.finsync.jellyfin.FinSyncError
import com.eye3y3.finsync.manifest.ManifestStore
import com.eye3y3.finsync.plan.Pick
import com.eye3y3.finsync.reuse.AudioFixtures
import com.eye3y3.finsync.plan.PlanOptions
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Choosing what to sync: several music libraries, and artists or albums left out. */
class WhatToSyncTest {
    private val fs = FakeFileSystem()
    private val dest = "/card".toPath()
    private val server = MockJellyfin(
        listOf(
            MockTrack("a1", "Abba/Gold/01.flac", "gold", flac(100), artist = "ABBA", album = "Gold"),
            MockTrack("a2", "Abba/Gold/02.flac", "gold", flac(110), artist = "ABBA", album = "Gold"),
            MockTrack("a3", "Abba/Arrival/01.flac", "arrival", flac(120), artist = "ABBA", album = "Arrival"),
            MockTrack("b1", "Bowie/Low/01.flac", "low", flac(130), artist = "David Bowie", album = "Low"),
            MockTrack("h1", "Hires/Kind of Blue/01.flac", "kob", flac(140), artist = "Miles Davis", album = "Kind of Blue", library = "lib2"),
        ),
        mapOf("Mix" to listOf("b1", "a1")),
    ).apply { libraries = listOf("lib1" to "Music", "lib2" to "Hi-Res") }

    private fun engine(plan: PlanOptions = PlanOptions(), ids: Set<String>? = null, all: Boolean = true, name: String? = null) =
        SyncEngine(server.client(), fs, dest, SyncOptions(plan.copy(root = "/lib"), 1, libraryName = name, libraryIds = ids, allLibraries = all), { null }, retryBackoffMs = 1)

    private fun paths(p: com.eye3y3.finsync.plan.SyncPlan) = p.downloads.map { it.path }.toSet()

    @Test fun everyMusicLibraryIsSyncedByDefault() = runTest {
        val p = engine().plan()
        assertEquals(5, p.downloads.size)
        assertEquals("lib1,lib2", p.libraryId)
    }

    @Test fun onlyChosenLibraries() = runTest {
        val p = engine(ids = setOf("lib2")).plan()
        assertEquals(setOf("Hires/Kind of Blue/01.flac"), paths(p))
    }

    @Test fun chosenLibrariesThatAreGoneAreAProblem() = runTest {
        assertFailsWith<FinSyncError.Problem> { engine(ids = setOf("gone")).plan() }
    }

    @Test fun withoutAChoiceSeveralLibrariesNeedOneNamed() = runTest {
        assertFailsWith<FinSyncError.Problem> { engine(all = false).plan() }
        assertEquals(4, engine(all = false, name = "music").plan().downloads.size)
    }

    @Test fun leftOutArtistsAndAlbumsAreNotSynced() = runTest {
        val p = engine(PlanOptions(excludedArtists = setOf("david bowie"), excludedAlbums = setOf("arrival"))).plan()
        assertEquals(setOf("Abba/Gold/01.flac", "Abba/Gold/02.flac", "Hires/Kind of Blue/01.flac"), paths(p))
        // The playlist lists only what is synced.
        assertFalse(p.playlists.single().content.contains("Bowie"))
    }

    @Test fun leavingOutWhatIsAlreadySyncedRemovesOnlyThoseFiles() = runTest {
        val e = engine()
        e.execute(e.plan())
        fs.write(dest / "Bowie/Low/notes.txt") { writeUtf8("mine") }
        val p = engine(PlanOptions(excludedArtists = setOf("David Bowie"))).plan()
        assertEquals(listOf("Bowie/Low/01.flac"), p.deletes.filter { it.entry.kind == "track" }.map { it.path })
        // One of five tracks is over the 10% brake: the user has to agree first.
        assertTrue(p.brakeTripped)
        engine(PlanOptions(excludedArtists = setOf("David Bowie"), allowLargeDelete = true)).let { it.execute(it.plan()) }
        assertFalse(fs.exists(dest / "Bowie/Low/01.flac"))
        assertTrue(fs.exists(dest / "Bowie/Low/notes.txt"), "files FinSync didn't write stay")
        assertTrue(fs.exists(dest / "Abba/Gold/01.flac"))
        assertEquals(4, ManifestStore(fs, dest).load()!!.tracks().size)
    }

    @Test fun onlyPickedArtistsAlbumsAndPlaylists() = runTest {
        assertEquals(setOf("Abba/Gold/01.flac", "Abba/Gold/02.flac", "Abba/Arrival/01.flac"),
            paths(engine(PlanOptions(pick = Pick(artists = setOf("abba")))).plan()))
        assertEquals(setOf("Abba/Arrival/01.flac", "Hires/Kind of Blue/01.flac"),
            paths(engine(PlanOptions(pick = Pick(albums = setOf("arrival", "kob")))).plan()))
        // A picked playlist brings its tracks and is the only playlist written.
        val p = engine(PlanOptions(pick = Pick(artists = setOf("Miles Davis"), playlists = setOf("pl-Mix")))).plan()
        assertEquals(setOf("Bowie/Low/01.flac", "Abba/Gold/01.flac", "Hires/Kind of Blue/01.flac"), paths(p))
        assertEquals(listOf("Mix.m3u8"), p.playlists.map { it.path })
        // Picking nothing syncs nothing, and writes no playlists.
        val none = engine(PlanOptions(pick = Pick())).plan()
        assertTrue(none.downloads.isEmpty() && none.playlists.isEmpty())
    }

    @Test fun cardCopiesOfMusicNotChosenAreNeitherReusedNorSpares() = runTest {
        val low = AudioFixtures.m4a(20_000, 13)
        val gold = AudioFixtures.m4a(20_000, 14)
        val srv = MockJellyfin(listOf(
            MockTrack("b1", "Bowie/Low/01.m4a", "low", low, artist = "David Bowie", container = "m4a", runTimeTicks = 50_000_000),
            MockTrack("a1", "Abba/Gold/01.m4a", "gold", gold, artist = "ABBA", container = "m4a", runTimeTicks = 50_000_000),
        ))
        fs.createDirectories("/old".toPath())
        fs.write("/old/low.m4a".toPath()) { write(low) }
        fs.write("/old/gold.m4a".toPath()) { write(gold) }
        val p = SyncEngine(srv.client(), fs, "/Jellyfin".toPath(),
            SyncOptions(PlanOptions(root = "/lib", excludedArtists = setOf("David Bowie")), 1, reuseFrom = "/".toPath()), { null }, retryBackoffMs = 1).plan()
        assertEquals(listOf("old/gold.m4a"), p.card.reuses.map { it.source.path })
        // Bowie's copy is left alone: not reused, not a spare to remove, and not "not in the library" either.
        assertTrue(p.card.leftovers.isEmpty() && p.card.notInLibrary.isEmpty())
    }

    @Test fun catalogListsEveryLibrarysAlbums() = runTest {
        val c = engine().catalog()
        assertEquals(listOf("Music", "Hi-Res"), c.libraries.map { it.name })
        val gold = c.albums.single { it.key == "gold" }
        assertEquals(Triple("Gold", "ABBA", 2), Triple(gold.name, gold.artist, gold.tracks))
        assertEquals("lib2", c.albums.single { it.key == "kob" }.libraryId)
        assertEquals(setOf("b1", "a1"), c.playlists.single().trackIds)
        assertFalse(fs.exists(dest), "looking changes nothing")
    }
}
