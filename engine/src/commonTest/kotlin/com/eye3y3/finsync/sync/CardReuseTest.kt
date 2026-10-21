package com.eye3y3.finsync.sync

import com.eye3y3.finsync.manifest.ManifestStore
import com.eye3y3.finsync.plan.PlanOptions
import com.eye3y3.finsync.reuse.AudioFixtures
import com.eye3y3.finsync.reuse.AudioFixtures.audio
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Old music already on the card (e.g. a player's copy from before the library was tidied) is reused instead of downloaded again. */
class CardReuseTest {
    private val fs = FakeFileSystem()
    private val card = "/card".toPath()
    private val dest = card / "Jellyfin"

    private val flacA = AudioFixtures.flacFrames(40_000, 10)
    private val flacB = AudioFixtures.flacFrames(300_000, 11) // big enough for gaps between the sampled pieces
    private val mp3C = AudioFixtures.mp3Frames(50_000, 12)
    private val serverA = AudioFixtures.flac(flacA, "TITLE=Alpha (new tags)", padding = 300)
    private val serverB = AudioFixtures.flac(flacB, "TITLE=Bravo, tidied by beets", padding = 64, md5Seed = 2)
    private val serverC = AudioFixtures.mp3(mp3C, "TIT2 Charlie (new)", extra = 500, id3v1 = true)
    private val serverD = AudioFixtures.m4a(20_000, 13)
    private val serverE = AudioFixtures.flac(AudioFixtures.flacFrames(10_000, 14), "TITLE=Echo", md5Seed = 3)

    private val server = MockJellyfin(
        listOf(
            MockTrack("tA", "Art/Alb/01 Alpha.flac", "al1", serverA, runTimeTicks = 100_000_000),
            MockTrack("tB", "Art/Alb/02 Bravo.flac", "al1", serverB, runTimeTicks = 100_000_000),
            MockTrack("tC", "Art/Alb/03 Charlie.mp3", "al1", serverC, container = "mp3", runTimeTicks = 100_040_000),
            MockTrack("tD", "Art/Alb/04 Delta.m4a", "al1", serverD, container = "m4a", runTimeTicks = 50_000_000),
            MockTrack("tE", "Art/Alb/05 Echo.flac", "al1", serverE, runTimeTicks = 100_000_000),
        ),
    )

    private fun put(rel: String, bytes: ByteArray): Path {
        val p = card / rel
        fs.createDirectories(p.parent!!)
        fs.write(p) { write(bytes) }
        return p
    }

    private fun engine() = SyncEngine(
        server.client(), fs, dest, SyncOptions(PlanOptions(root = "/lib"), concurrency = 1, reuseFrom = card), { null }, retryBackoffMs = 1,
    )

    private fun oldCard() {
        put("HIFI/old rip/Alpha.flac", AudioFixtures.flac(flacA, "TITLE=Alpha (old tags)", padding = 300)) // same header size: moved in place
        put("HIFI/old rip/Bravo.flac", AudioFixtures.flac(flacB, "T=b", padding = 2000, md5Seed = 2))  // different: rebuilt
        put("HIFI/old rip/Charlie.mp3", AudioFixtures.mp3(mp3C, "TIT2 old", ape = true))                  // different tags: rebuilt
        put("Downloads/Delta.m4a", serverD.copyOf())                                                         // identical
        put("Backup/Alpha copy.flac", AudioFixtures.flac(flacA, "TITLE=another copy", padding = 10))       // a spare copy of A
        put("Seeker/Unknown.flac", AudioFixtures.flac(AudioFixtures.flacFrames(40_000, 99), "TITLE=Not on the server", md5Seed = 9)) // same length as A, other audio
        put("Seeker/Song.mp3", AudioFixtures.mp3(AudioFixtures.mp3Frames(9000, 98, frameCount = 50), "TIT2 card only"))
        put("Android/data/x.mp3", AudioFixtures.mp3(mp3C, "ignored")) // system folders aren't looked at
    }

    @Test fun planReusesMatchesAndSortsTheRest() = runTest {
        oldCard()
        val plan = engine().plan()
        assertEquals(setOf("tA", "tB", "tC", "tD"), plan.card.reuses.map { it.track.id }.toSet())
        assertEquals(listOf("tE"), plan.downloads.map { it.track.id })
        assertEquals(4, plan.totals.reuse.files)
        assertEquals(listOf("Backup/Alpha copy.flac"), plan.card.leftovers.map { it.source.path })
        assertEquals(listOf("Seeker/Song.mp3", "Seeker/Unknown.flac"), plan.card.notInLibrary.map { it.path })
        assertTrue(plan.card.reuses.single { it.track.id == "tA" }.inPlace)
        assertFalse(plan.card.reuses.single { it.track.id == "tB" }.inPlace)
        // Planning read only small pieces of the reused tracks, never whole files.
        assertTrue(server.downloadRanges.filter { it.first != "tE" }.all { it.second != null })
    }

    @Test fun syncTurnsCardFilesIntoTheLibrarysFilesByteForByte() = runTest {
        oldCard()
        val e = engine()
        val r = e.execute(e.plan())
        assertEquals(4, r.reused)
        assertEquals(1, r.downloaded)
        assertTrue(r.ok, r.failures.toString())
        for (t in server.tracks) assertContentEquals(t.data, fs.read(dest / t.path) { readByteArray() }, t.path)
        // The reused originals are gone from their old places (moved, or rebuilt and then removed)...
        for (old in listOf("HIFI/old rip/Alpha.flac", "HIFI/old rip/Bravo.flac", "HIFI/old rip/Charlie.mp3", "Downloads/Delta.m4a")) assertFalse(fs.exists(card / old), old)
        assertFalse(fs.exists(card / "HIFI"), "emptied folders are removed")
        // ...but spare copies and music the library doesn't have are untouched.
        assertTrue(fs.exists(card / "Backup/Alpha copy.flac"))
        assertTrue(fs.exists(card / "Seeker/Unknown.flac"))
        assertTrue(fs.exists(card / "Seeker/Song.mp3"))
        // Only the one track that wasn't on the card was downloaded whole.
        assertEquals(listOf("tE"), server.downloadRanges.filter { it.second == null }.map { it.first })
        val m = ManifestStore(fs, dest).load()!!
        assertEquals(5, m.tracks().size)
        assertTrue(m.tracks().values.none { it.adopted })
        val report = e.cardReport()!!
        assertEquals(listOf("Backup/Alpha copy.flac"), report.leftovers.map { it.source.path })
        assertEquals(2, report.notInLibrary.size)
        assertTrue(e.execute(e.plan()).let { it.reused == 0 && it.downloaded == 0 })
    }

    @Test fun leftoversGoOnlyWhenConfirmedAndOnlyWhileTheMirrorHasTheTrack() = runTest {
        oldCard()
        val e = engine()
        e.execute(e.plan())
        assertTrue(fs.exists(card / "Backup/Alpha copy.flac"), "nothing is removed without confirmation")
        val alpha = dest / "Art/Alb/01 Alpha.flac"
        fs.delete(alpha) // the mirror lost its copy: the spare is now the only one
        assertEquals(0 to 0L, e.removeLeftovers())
        assertTrue(fs.exists(card / "Backup/Alpha copy.flac"))
        fs.write(alpha) { write(server.tracks[0].data) } // the mirror has it again
        val (n, bytes) = e.removeLeftovers()
        assertEquals(1, n)
        assertTrue(bytes > 0)
        assertFalse(fs.exists(card / "Backup"))
        assertTrue(fs.exists(card / "Seeker/Song.mp3"), "music not in the library is never removed")
        assertTrue(e.cardReport()!!.leftovers.isEmpty())
    }

    @Test fun aSpareCopyIsReusedWhenTheMirrorLosesTheTrack() = runTest {
        oldCard()
        val e = engine()
        e.execute(e.plan())
        fs.delete(dest / "Art/Alb/01 Alpha.flac")
        val r = e.execute(e.plan())
        assertEquals(1, r.reused)
        assertContentEquals(server.tracks[0].data, fs.read(dest / "Art/Alb/01 Alpha.flac") { readByteArray() })
        assertFalse(fs.exists(card / "Backup/Alpha copy.flac"))
    }

    @Test fun aCardFileChangedAfterPlanningIsDownloadedInstead() = runTest {
        oldCard()
        val e = engine()
        val plan = e.plan()
        put("HIFI/old rip/Bravo.flac", byteArrayOf(1, 2, 3)) // replaced since planning
        val r = e.execute(plan)
        assertEquals(3, r.reused)
        assertEquals(2, r.downloaded)
        assertContentEquals(serverB, fs.read(dest / "Art/Alb/02 Bravo.flac") { readByteArray() })
        assertTrue(fs.exists(card / "HIFI/old rip/Bravo.flac"), "a file that didn't match is left where it is")
    }

    @Test fun aDamagedCardCopyIsLeftAloneAndTheTrackDownloaded() = runTest {
        oldCard()
        // Damage the old copy of B where sampled comparisons don't look (between the samples).
        val p = card / "HIFI/old rip/Bravo.flac"
        val bytes = fs.read(p) { readByteArray() }
        val at = bytes.size - flacB.size + flacB.size * 3 / 8
        for (i in 0 until 32) bytes[at + i] = (bytes[at + i] + 1).toByte()
        fs.write(p) { write(bytes) }
        val e = engine()
        val plan = e.plan()
        assertTrue(plan.card.reuses.any { it.track.id == "tB" }, "sampling alone can't see this damage")
        val r = e.execute(plan)
        assertEquals(3, r.reused)
        assertContentEquals(serverB, fs.read(dest / "Art/Alb/02 Bravo.flac") { readByteArray() })
        assertContentEquals(bytes, fs.read(p) { readByteArray() }, "the damaged copy is left as it was")
        assertEquals(listOf("HIFI/old rip/Bravo.flac"), e.cardReport()!!.damaged.map { it.path })
    }

    @Test fun theCheckReportsAtOnceAndLogsEveryFileAndPhase() = runTest {
        oldCard()
        val reports = ArrayList<String>()
        val logs = ArrayList<String>()
        val listener = object : SyncListener {
            override fun report(message: String) { reports += message }
            override fun log(message: String) { logs += message }
        }
        val e = engine()
        e.execute(e.plan(listener), listener)
        assertTrue(reports.first { "card" in it }.contains("0 of 7 files"), reports.toString()) // never a silent start
        assertTrue(reports.any { it.startsWith("Looking at the music already on the card: 7 of 7") })
        assertEquals(7, logs.count { it.startsWith("Card check: ") && "ms" in it && ("same audio" in it || "no match" in it) })
        for (phase in listOf("Plan: asking", "Card check: listing", "Card check finished", "Reuse: 4 tracks", "Reuse finished", "Downloads: ", "Downloads finished"))
            assertTrue(logs.any { it.startsWith(phase) }, "missing log: $phase in $logs")
        assertTrue(logs.count { it.startsWith("Reuse: HIFI") || it.startsWith("Reuse: Downloads") } == 4)
        assertTrue(reports.any { it.startsWith("Reusing tracks already on the card: 4 of 4") && ("GB" in it || "MB" in it) })
    }

    @Test fun stoppingMidSyncIsCleanAndTheNextSyncFinishes() = runTest {
        oldCard()
        val e = engine()
        val plan = e.plan()
        var job: Job? = null
        val stopAfterFirstReuse = object : SyncListener {
            override fun report(message: String) {}
            override fun log(message: String) { if (message.startsWith("Reuse: HIFI")) job?.cancel() } // the notification's Stop
        }
        job = launch { e.execute(plan, stopAfterFirstReuse) }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(fs.exists(dest / ".finsync/lock"), "the lock is released")
        val kept = ManifestStore(fs, dest).load()?.tracks()?.size ?: 0
        assertTrue(kept in 1..4, "work done before Stop is kept ($kept)")
        val r = e.execute(e.plan())
        assertTrue(r.ok)
        for (t in server.tracks) assertContentEquals(t.data, fs.read(dest / t.path) { readByteArray() }, t.path)
    }

    @Test fun withoutAReuseFolderNothingOnTheCardIsLookedAt() = runTest {
        oldCard()
        val e = SyncEngine(server.client(), fs, dest, SyncOptions(PlanOptions(root = "/lib")), { null }, retryBackoffMs = 1)
        val plan = e.plan()
        assertEquals(5, plan.downloads.size)
        assertTrue(plan.card.reuses.isEmpty() && plan.card.notInLibrary.isEmpty())
    }
}
