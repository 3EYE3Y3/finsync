package com.eye3y3.finsync.app.saf

import com.eye3y3.finsync.manifest.ManifestStore
import com.eye3y3.finsync.jellyfin.FinSyncError
import okio.IOException
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SafFileSystemTest {
    private val tree = FakeDocumentTree()
    private val fs = SafFileSystem(tree)

    @Test fun writesReadsAndListsLikeAFolder() {
        fs.createDirectories("/Jellyfin/Art/Alb".toPath())
        fs.write("/Jellyfin/Art/Alb/01 x.flac".toPath()) { writeUtf8("hello") }
        assertEquals("hello", fs.read("/Jellyfin/Art/Alb/01 x.flac".toPath()) { readUtf8() })
        assertEquals(5L, fs.metadata("/Jellyfin/Art/Alb/01 x.flac".toPath()).size)
        assertEquals(listOf("/Jellyfin/Art/Alb/01 x.flac".toPath()), fs.list("/Jellyfin/Art/Alb".toPath()))
        fs.write("/Jellyfin/Art/Alb/01 x.flac".toPath()) { writeUtf8("hi") } // rewriting cuts the old content
        assertEquals("hi", tree.read("Jellyfin/Art/Alb/01 x.flac")!!.decodeToString())
        assertEquals(2L, fs.metadata("/Jellyfin/Art/Alb/01 x.flac".toPath()).size, "sizes are fresh after writing")
    }

    @Test fun appendsAndWritesAtPositions() {
        fs.write("/a.bin".toPath()) { writeUtf8("abc") }
        fs.appendingSink("/a.bin".toPath()).buffer().use { it.writeUtf8("def") }
        fs.openReadWrite("/a.bin".toPath()).use { it.write(1, "XY".encodeToByteArray(), 0, 2) }
        assertEquals("aXYdef", tree.read("a.bin")!!.decodeToString())
    }

    @Test fun moveAcrossFoldersUsesTheProvidersMove() {
        tree.put("old/song.flac", byteArrayOf(1, 2, 3))
        fs.createDirectories("/Jellyfin/A".toPath())
        fs.atomicMove("/old/song.flac".toPath(), "/Jellyfin/A/01 Song.flac".toPath())
        assertEquals(1, tree.moves)
        assertContentEquals(byteArrayOf(1, 2, 3), tree.read("Jellyfin/A/01 Song.flac"))
        assertFalse(tree.exists("old/song.flac"))
    }

    @Test fun withoutProviderMoveItCopiesThenDeletes() {
        tree.canMove = false
        tree.put("old/song.flac", byteArrayOf(4, 5))
        fs.createDirectories("/Jellyfin".toPath())
        fs.atomicMove("/old/song.flac".toPath(), "/Jellyfin/song.flac".toPath())
        assertContentEquals(byteArrayOf(4, 5), tree.read("Jellyfin/song.flac"))
        assertFalse(tree.exists("old/song.flac"))
    }

    @Test fun moveReplacesTheTargetLikeAtomicMoveShould() {
        tree.put(".finsync/manifest.json", "old".encodeToByteArray())
        tree.put(".finsync/manifest.json.tmp", "new".encodeToByteArray())
        fs.atomicMove("/.finsync/manifest.json.tmp".toPath(), "/.finsync/manifest.json".toPath())
        assertEquals("new", tree.read(".finsync/manifest.json")!!.decodeToString())
        assertEquals(listOf(".finsync/manifest.json"), tree.files())
    }

    @Test fun caseOnlyRenameWorksOnACaseInsensitiveCard() {
        tree.put("A/song.flac", byteArrayOf(1))
        fs.atomicMove("/A/song.flac".toPath(), "/A/Song.flac".toPath())
        assertEquals(listOf("A/Song.flac"), tree.files())
    }

    @Test fun neverWritesUnderANameTheProviderMadeUp() {
        tree.put("A/x.flac", byteArrayOf(1))
        // A folder can't be made where a file is, and the provider's "x (1).flac" trick is refused.
        assertFailsWith<IOException> { fs.createDirectory("/A/x.flac".toPath()) }
        assertEquals(listOf("A/x.flac"), tree.files())
    }

    @Test fun mustCreateRefusesAnExistingFileSoTheSyncLockWorks() {
        val store = ManifestStore(fs, "/Jellyfin".toPath())
        store.lock()
        assertFailsWith<FinSyncError.Busy> { ManifestStore(fs, "/Jellyfin".toPath()).lock() }
        store.unlock()
        assertFalse(tree.exists("Jellyfin/.finsync/lock"))
    }

    @Test fun refusesToDeleteAFolderThatIsntEmpty() {
        tree.put("A/b.flac", byteArrayOf(1))
        assertFailsWith<IOException> { fs.delete("/A".toPath()) }
        fs.delete("/A/b.flac".toPath())
        fs.delete("/A".toPath())
        assertTrue(tree.files().isEmpty())
    }

    @Test fun namesMatchWithoutRegardToCaseWhenThereIsNoExactMatch() {
        tree.put("Music/Song.FLAC", byteArrayOf(9))
        assertEquals(1L, fs.metadataOrNull("/music/song.flac".toPath())?.size)
        assertNull(fs.metadataOrNull("/Music/other.flac".toPath()))
    }
}
