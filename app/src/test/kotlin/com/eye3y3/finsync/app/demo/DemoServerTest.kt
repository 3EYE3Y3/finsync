package com.eye3y3.finsync.app.demo

import com.eye3y3.finsync.jellyfin.JellyfinClient
import com.eye3y3.finsync.jellyfin.ServerConfig
import com.eye3y3.finsync.plan.PlanOptions
import com.eye3y3.finsync.sync.SyncEngine
import com.eye3y3.finsync.sync.SyncOptions
import com.eye3y3.finsync.app.saf.FakeDocumentTree
import com.eye3y3.finsync.app.saf.SafFileSystem
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Try it with sample music": the built-in server, a whole sync from it, and leaving demo mode. */
class DemoServerTest {
    private val assets = File("src/main/assets/demo")
    private val server = DemoServer(File(assets, "library.json").readText()) { File(assets, it).readBytes() }
    private fun client() = JellyfinClient(server.httpClient(), ServerConfig(DemoServer.URL, "dev", "test"), DemoServer.TOKEN)
    private val folder = "/FinSync sample music".toPath()

    @Test fun answersLikeAJellyfinThatAllowsDownloads() = runTest {
        val c = client()
        assertEquals("FinSync sample library", c.serverInfo().name)
        val user = c.currentUser()
        assertTrue(user.canDownload)
        val lib = c.musicLibraries(user.id).single()
        val tracks = c.tracks(user.id, lib.id)
        assertEquals(13, tracks.size)
        assertEquals(setOf("flac", "mp3"), tracks.map { it.container }.toSet())
        for (t in tracks) assertEquals(File(assets, "${t.id}.${t.container}").length(), t.size)
        assertEquals(listOf("t02", "t06", "t11", "t08", "t01"), c.playlists(user.id).single().trackIds)
        assertEquals(2, tracks.count { it.hasLyrics })
        assertTrue(c.lyrics("t01")!!.isNotEmpty())
        assertTrue(c.albumArt("al1")!!.size > 1000)
        assertContentEquals(File(assets, "t01.flac").readBytes().copyOfRange(100, 200), c.readRange("t01", 100, 100))
    }

    @Test fun wholeTrackDownloadsArePacedOneAtATime() = runBlocking {
        val http = DemoServer(File(assets, "library.json").readText(), paceMillis = 200) { File(assets, it).readBytes() }.httpClient()
        val started = System.nanoTime()
        http.get("${DemoServer.URL}/Items/t01/Download") { header(HttpHeaders.Range, "bytes=0-99") }
        http.get("${DemoServer.URL}/Users/Me")
        assertTrue(System.nanoTime() - started < 150_000_000, "ranges and metadata are not paced")
        val t0 = System.nanoTime()
        listOf("t01", "t02", "t03").map { async { http.get("${DemoServer.URL}/Items/$it/Download") } }.awaitAll()
        assertTrue(System.nanoTime() - t0 >= 600_000_000, "three tracks in parallel still take 3 x 200 ms")
    }

    private suspend fun sync(tree: FakeDocumentTree) = SyncEngine(
        client(), SafFileSystem(tree), folder, SyncOptions(PlanOptions(), reuseFrom = null), { tree.freeBytes() }, retryBackoffMs = 1,
    ).let { e -> e.execute(e.plan()) }

    @Test fun aWholeSyncFromTheSamplesIsTheRealThing() = runTest {
        val tree = FakeDocumentTree()
        tree.put("Music/old/song.mp3", byteArrayOf(1, 2, 3)) // the user's own music elsewhere
        val r = sync(tree)
        assertTrue(r.ok, r.failures.toString())
        assertEquals(13, r.downloaded)
        val files = tree.files().filter { it.startsWith("FinSync sample music/") && "/.finsync/" !in it }
        assertEquals(13, files.count { it.endsWith(".flac") || it.endsWith(".mp3") })
        assertEquals(4, files.count { it.endsWith("cover.jpg") })
        assertEquals(2, files.count { it.endsWith(".lrc") })
        val playlist = files.single { it.endsWith(".m3u8") }
        assertEquals(5, tree.read(playlist)!!.decodeToString().lines().count { it.isNotBlank() && !it.startsWith("#") })
        // Names SD cards reject are made safe ("Why? Because: Yes*").
        assertTrue(files.none { n -> n.any { it in "?:*\"<>|" } }, files.toString())
        assertTrue(files.any { "Why_ Because_ Yes_" in it }, files.toString())
        // Every track arrived byte for byte.
        val c = client()
        for (t in c.tracks("sample-user", "samplelib")) {
            val local = files.single { it.endsWith(".${t.container}") && t.name.replace(Regex("[?:*\"<>|]"), "_") in it }
            assertContentEquals(File(assets, "${t.id}.${t.container}").readBytes(), tree.read(local), t.name)
        }
        assertEquals(0, sync(tree).downloaded, "a second sync has nothing to do")
        assertTrue(tree.exists("Music/old/song.mp3"))
    }

    @Test fun leavingDemoModeRemovesTheSamplesAndNothingElse() = runTest {
        val tree = FakeDocumentTree()
        tree.put("Music/old/song.mp3", byteArrayOf(1, 2, 3))
        sync(tree)
        removeSamples(SafFileSystem(tree), folder)
        assertEquals(listOf("Music/old/song.mp3"), tree.files())
        assertFalse(tree.exists("FinSync sample music"))
    }

    @Test fun somethingTheUserPutInTheSampleFolderIsKept() = runTest {
        val tree = FakeDocumentTree()
        sync(tree)
        tree.put("FinSync sample music/my notes.txt", "mine".encodeToByteArray())
        removeSamples(SafFileSystem(tree), folder)
        assertEquals(listOf("FinSync sample music/my notes.txt"), tree.files())
    }
}
