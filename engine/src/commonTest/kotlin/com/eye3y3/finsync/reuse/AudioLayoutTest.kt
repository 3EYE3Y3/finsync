package com.eye3y3.finsync.reuse

import com.eye3y3.finsync.reuse.AudioFixtures.audio
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioLayoutTest {
    private suspend fun layout(b: ByteArray) = assertNotNull(AudioLayouts.read(BytesReader(b)))

    @Test fun flacPayloadStartsAfterTheMetadataBlocks() = runTest {
        val frames = audio(5000, 1)
        val f = AudioFixtures.flac(frames, "ARTIST=Muse", padding = 200)
        val l = layout(f)
        assertEquals("flac", l.format)
        assertEquals((f.size - frames.size).toLong(), l.payloadStart)
        assertEquals(f.size.toLong(), l.payloadEnd)
        assertEquals(10_000L, l.durationMs)
        assertEquals(441_000L, l.totalSamples)
        assertNotNull(l.audioMd5)
    }

    @Test fun retaggedFlacHasTheSamePayload() = runTest {
        val frames = audio(5000, 2)
        val a = layout(AudioFixtures.flac(frames, "ALBUM=Old", padding = 50))
        val b = layout(AudioFixtures.flac(frames, "ALBUM=Something much longer", padding = 4000))
        assertEquals(a.payloadLength, b.payloadLength)
        assertEquals(a.audioMd5, b.audioMd5)
        assertFalse(a.headerLength == b.headerLength)
    }

    @Test fun flacWithoutMd5HasNone() = runTest {
        val f = AudioFixtures.flac(audio(100, 3), "X=1")
        for (i in 0 until 16) f[8 + 18 + i] = 0 // "fLaC" + block header, then STREAMINFO's MD5 at 18
        assertNull(layout(f).audioMd5)
    }

    @Test fun mp3PayloadSkipsId3v2ApeAndId3v1() = runTest {
        val frames = AudioFixtures.mp3Frames(8000, 4)
        val f = AudioFixtures.mp3(frames, "TIT2 hello", extra = 100, ape = true, id3v1 = true)
        val l = layout(f)
        assertEquals("mp3", l.format)
        assertEquals(10L + "TIT2 hello".length + 100, l.payloadStart)
        assertEquals(frames.size.toLong(), l.payloadLength)
        assertEquals(383L * 1152 * 1000 / 44100, l.durationMs)
    }

    @Test fun cbrMp3DurationComesFromTheBitrate() = runTest {
        val frames = audio(16_000, 5)
        byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64).copyInto(frames, 0)
        val l = layout(AudioFixtures.mp3(frames, "x"))
        assertEquals(16_000L * 8 / 128, l.durationMs) // 128 kbps
    }

    @Test fun otherFormatsAreWholeFiles() = runTest {
        val f = AudioFixtures.m4a(1000, 6)
        val l = layout(f)
        assertEquals("mp4", l.format)
        assertFalse(l.separable)
        assertEquals(0L, l.payloadStart)
        assertEquals(f.size.toLong(), l.payloadEnd)
    }

    @Test fun nonAudioIsNull() = runTest {
        assertNull(AudioLayouts.read(BytesReader(ByteArray(500) { 7 })))
        assertNull(AudioLayouts.read(BytesReader(ByteArray(3))))
    }

    @Test fun samplesCoverStartMiddleAndEnd() {
        val o = CardMatcher.sampleOffsets(1_000_000)
        assertEquals(0L, o.first())
        assertEquals(1_000_000L - CardMatcher.SAMPLE, o.last())
        assertTrue(o.size >= 4)
        assertEquals(listOf(0L), CardMatcher.sampleOffsets(100))
    }
}
