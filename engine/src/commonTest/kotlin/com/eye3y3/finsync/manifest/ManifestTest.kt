package com.eye3y3.finsync.manifest

import com.eye3y3.finsync.jellyfin.FinSyncError
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManifestTest {
    private val fs = FakeFileSystem()
    private val dest = "/card".toPath()
    private val store = ManifestStore(fs, dest)

    @Test fun missingManifestLoadsAsNull() = assertNull(store.load())

    @Test fun roundTripKeepsEverything() {
        val m = Manifest(serverId = "s", userId = "u", libraryId = "l")
        m.files["A/B/01 x.flac"] = ManifestEntry("i1", "track", 123, "123:e", adopted = true)
        m.files["A/B/cover.jpg"] = ManifestEntry("al1", "art", 9)
        store.save(m)
        val back = assertNotNull(store.load())
        assertEquals("s", back.serverId)
        assertEquals(m.files, back.files)
        assertEquals(1, back.tracks().size)
    }

    @Test fun jsonShapeMatchesTheSpec() {
        val m = Manifest(serverId = "s")
        m.files["a.flac"] = ManifestEntry("i", "track", 1, "1:x")
        store.save(m)
        val text = fs.read(dest / ".finsync/manifest.json") { readUtf8() }
        for (needle in listOf("\"version\": 1", "\"serverId\": \"s\"", "\"files\"", "\"itemId\": \"i\"", "\"kind\": \"track\"", "\"adopted\": false")) assertTrue(needle in text, needle)
    }

    @Test fun saveLeavesNoTemporaryFile() {
        store.save(Manifest())
        assertFalse(fs.exists(dest / ".finsync/manifest.json.tmp"))
        store.save(Manifest(serverId = "again"))
        assertEquals("again", store.load()!!.serverId)
    }

    @Test fun damagedManifestGivesPlainError() {
        fs.createDirectories(dest / ".finsync")
        fs.write(dest / ".finsync/manifest.json") { writeUtf8("{ not json") }
        assertFailsWith<FinSyncError.Problem> { store.load() }
    }

    @Test fun secondLockIsRefusedUntilReleased() {
        store.lock()
        assertFailsWith<FinSyncError.Busy> { store.lock() }
        store.unlock()
        store.withLock { assertTrue(fs.exists(dest / ".finsync/lock")) }
        assertFalse(fs.exists(dest / ".finsync/lock"))
    }

    private var clock = 1_000_000_000L
    private fun store() = ManifestStore(fs, dest) { clock }
    private fun lockText() = fs.read(dest / ".finsync/lock") { readUtf8() }

    @Test fun lockOfAKilledSyncIsTakenOverOnceStale() {
        val killed = store()
        killed.lock() // and never unlocks
        clock += ManifestStore.STALE_MS - 1
        assertFailsWith<FinSyncError.Busy> { store().lock() }
        clock += 2
        val next = store()
        next.lock()
        killed.unlock() // a late unlock from the old owner must not remove the new lock
        assertTrue(fs.exists(dest / ".finsync/lock"))
        next.unlock()
        assertFalse(fs.exists(dest / ".finsync/lock"))
    }

    @Test fun heartbeatKeepsARunningSyncsLock() {
        val running = store()
        running.lock()
        clock += 4 * 60_000L
        running.heartbeat()
        val beatAt = clock
        clock += 4 * 60_000L
        assertFailsWith<FinSyncError.Busy> { store().lock() }
        assertTrue("heartbeat=$beatAt" in lockText())
    }

    @Test fun heartbeatOfATakenOverLockChangesNothing() {
        val old = store()
        old.lock()
        clock += ManifestStore.STALE_MS + 1
        val next = store()
        next.lock()
        val before = lockText()
        clock += 1000
        old.heartbeat()
        assertEquals(before, lockText())
    }

    @Test fun lockWithoutHeartbeatUsesTheFileTime() {
        // The lock file older FinSync versions wrote: no owner, no heartbeat.
        fs.createDirectories(dest / ".finsync")
        fs.write(dest / ".finsync/lock") { writeUtf8("FinSync is syncing this folder\n") }
        val written = fs.metadata(dest / ".finsync/lock").lastModifiedAtMillis!!
        clock = written + 60_000L
        assertFailsWith<FinSyncError.Busy> { store().lock() }
        clock = written + ManifestStore.STALE_MS + 1
        store().lock()
        assertTrue("owner=" in lockText())
    }

    @Test fun busyMessageNoLongerAsksToDeleteTheLock() {
        assertFalse("delete" in FinSyncError.Busy().message!!)
    }

    @Test fun lockIsReleasedWhenTheBlockFails() {
        assertFailsWith<IllegalStateException> { store.withLock { error("boom") } }
        assertFalse(fs.exists(dest / ".finsync/lock"))
    }

    @Test fun diskScanSkipsOwnState() {
        fs.createDirectories(dest / "A")
        fs.write(dest / "A/x.flac") { writeUtf8("12345") }
        fs.write(dest / "top.m3u8") { writeUtf8("1") }
        store.save(Manifest())
        assertEquals(mapOf("A/x.flac" to 5L, "top.m3u8" to 1L), ManifestStore.scanDisk(fs, dest))
        assertEquals(emptyMap(), ManifestStore.scanDisk(fs, "/nowhere".toPath()))
    }
}
