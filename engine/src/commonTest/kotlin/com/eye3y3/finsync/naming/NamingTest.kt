package com.eye3y3.finsync.naming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NamingTest {
    private fun safe(s: String) = Naming.safeComponent(s)

    @Test fun slashesAndBackslashesBecomeUnderscores() {
        assertEquals("AC_DC", safe("AC/DC"))
        assertEquals("a_b", safe("a\\b"))
    }

    @Test fun controlCharactersAreRemoved() = assertEquals("ab", safe("a\u0000b\u001f"))

    @Test fun forbiddenCharactersBecomeUnderscores() {
        assertEquals("What__ Live_", safe("What?: Live*"))
        assertEquals("_______", safe("<>:\"?*|"))
    }

    @Test fun leadingDotIsReplaced() = assertEquals("_hidden", safe(".hidden"))

    @Test fun leadingWhitespaceIsRemoved() = assertEquals("Song", safe("  Song"))

    @Test fun stepsRunInOrderSoDotAfterSpaceSurvives() {
        // Step 4 (leading dot) runs before step 5 (leading whitespace), exactly as written in NAMING.md.
        assertEquals("_hidden", safe(" .hidden"))
    }

    @Test fun trailingDotsAndWhitespaceAreRemoved() {
        assertEquals("Album", safe("Album..."))
        assertEquals("Album", safe("Album . "))
    }

    @Test fun emptyResultBecomesUnderscore() {
        assertEquals("_", safe(""))
        assertEquals("_", safe("..."))
        assertEquals("_", safe("   "))
    }

    @Test fun truncationStopsAt100Bytes() {
        assertEquals(100, safe("a".repeat(150)).length)
        assertEquals("a".repeat(100), safe("a".repeat(100)))
    }

    @Test fun truncationNeverSplitsCyrillic() {
        val out = safe("ж".repeat(60)) // 2 bytes each
        assertEquals("ж".repeat(50), out)
        assertEquals(100, Naming.utf8Length(out))
        assertEquals("ж".repeat(49) + "a", safe("ж".repeat(49) + "a".repeat(40)).substring(0, 50))
    }

    @Test fun truncationNeverSplitsEmoji() {
        val out = safe("a" + "😀".repeat(30)) // 1 + 4 bytes each; 24 emoji = 97 bytes, a 25th would need 101
        assertEquals("a" + "😀".repeat(24), out)
        assertTrue(Naming.utf8Length(out) <= 100)
        assertEquals("😀😀", Naming.truncateUtf8("😀😀😀", 11))
    }

    @Test fun truncationLeavingTrailingSpaceIsCleaned() {
        assertEquals("a".repeat(99), safe("a".repeat(99) + " b"))
    }

    @Test fun extensionIsLowercasedAndKept() {
        assertEquals("Song.flac", Naming.safeFileName("Song.FLAC"))
        assertEquals("Song.flac", Naming.safeFileName("Song..flac"))
    }

    @Test fun longFileNameKeepsExtensionWhole() {
        val out = Naming.safeFileName("x".repeat(150) + ".FLAC")
        assertEquals("x".repeat(95) + ".flac", out)
        assertEquals(100, out.length)
    }

    @Test fun nonExtensionsStayInTheName() {
        assertEquals("Mr. Brightside", Naming.safeFileName("Mr. Brightside"))
        assertEquals("Album", Naming.safeFileName("Album..."))
        assertEquals("_hidden", Naming.safeFileName(".hidden"))
        assertEquals("a.toolongext", Naming.safeFileName("a.toolongext"))
    }

    @Test fun relativePathDropsUnsafeParts() {
        assertEquals("AC_DC/Back In Black/01 Hells Bells.flac", Naming.safeRelativePath("AC_DC\\Back In Black//./../01 Hells Bells.FLAC"))
        assertEquals("A/B.mp3", Naming.safeRelativePath("/A/../B.mp3"))
    }

    @Test fun tidyAlbum() {
        val m = TrackMeta("AC/DC", "AC/DC", "Back in Black", 1980, 1, 1, 1, "Hells Bells", "FLAC")
        assertEquals("AC_DC/Back in Black (1980)/01 Hells Bells.flac", Naming.tidyPath(m))
    }

    @Test fun tidyOmitsUnknownYear() {
        val m = TrackMeta("A", "A", "B", null, 3, 1, 1, "T", "mp3")
        assertEquals("A/B/03 T.mp3", Naming.tidyPath(m))
    }

    @Test fun tidyMultiDisc() {
        val m = TrackMeta("A", "A", "B", 2000, 4, 2, 2, "T", "flac")
        assertEquals("A/B (2000)/02-04 T.flac", Naming.tidyPath(m))
    }

    @Test fun tidyCompilation() {
        val m = TrackMeta("Various Artists", "Daft Punk", "Now 1", 1999, 7, 1, 1, "One More Time", "flac")
        assertEquals("Various Artists/Now 1 (1999)/07 Daft Punk - One More Time.flac", Naming.tidyPath(m))
    }

    @Test fun tidySingleTrack() {
        val m = TrackMeta(null, "Solo", null, null, null, null, null, "Alone?", "ogg")
        assertEquals("Solo/Non-Album/Alone_.ogg", Naming.tidyPath(m))
    }

    @Test fun caseInsensitiveClashGetsSuffixInIdOrder() {
        val out = Naming.resolveClashes(listOf("b" to "ABBA/x.flac", "a" to "Abba/x.flac", "c" to "abba/x.flac", "d" to "ABBA/y.flac"))
        assertEquals("Abba/x.flac", out["a"])
        assertEquals("ABBA/x (2).flac", out["b"])
        assertEquals("abba/x (3).flac", out["c"])
        assertEquals("ABBA/y.flac", out["d"])
    }

    @Test fun clashSuffixStillFitsIn100Bytes() {
        val long = "z".repeat(94) + ".flac"
        val out = Naming.resolveClashes(listOf("1" to "A/$long", "2" to "a/$long"))
        assertEquals(100, out["2"]!!.substringAfter('/').length)
        assertTrue(out["2"]!!.endsWith(" (2).flac"))
    }
}
