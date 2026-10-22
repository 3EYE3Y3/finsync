package com.eye3y3.finsync.app

import com.eye3y3.finsync.jellyfin.Library
import com.eye3y3.finsync.sync.Catalog
import com.eye3y3.finsync.sync.CatalogAlbum
import com.eye3y3.finsync.sync.CatalogPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChoiceTest {
    private val gold = CatalogAlbum("gold", "Gold", "ABBA", "lib1", setOf("a1", "a2"), 200)
    private val low = CatalogAlbum("low", "Low", "David Bowie", "lib1", setOf("b1"), 100)
    private val kob = CatalogAlbum("kob", "Kind of Blue", "Miles Davis", "lib2", setOf("m1"), 50)
    private val catalog = Catalog(
        listOf(Library("lib1", "Music"), Library("lib2", "Hi-Res")), listOf(gold, low, kob),
        listOf(CatalogPlaylist("mix", "Mix", setOf("b1", "m1"), 150)),
        mapOf("a1" to 100L, "a2" to 100L, "b1" to 100L, "m1" to 50L),
    )
    private val all = Choice(false, setOf("lib1", "lib2"))

    @Test fun wholeLibraryLessWhatIsUnticked() {
        assertEquals(350L to 350L, all.sizes(catalog))
        val c = all.toggleArtist("David Bowie")
        assertFalse(c.albumOn(low)); assertTrue(c.albumOn(gold))
        assertEquals(setOf("david bowie"), c.excludedArtists)
        assertEquals(350L to 250L, c.sizes(catalog))
        assertEquals(350L to 50L, c.toggleAlbum(gold).sizes(catalog))
        assertEquals(350L to 0L, all.all(listOf("ABBA", "David Bowie", "Miles Davis"), false).sizes(catalog))
        assertEquals(all, c.toggleAlbum(gold).all(listOf("ABBA"), true))
    }

    @Test fun onlyWhatIsPickedCountsPlaylistTracksOnce() {
        val p = all.copy(pickOnly = true)
        assertNotNull(p.problem())
        val c = p.toggleArtist("abba").togglePlaylist("mix")
        assertNull(c.problem())
        assertEquals(350L to 350L, c.sizes(catalog))
        // A playlist's tracks in an unchosen library don't count.
        assertEquals(300L to 300L, c.toggleLibrary("lib2").sizes(catalog))
        assertEquals(350L to 150L, p.toggleAlbum(low).togglePlaylist("mix").sizes(catalog))
    }

    @Test fun noLibraryIsAProblem() {
        assertNotNull(all.toggleLibrary("lib1").toggleLibrary("lib2").problem())
    }
}
