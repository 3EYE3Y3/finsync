package com.eye3y3.finsync.sync

import com.eye3y3.finsync.jellyfin.FinSyncError
import com.eye3y3.finsync.manifest.Manifest
import com.eye3y3.finsync.manifest.ManifestStore
import com.eye3y3.finsync.plan.PlanOptions
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncEngineTest {
    private val fs = FakeFileSystem()
    private val dest = "/card".toPath()
    private val server = MockJellyfin(
        listOf(
            MockTrack("t1", "Art/Alb/01 One.flac", "al1", flac(300), lyrics = true, art = true),
            MockTrack("t2", "Art/Alb/02 Two.flac", "al1", flac(400), art = true),
            MockTrack("t3", "Art/Alb/03 Three.flac", "al1", flac(500)),
        ),
        mapOf("Mix" to listOf("t2", "t1")),
    )

    private fun engine(free: Long? = null, concurrency: Int = 1) =
        SyncEngine(server.client(), fs, dest, SyncOptions(PlanOptions(root = "/lib"), concurrency), { free }, retryBackoffMs = 1)

    private fun manifest() = ManifestStore(fs, dest).load()!!

    @Test fun planOnlyChangesNothing() = runTest {
        val p = engine().plan()
        assertEquals(3, p.downloads.size)
        assertEquals("Art/Alb/01 One.flac", p.downloads[0].path)
        assertEquals(1200L + 12, p.totals.bytesNeeded - p.totals.playlists.bytes)
        assertFalse(fs.exists(dest))
    }

    @Test fun executeWritesEverythingAndRecordsIt() = runTest {
        val e = engine()
        val msgs = ArrayList<String>()
        val r = e.execute(e.plan()) { msgs += it }
        assertTrue(r.ok)
        assertEquals(3, r.downloaded)
        assertContentEquals(flac(300), fs.read(dest / "Art/Alb/01 One.flac") { readByteArray() })
        assertContentEquals(byteArrayOf(1, 2, 3), fs.read(dest / "Art/Alb/cover.jpg") { readByteArray() })
        assertEquals("[00:01.00]la\n", fs.read(dest / "Art/Alb/01 One.lrc") { readUtf8() })
        assertTrue(fs.read(dest / "Mix.m3u8") { readUtf8() }.startsWith("#EXTM3U\n#EXTINF:1,Art - t2\nArt/Alb/02 Two.flac\n"))
        val m = manifest()
        assertEquals(setOf("track", "art", "lyrics", "playlist"), m.files.values.map { it.kind }.toSet())
        assertEquals("srv1", m.serverId)
        assertTrue(msgs.any { it.startsWith("Downloaded 3 of 3") })
        assertTrue(fs.list(dest / ".finsync/partial").isEmpty())
        assertFalse(fs.exists(dest / ".finsync/lock"))
    }

    @Test fun fileThatAppearsAfterPlanningIsLeftAlone() = runTest {
        val e = engine()
        val plan = e.plan()
        fs.createDirectories(dest / "Art/Alb")
        fs.write(dest / "Art/Alb/01 One.flac") { writeUtf8("mine") }
        fs.write(dest / "Mix.m3u8") { writeUtf8("my list") }
        val r = e.execute(plan)
        assertEquals("mine", fs.read(dest / "Art/Alb/01 One.flac") { readUtf8() })
        assertEquals("my list", fs.read(dest / "Mix.m3u8") { readUtf8() })
        assertEquals(2, r.downloaded)
        assertTrue(r.failures.any { it.startsWith("Art/Alb/01 One.flac") })
        assertFalse("Art/Alb/01 One.flac" in manifest().files)
    }

    @Test fun manifestPathsCannotLeaveTheFolder() {
        assertFailsWith<IllegalArgumentException> { ManifestStore.resolve(dest, "../etc/passwd") }
        assertFailsWith<IllegalArgumentException> { ManifestStore.resolve(dest, "/etc/passwd") }
    }

    @Test fun lockLeftByAKilledSyncDoesNotBlockTheNextOne() = runTest {
        fs.createDirectories(dest / ".finsync")
        fs.write(dest / ".finsync/lock") { writeUtf8("FinSync is syncing this folder\nowner=dead\nheartbeat=0\n") }
        val e = engine()
        e.execute(e.plan())
        assertEquals(3, manifest().tracks().size)
        assertFalse(fs.exists(dest / ".finsync/lock"))
    }

    @Test fun runningSyncsLockIsRespected() = runTest {
        ManifestStore(fs, dest).lock() // another sync, just started
        val e = engine()
        assertFailsWith<FinSyncError.Busy> { e.execute(e.plan()) }
    }

    @Test fun secondRunHasNothingToDo() = runTest {
        engine().let { it.execute(it.plan()) }
        assertTrue(engine().plan().isEmpty)
    }

    @Test fun manifestIsSavedAfterEachFileSoAnInterruptionLosesNothing() = runTest {
        server.refuseFromCall = 3
        val e = engine()
        assertFailsWith<FinSyncError.DownloadNotAllowed> { e.execute(e.plan()) }
        assertEquals(setOf("Art/Alb/01 One.flac", "Art/Alb/02 Two.flac"), manifest().files.keys)
        assertTrue(fs.exists(dest / "Art/Alb/02 Two.flac"))
        assertFalse(fs.exists(dest / ".finsync/lock"))
        server.refuseFromCall = null
        val e2 = engine()
        assertEquals(1, e2.execute(e2.plan()).downloaded) // art, lyrics and playlists come after, then only track 3 was missing
    }

    @Test fun refusesWhenThereIsNotEnoughFreeSpace() = runTest {
        val e = engine(free = 1_000_000)
        val err = assertFailsWith<FinSyncError.NotEnoughSpace> { e.execute(e.plan()) }
        assertTrue("Not enough free space" in err.message!!)
        assertEquals(0, server.downloadCalls)
    }

    @Test fun needsMarginOfOnePercentPlus200Mb() = runTest {
        // Enough for the files alone (1200 B) but not for the 200 MB margin.
        assertFailsWith<FinSyncError.NotEnoughSpace> { engine(free = 199_999_999).let { it.execute(it.plan()) } }
        engine(free = 200_002_000).let { assertTrue(it.execute(it.plan()).ok) }
    }

    @Test fun trippedBrakeStopsEverythingUnlessAllowed() = runTest {
        engine().let { it.execute(it.plan()) }
        server.tracks = emptyList()
        val e = engine()
        val plan = e.plan()
        assertTrue(plan.brakeTripped)
        assertFailsWith<FinSyncError.DeletionBrake> { e.execute(plan) }
        assertTrue(fs.exists(dest / "Art/Alb/01 One.flac"))
        assertEquals(3, manifest().tracks().size)
        val r = e.execute(plan.copy(allowLargeDelete = true))
        assertEquals(6, r.deleted) // 3 tracks, cover, lyrics, playlist
        assertFalse(fs.exists(dest / "Art"))
    }

    @Test fun adoptedFilesAreRecordedWithoutDownloading() = runTest {
        fs.createDirectories(dest / "Art/Alb")
        fs.write(dest / "Art/Alb/01 One.flac") { write(flac(300)) }
        val e = engine()
        val r = e.execute(e.plan())
        assertEquals(1, r.adopted)
        assertEquals(2, r.downloaded)
        assertTrue(manifest().files.getValue("Art/Alb/01 One.flac").adopted)
    }

    @Test fun deletesOnlyManifestFilesAndKeepsUsersOwn() = runTest {
        fs.createDirectories(dest)
        engine().let { it.execute(it.plan()) }
        fs.write(dest / "Art/Alb/my notes.txt") { writeUtf8("keep") }
        server.tracks = server.tracks.filter { it.id != "t3" }
        val e = engine()
        val r = e.execute(e.plan().copy(allowLargeDelete = true))
        assertEquals(1, r.deleted)
        assertFalse(fs.exists(dest / "Art/Alb/03 Three.flac"))
        assertTrue(fs.exists(dest / "Art/Alb/my notes.txt"))
        assertTrue(fs.exists(dest / "Art/Alb/01 One.flac"))
    }

    @Test fun serverChangeDownloadsAgainAndRenameMoves() = runTest {
        engine().let { it.execute(it.plan()) }
        server.tracks = server.tracks.map {
            when (it.id) {
                "t1" -> MockTrack("t1", it.path, it.albumId, flac(350, 1), etag = "e2", lyrics = true, art = true)
                "t2" -> MockTrack("t2", "Art/Alb/02 Renamed.flac", it.albumId, it.data, art = true)
                else -> it
            }
        }
        server.downloadCalls = 0
        val e = engine()
        val r = e.execute(e.plan())
        assertEquals(1, server.downloadCalls)
        assertEquals(1, r.moved)
        assertContentEquals(flac(350, 1), fs.read(dest / "Art/Alb/01 One.flac") { readByteArray() })
        assertTrue(fs.exists(dest / "Art/Alb/02 Renamed.flac"))
        assertFalse(fs.exists(dest / "Art/Alb/02 Two.flac"))
        assertFalse(manifest().files.containsKey("Art/Alb/02 Two.flac"))
    }

    @Test fun differentServerIsRefused() = runTest {
        fs.createDirectories(dest / ".finsync")
        ManifestStore(fs, dest).save(Manifest(serverId = "other"))
        assertFailsWith<FinSyncError.WrongTarget> { engine().plan() }
    }
}
