package com.eye3y3.finsync.app.saf

import com.eye3y3.finsync.app.testing.AudioFixtures
import com.eye3y3.finsync.app.testing.MockJellyfin
import com.eye3y3.finsync.app.testing.MockTrack
import com.eye3y3.finsync.plan.PlanOptions
import com.eye3y3.finsync.sync.SyncEngine
import com.eye3y3.finsync.sync.SyncOptions
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The whole card flow on a fake SAF tree: old music adopted into FinSync's folder, then spares removed on request. */
class CardOnSafTest {
    private val flacA = AudioFixtures.flacFrames(200_000, 1)
    private val flacB = AudioFixtures.flacFrames(150_000, 2)
    private val serverA = AudioFixtures.flac(flacA, "TITLE=Alpha, new", padding = 400)
    private val serverB = AudioFixtures.flac(flacB, "TITLE=Bravo, tidied", padding = 16, md5Seed = 2)
    private val serverC = AudioFixtures.flac(AudioFixtures.flacFrames(50_000, 3), "TITLE=Charlie", md5Seed = 3)
    private val server = MockJellyfin(
        listOf(
            MockTrack("tA", "Muse/Absolution (2003)/01 Alpha.flac", "al1", serverA, runTimeTicks = 100_000_000),
            MockTrack("tB", "Muse/Absolution (2003)/02 Bravo.flac", "al1", serverB, runTimeTicks = 100_000_000),
            MockTrack("tC", "Muse/Absolution (2003)/03 Charlie.flac", "al1", serverC, runTimeTicks = 100_000_000),
        ),
    )

    private fun oldCard(tree: FakeDocumentTree) {
        tree.put("Music/Old backup/Some Artist - Some Album (2003)/01 alpha.flac", AudioFixtures.flac(flacA, "TITLE=Alpha, old", padding = 400))
        tree.put("Music/Old backup/Some Artist - Some Album (2003)/02 bravo.flac", AudioFixtures.flac(flacB, "T=b", padding = 900, md5Seed = 2))
        tree.put("Music/Old backup/Some Artist - Some Album (2003)/cover.jpg", byteArrayOf(1, 2))
        tree.put("Music/Copies/Alpha.flac", AudioFixtures.flac(flacA, "TITLE=a spare", padding = 7))
        tree.put("Music/Seeker downloads/Live bootleg.flac", AudioFixtures.flac(AudioFixtures.flacFrames(80_000, 9), "TITLE=boot", md5Seed = 9))
    }

    private fun engine(tree: FakeDocumentTree) = SyncEngine(
        server.client(), SafFileSystem(tree), "/Jellyfin".toPath(),
        SyncOptions(PlanOptions(root = "/lib"), concurrency = 2, reuseFrom = "/".toPath()), { tree.freeBytes() }, retryBackoffMs = 1,
    )

    private fun check(tree: FakeDocumentTree) = runTest {
        oldCard(tree)
        val e = engine(tree)
        val plan = e.plan()
        assertEquals(2, plan.totals.reuse.files)
        assertEquals(listOf("tC"), plan.downloads.map { it.track.id })
        val r = e.execute(plan)
        assertTrue(r.ok, r.failures.toString())
        assertEquals(2, r.reused)
        for (t in server.tracks) assertContentEquals(t.data, tree.read("Jellyfin/" + t.path), t.path)
        assertFalse(tree.exists("Music/Old backup/Some Artist - Some Album (2003)/01 alpha.flac"))
        assertFalse(tree.exists("Music/Old backup/Some Artist - Some Album (2003)/02 bravo.flac"))
        assertTrue(tree.exists("Music/Old backup/Some Artist - Some Album (2003)/cover.jpg"), "only music files are taken")
        assertTrue(tree.exists("Music/Copies/Alpha.flac"))
        assertTrue(tree.exists("Music/Seeker downloads/Live bootleg.flac"))
        val report = e.cardReport()!!
        assertEquals(listOf("Music/Copies/Alpha.flac"), report.leftovers.map { it.source.path })
        assertEquals(listOf("Music/Seeker downloads/Live bootleg.flac"), report.notInLibrary.map { it.path })
        assertEquals(1, e.removeLeftovers().first)
        assertFalse(tree.exists("Music/Copies"))
        assertTrue(tree.exists("Music/Seeker downloads/Live bootleg.flac"))
        assertTrue(tree.files().none { it.contains(".finsync/partial/") || it.endsWith(".tmp") }, tree.files().toString())
    }

    @Test fun adoptsOldMusicByMovingIt() {
        val tree = FakeDocumentTree(canMove = true)
        check(tree)
        assertTrue(tree.moves >= 1, "the in-place reuse moved the file rather than copying it")
    }

    @Test fun adoptsOldMusicWhenTheCardCantMove() = check(FakeDocumentTree(canMove = false))
}
