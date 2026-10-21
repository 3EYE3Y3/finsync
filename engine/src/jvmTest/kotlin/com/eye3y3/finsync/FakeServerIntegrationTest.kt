package com.eye3y3.finsync

import com.eye3y3.finsync.download.Downloader
import com.eye3y3.finsync.jellyfin.FinSyncError
import com.eye3y3.finsync.jellyfin.JellyfinClient
import com.eye3y3.finsync.jellyfin.ServerConfig
import com.eye3y3.finsync.manifest.ManifestStore
import com.eye3y3.finsync.plan.PlanOptions
import com.eye3y3.finsync.sync.SyncEngine
import com.eye3y3.finsync.sync.SyncOptions
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakeServerIntegrationTest {
    private val http = HttpClient(CIO)
    private val servers = ArrayList<FakeJellyfinServer>()
    private val fs = FakeFileSystem()
    private val dest = "/card".toPath()

    @AfterTest fun tearDown() {
        http.close()
        servers.forEach { it.close() }
    }

    private fun server(vararg tracks: FakeTrack, playlists: Map<String, List<String>> = emptyMap()) =
        FakeJellyfinServer(tracks.toList(), playlists).also { servers += it }

    private fun client(s: FakeJellyfinServer, token: String? = s.token) = JellyfinClient(http, ServerConfig(s.url, "dev-1", "Test box"), token)

    private fun payload(n: Int, seed: Int = 0) = "fLaC".encodeToByteArray() + ByteArray(n) { (it * 7 + seed).toByte() }

    private fun engine(s: FakeJellyfinServer) =
        SyncEngine(client(s), fs, dest, SyncOptions(PlanOptions(root = "/media/Music"), concurrency = 2), { null }, retryBackoffMs = 5)

    @Test fun quickConnectPollsUntilApproved() = runBlocking<Unit> {
        val s = server()
        val c = client(s, null)
        var shown = ""
        val session = c.signInWithQuickConnect(timeoutMs = 5000, pollMs = 10) { shown = it }
        assertEquals("123451", shown)
        assertEquals("secret-token", session.token)
        assertEquals("u1", session.userId)
        assertEquals("alex", c.currentUser().name) // the new token is used from now on
    }

    @Test fun expiredQuickConnectCodeIsReplaced() = runBlocking<Unit> {
        val s = server().also { it.expireFirstCodeOnPoll = 1; it.approveAfterPolls = 3 }
        val shown = ArrayList<String>()
        val session = client(s, null).signInWithQuickConnect(timeoutMs = 5000, pollMs = 10) { shown += it }
        assertEquals(listOf("123451", "123452"), shown)
        assertEquals("secret-token", session.token)
    }

    @Test fun quickConnectTimesOutAndReportsWhenOff() = runBlocking<Unit> {
        val s = server().also { it.approveAfterPolls = Int.MAX_VALUE }
        assertFailsWith<FinSyncError.QuickConnectTimeout> { client(s, null).signInWithQuickConnect(50, 10) {} }
        s.quickConnectEnabled = false
        assertFailsWith<FinSyncError.QuickConnectOff> { client(s, null).signInWithQuickConnect(50, 10) {} }
    }

    @Test fun passwordSignIn() = runBlocking<Unit> {
        val s = server()
        assertEquals("secret-token", client(s, null).signInWithPassword("alex", "pw").token)
    }

    @Test fun requestsCarryTheAuthorizationHeader() = runBlocking<Unit> {
        val s = server()
        client(s).currentUser()
        val h = s.authHeaders.single()
        assertTrue(h.startsWith("MediaBrowser Client=\"FinSync\", Device=\"Test box\", DeviceId=\"dev-1\", Version=\"0.1.0\""))
        assertTrue(h.endsWith("Token=\"secret-token\""))
    }

    @Test fun wrongTokenMeansSignedOut() = runBlocking<Unit> {
        val s = server()
        assertFailsWith<FinSyncError.SignedOut> { client(s, "nope").currentUser() }
    }

    @Test fun unreachableServerGivesPlainMessage() = runBlocking<Unit> {
        val s = server()
        val c = client(s)
        s.close()
        val e = assertFailsWith<FinSyncError.ServerUnreachable> { c.serverInfo() }
        assertTrue("Can't reach the server" in e.message!!)
    }

    @Test fun pagesThroughAllTracksAndFindsMusicLibraryOnly() = runBlocking<Unit> {
        val s = server(*Array(1100) { FakeTrack("t$it", "A/$it.flac", ByteArray(4)) })
        val c = client(s)
        assertEquals(listOf("lib2"), c.musicLibraries("u1").map { it.id })
        assertEquals(1100, c.tracks("u1", "lib2").size)
        assertNull(c.libraryFolders())
    }

    private fun assertNull(x: Any?) = kotlin.test.assertNull(x)

    @Test fun cutDownloadResumesWithRangeAndIsByteIdentical() = runBlocking<Unit> {
        val data = payload(6000)
        val s = server(FakeTrack("t1", "A/1.flac", data))
        val target = dest / "A/1.flac"
        val partial = dest / ".finsync/partial"
        s.cutNext.set(1)
        // First attempt gets cut and there are no retries: the half file stays in partial/, nothing at the final path.
        assertFailsWith<Exception> { Downloader(client(s), fs, partial, retries = 0, backoffMs = 1).fetch("t1", data.size.toLong(), target) }
        assertFalse(fs.exists(target))
        val half = fs.metadata(partial / "t1.part").size!!
        assertTrue(half in 1 until data.size)
        // A fresh run asks for the rest with Range and the result is identical.
        Downloader(client(s), fs, partial, retries = 0, backoffMs = 1).fetch("t1", data.size.toLong(), target)
        assertEquals("bytes=$half-", s.ranges.last())
        assertContentEquals(data, fs.read(target) { readByteArray() })
        assertFalse(fs.exists(partial / "t1.part"))
    }

    @Test fun retriesInsideOneRunResumeAutomatically() = runBlocking<Unit> {
        val data = payload(5000, 3)
        val s = server(FakeTrack("t1", "A/1.flac", data))
        s.cutNext.set(2)
        Downloader(client(s), fs, dest / ".finsync/partial", retries = 3, backoffMs = 1).fetch("t1", data.size.toLong(), dest / "A/1.flac")
        assertContentEquals(data, fs.read(dest / "A/1.flac") { readByteArray() })
        assertEquals(3, s.ranges.size)
        assertEquals(null, s.ranges[0])
        assertTrue(s.ranges.drop(1).any { it != null && it.startsWith("bytes=") }, s.ranges.toString())
    }

    @Test fun truncatedFileNeverAppearsAtFinalPath() = runBlocking<Unit> {
        val data = payload(5000)
        val s = server(FakeTrack("t1", "A/1.flac", data))
        s.shortBody = 2000 // the server keeps sending too little, though it says so honestly
        val target = dest / "A/1.flac"
        assertFailsWith<FinSyncError.BadDownload> {
            Downloader(client(s), fs, dest / ".finsync/partial", retries = 2, backoffMs = 1).fetch("t1", data.size.toLong(), target)
        }
        assertFalse(fs.exists(target))
        assertFalse(fs.exists(dest / "A"))
    }

    @Test fun noDownloadPermissionIsReportedPlainly() = runBlocking<Unit> {
        val s = server(FakeTrack("t1", "A/1.flac", payload(100)))
        s.canDownload = false
        val e = assertFailsWith<FinSyncError.DownloadNotAllowed> { engine(s).plan() }
        assertTrue("not allowed to download" in e.message!!)
        assertFailsWith<FinSyncError.DownloadNotAllowed> {
            Downloader(client(s), fs, dest / ".finsync/partial", retries = 0).fetch("t1", 104, dest / "x.flac")
        }
    }

    @Test fun fullSyncOverHttpEvenWhenEveryFirstDownloadIsCut() = runBlocking<Unit> {
        val a = payload(3000, 1)
        val b = payload(4000, 2)
        val s = server(
            FakeTrack("t1", "Art/Alb/01 One.flac", a, lyrics = true, art = true),
            FakeTrack("t2", "Art/Alb/02 Two.flac", b, art = true),
            playlists = mapOf("Mix" to listOf("t2", "t1")),
        )
        s.cutNext.set(2)
        val e = engine(s)
        val plan = e.plan()
        assertEquals(2, plan.downloads.size)
        assertFalse(fs.exists(dest))
        val r = e.execute(plan)
        assertTrue(r.ok, r.failures.toString())
        assertContentEquals(a, fs.read(dest / "Art/Alb/01 One.flac") { readByteArray() })
        assertContentEquals(b, fs.read(dest / "Art/Alb/02 Two.flac") { readByteArray() })
        assertEquals("[00:00.00]first line\n[00:06.50]second\n", fs.read(dest / "Art/Alb/01 One.lrc") { readUtf8() })
        assertEquals(4, fs.read(dest / "Art/Alb/cover.jpg") { readByteArray() }.size)
        assertTrue("Art/Alb/01 One.flac" in fs.read(dest / "Mix.m3u8") { readUtf8() })
        assertTrue(engine(s).plan().isEmpty)
        assertEquals(setOf("track", "art", "lyrics", "playlist"), ManifestStore(fs, dest).load()!!.files.values.map { it.kind }.toSet())
    }
}
