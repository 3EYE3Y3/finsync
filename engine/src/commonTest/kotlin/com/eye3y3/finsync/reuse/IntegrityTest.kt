package com.eye3y3.finsync.reuse

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IntegrityTest {
    private suspend fun ok(b: ByteArray): Boolean {
        val r = BytesReader(b)
        return Integrity.check(r, assertNotNull(AudioLayouts.read(r)))
    }

    private val flac = AudioFixtures.flac(AudioFixtures.flacFrames(200_000, 1), "TITLE=x", padding = 100)
    private val mp3 = AudioFixtures.mp3(AudioFixtures.mp3Frames(100_000, 2), "TIT2 x", ape = true, id3v1 = true)

    @Test fun intactFlacPasses() = runTest { assertTrue(ok(flac)) }

    @Test fun anyChangedByteInFlacAudioIsCaught() = runTest {
        val start = flac.size - 200_000
        for (at in listOf(start + 3, start + 1234, flac.size / 2, flac.size - 1, flac.size - 3)) {
            val bad = flac.copyOf()
            bad[at] = (bad[at] + 1).toByte()
            assertFalse(ok(bad), "changed byte at $at")
        }
    }

    @Test fun zeroedStretchInTheMiddleIsCaught() = runTest {
        val bad = flac.copyOf()
        for (i in 0 until 64) bad[flac.size / 2 + i] = 0
        assertFalse(ok(bad))
    }

    @Test fun truncatedFlacIsCaught() = runTest { assertFalse(ok(flac.copyOf(flac.size - 500))) }

    @Test fun flacTagChangesDontMatter() = runTest {
        assertTrue(ok(AudioFixtures.flac(AudioFixtures.flacFrames(200_000, 1), "TITLE=something else entirely", padding = 5000)))
    }

    @Test fun intactMp3Passes() = runTest { assertTrue(ok(mp3)) }

    @Test fun brokenMp3FrameChainIsCaught() = runTest {
        val start = 10 + "TIT2 x".length
        val bad = mp3.copyOf()
        bad[start + 417 * 50] = 0 // a frame header in the middle
        assertFalse(ok(bad))
    }

    @Test fun mp3FrameLengths() {
        val h = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64)
        assertTrue(Integrity.mp3FrameLength(h) == 417)
        h[2] = 0x92.toByte() // padding bit
        assertTrue(Integrity.mp3FrameLength(h) == 418)
        assertTrue(Integrity.mp3FrameLength(byteArrayOf(1, 2, 3, 4)) == null)
    }
}
