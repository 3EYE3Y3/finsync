package com.eye3y3.finsync.jellyfin

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JellyfinClientTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val cfg = ServerConfig("http://jf.test:8096/", "dev-1", "Test \"Box\"")

    private fun client(token: String? = null, handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        JellyfinClient(HttpClient(MockEngine { handler(it) }), cfg, token)

    @Test fun authorizationHeaderHasDeviceAndToken() = runTest {
        var seen = ""
        val c = client("abc") { seen = it.headers[HttpHeaders.Authorization]!!; respond("""{"Id":"s1","ServerName":"Home","Version":"10.11"}""", headers = json) }
        val info = c.serverInfo()
        assertEquals(ServerInfo("s1", "Home", "10.11"), info)
        assertEquals("MediaBrowser Client=\"FinSync\", Device=\"Test Box\", DeviceId=\"dev-1\", Version=\"0.1.0\", Token=\"abc\"", seen)
    }

    @Test fun noTokenBeforeSignIn() {
        assertTrue(!client { respond("") }.authorization().contains("Token"))
    }

    @Test fun passwordSignInStoresToken() = runTest {
        val c = client { respond("""{"AccessToken":"T","User":{"Id":"u1","Name":"alex"},"Extra":1}""", headers = json) }
        assertEquals(Session("T", "u1", "alex"), c.signInWithPassword("alex", "pw"))
        assertEquals("T", c.token)
    }

    @Test fun trackParsingAndPaging() = runTest {
        var calls = 0
        val c = client {
            calls++
            val start = it.url.parameters["StartIndex"]!!.toInt()
            assertEquals("Audio", it.url.parameters["IncludeItemTypes"])
            val ids = if (start == 0) listOf("a", "b") else listOf("c")
            val items = ids.joinToString(",") { id ->
                """{"Id":"$id","Name":"T $id","Album":"Al","AlbumId":"al1","AlbumArtist":"AA","Artists":["X","Y"],"IndexNumber":3,"ParentIndexNumber":1,
                "ProductionYear":1999,"Path":"/m/AA/Al/$id.flac","Etag":"e$id","HasLyrics":true,"RunTimeTicks":1800000000,"AlbumPrimaryImageTag":"p",
                "MediaSources":[{"Size":1234,"Container":"flac"}]}"""
            }
            respond("""{"Items":[$items],"TotalRecordCount":3}""", headers = json)
        }
        val tracks = c.tracks("u1", "lib")
        assertEquals(listOf("a", "b", "c"), tracks.map { it.id })
        assertEquals(2, calls)
        val t = tracks[0]
        assertEquals("1234:ea", t.changeKey)
        assertEquals(listOf("X", "Y"), t.artists)
        assertEquals("/m/AA/Al/a.flac", t.serverPath)
        assertTrue(t.hasLyrics && t.albumHasArt)
        assertEquals(1234L, t.size)
    }

    @Test fun changeKeyFallsBackToDateCreated() = runTest {
        val c = client { respond("""{"Items":[{"Id":"a","DateCreated":"2020-01-01","MediaSources":[{"Size":5}]}],"TotalRecordCount":1}""", headers = json) }
        assertEquals("5:2020-01-01", c.tracks("u", "l").single().changeKey)
    }

    @Test fun musicLibrariesOnlyKeepsMusicViews() = runTest {
        val c = client { respond("""{"Items":[{"Id":"1","Name":"Films","CollectionType":"movies"},{"Id":"2","Name":"Music","CollectionType":"music"}]}""", headers = json) }
        assertEquals(listOf(Library("2", "Music")), c.musicLibraries("u"))
    }

    @Test fun libraryFoldersAreNullForNonAdmins() = runTest {
        assertNull(client { respondError(HttpStatusCode.Forbidden) }.libraryFolders())
        val c = client { respond("""[{"Name":"M","CollectionType":"music","Locations":["/media/Music"]},{"Name":"F","CollectionType":"movies","Locations":["/f"]}]""", headers = json) }
        assertEquals(listOf("/media/Music"), c.libraryFolders())
    }

    @Test fun lyricsAndArtMissingGiveNull() = runTest {
        val c = client { respondError(HttpStatusCode.NotFound) }
        assertNull(c.lyrics("x"))
        assertNull(c.albumArt("x"))
    }

    @Test fun httpErrorsBecomePlainMessages() = runTest {
        assertFailsWith<FinSyncError.SignedOut> { client { respondError(HttpStatusCode.Unauthorized) }.currentUser() }
        assertFailsWith<FinSyncError.ServerProblem> { client { respondError(HttpStatusCode.InternalServerError) }.currentUser() }
        assertFailsWith<FinSyncError.DownloadNotAllowed> {
            client { respondError(HttpStatusCode.Forbidden) }.download("x") { _, _, _ -> }
        }
        val down = assertFailsWith<FinSyncError.ServerUnreachable> { client { throw RuntimeException("boom") }.serverInfo() }
        assertTrue("Can't reach the server" in down.message!!)
    }

    @Test fun downloadSendsRangeHeader() = runTest {
        var range: String? = null
        val c = client { range = it.headers[HttpHeaders.Range]; respond("xyz", HttpStatusCode.PartialContent) }
        val status = c.download("id", 100) { s, _, _ -> s }
        assertEquals(206, status)
        assertEquals("bytes=100-", range)
    }

    @Test fun aRangeReadNeverReturnsMoreThanAskedFor() = runTest {
        val file = ByteArray(300_000) { (it * 7).toByte() }
        // Like a server that honours a range's start but sends everything after it.
        val c = client("t") { req ->
            val from = req.headers[HttpHeaders.Range]!!.removePrefix("bytes=").substringBefore('-').toInt()
            respond(file.copyOfRange(from, file.size), HttpStatusCode.PartialContent)
        }
        for (offset in listOf(0L, 1L, 225_235L, 299_000L)) {
            val n = minOf(16_384L, file.size - offset).toInt()
            assertTrue(file.copyOfRange(offset.toInt(), offset.toInt() + n).contentEquals(c.readRange("x", offset, 16_384)), "at $offset")
        }
    }
}
