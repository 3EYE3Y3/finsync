package com.eye3y3.finsync.download

import com.eye3y3.finsync.jellyfin.FinSyncError
import com.eye3y3.finsync.jellyfin.JellyfinClient
import com.eye3y3.finsync.jellyfin.ServerConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloaderTest {
    private val fs = FakeFileSystem()
    private val partial = "/card/.finsync/partial".toPath()
    private val target = "/card/A/Al/01 x.flac".toPath()
    private val data = "fLaC".encodeToByteArray() + ByteArray(5000) { (it % 251).toByte() }

    private fun downloader(handler: (String?) -> Pair<HttpStatusCode, ByteArray>): Downloader {
        val engine = MockEngine { req ->
            val (status, body) = handler(req.headers[HttpHeaders.Range])
            respond(body, status, headersOf(HttpHeaders.ContentLength, body.size.toString()))
        }
        return Downloader(JellyfinClient(HttpClient(engine), ServerConfig("http://x", "d", "n"), "t"), fs, partial, retries = 3, backoffMs = 1)
    }

    private fun fileBytes() = fs.read(target) { readByteArray() }

    @Test fun downloadsAndMovesIntoPlace() = runTest {
        downloader { HttpStatusCode.OK to data }.fetch("id1", data.size.toLong(), target)
        assertContentEquals(data, fileBytes())
        assertFalse(fs.exists(partial / "id1.part"))
    }

    @Test fun resumesFromExistingPartWithRange() = runTest {
        fs.createDirectories(partial)
        fs.write(partial / "id1.part") { write(data, 0, 2000) }
        var range: String? = null
        downloader { range = it; HttpStatusCode.PartialContent to data.copyOfRange(2000, data.size) }.fetch("id1", data.size.toLong(), target)
        assertEquals("bytes=2000-", range)
        assertContentEquals(data, fileBytes())
    }

    @Test fun serverIgnoringRangeRestartsFromScratch() = runTest {
        fs.createDirectories(partial)
        fs.write(partial / "id1.part") { write(data, 0, 2000) }
        downloader { HttpStatusCode.OK to data }.fetch("id1", data.size.toLong(), target)
        assertContentEquals(data, fileBytes())
    }

    @Test fun shortAnswerIsResumedOnRetry() = runTest {
        var calls = 0
        downloader { range ->
            calls++
            if (calls == 1) HttpStatusCode.OK to data.copyOf(3000) else {
                assertEquals("bytes=3000-", range)
                HttpStatusCode.PartialContent to data.copyOfRange(3000, data.size)
            }
        }.fetch("id1", data.size.toLong(), target)
        assertEquals(2, calls)
        assertContentEquals(data, fileBytes())
    }

    @Test fun neverCompleteMeansNothingAtFinalPath() = runTest {
        assertFailsWith<FinSyncError.BadDownload> { downloader { HttpStatusCode.OK to data.copyOf(100) }.fetch("id1", data.size.toLong(), target) }
        assertFalse(fs.exists(target))
        assertFalse(fs.exists("/card/A".toPath()))
    }

    @Test fun tooLongFileIsThrownAwayAndNeverAppears() = runTest {
        assertFailsWith<FinSyncError.BadDownload> { downloader { HttpStatusCode.OK to data + byteArrayOf(1) }.fetch("id1", data.size.toLong(), target) }
        assertFalse(fs.exists(target))
        assertFalse(fs.exists(partial / "id1.part"))
    }

    @Test fun wrongFirstBytesAreRejected() = runTest {
        val bad = ByteArray(data.size) { 7 }
        assertFailsWith<FinSyncError.BadDownload> { downloader { HttpStatusCode.OK to bad }.fetch("id1", bad.size.toLong(), target) }
        assertFalse(fs.exists(target))
        assertFalse(fs.exists(partial / "id1.part"))
    }

    @Test fun unknownContainersPassOnSizeAlone() = runTest {
        val t = "/card/x.wma".toPath()
        downloader { HttpStatusCode.OK to ByteArray(10) }.fetch("w", 10, t)
        assertTrue(fs.exists(t))
    }

    @Test fun mp3NamedM4aIsAccepted() = runTest {
        // Seen in a real library: MP3 audio with an .m4a name.
        val mp3 = "ID3\u0004\u0000".encodeToByteArray() + ByteArray(20) { 1 }
        val t = "/card/GONE (DEMO) A.m4a".toPath()
        downloader { HttpStatusCode.OK to mp3 }.fetch("g", mp3.size.toLong(), t)
        assertTrue(fs.exists(t))
    }

    @Test fun garbageNamedLikeAudioIsStillRejected() = runTest {
        val t = "/card/x.m4a".toPath()
        assertFailsWith<FinSyncError.BadDownload> { downloader { HttpStatusCode.OK to ByteArray(10) { 7 } }.fetch("x", 10, t) }
        assertFalse(fs.exists(t))
    }

    @Test fun detectGoesByContent() {
        fun d(b: ByteArray) = AudioSignature.detect(b)
        assertEquals("flac", d("fLaC".encodeToByteArray()))
        assertEquals("id3", d("ID3\u0004".encodeToByteArray()))
        assertEquals("mpeg", d(byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0)))
        assertEquals("mp4", d("\u0000\u0000\u0000 ftypM4A ".encodeToByteArray()))
        assertEquals("aiff", d("FORM\u0000\u0000\u0000\u0001AIFF".encodeToByteArray()))
        assertEquals("asf", d(byteArrayOf(0x30, 0x26, 0xB2.toByte(), 0x75, 0x8E.toByte())))
        assertEquals(null, d(byteArrayOf(1, 2, 3)))
        // Content decides, whatever the name says.
        assertTrue(AudioSignature.looksRight("m4a", "fLaC".encodeToByteArray()))
        assertTrue(AudioSignature.looksRight("flac", "OggS".encodeToByteArray()))
        assertFalse(AudioSignature.looksRight("aiff", ByteArray(12)))
    }

    @Test fun signaturesPerFormat() {
        fun ok(ext: String, b: ByteArray) = AudioSignature.looksRight(ext, b)
        assertTrue(ok("mp3", "ID3\u0004".encodeToByteArray()))
        assertTrue(ok("mp3", byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0)))
        assertFalse(ok("mp3", byteArrayOf(1, 2, 3)))
        assertTrue(ok("ogg", "OggS".encodeToByteArray()))
        assertTrue(ok("m4a", "\u0000\u0000\u0000 ftypM4A ".encodeToByteArray()))
        assertTrue(ok("wav", "RIFF\u0001\u0000\u0000\u0000WAVE".encodeToByteArray()))
        assertFalse(ok("wav", "RIFF\u0001\u0000\u0000\u0000AVI ".encodeToByteArray()))
        assertTrue(ok("wv", "wvpk".encodeToByteArray()))
        assertTrue(ok("ape", "MAC ".encodeToByteArray()))
        assertTrue(ok("dff", "FRM8".encodeToByteArray()))
        assertTrue(ok("dsf", "DSD ".encodeToByteArray()))
        assertFalse(ok("flac", ByteArray(0)))
    }

    @Test fun signedOutIsNotRetried() = runTest {
        var calls = 0
        val d = downloader { calls++; HttpStatusCode.Unauthorized to ByteArray(0) }
        assertFailsWith<FinSyncError.SignedOut> { d.fetch("id1", 10, target) }
        assertEquals(1, calls)
    }
}
