package com.eye3y3.finsync.app

import kotlin.test.Test
import kotlin.test.assertEquals

class WordsTest {
    @Test fun countsAreSingularOnlyForOne() {
        assertEquals("1 playlist", count(1, "playlist"))
        assertEquals("0 playlists", count(0, "playlist"))
        assertEquals("2 extra copies", count(2, "extra copy", "extra copies"))
        assertEquals("1 extra copy", count(1, "extra copy", "extra copies"))
    }
}
