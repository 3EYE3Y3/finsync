package com.eye3y3.finsync.sync

import com.eye3y3.finsync.jellyfin.JellyfinClient
import com.eye3y3.finsync.jellyfin.ServerConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

class MockTrack(
    val id: String, val path: String, val albumId: String, val data: ByteArray, val etag: String = "e1", val lyrics: Boolean = false,
    val art: Boolean = false, val artist: String = "Art", val container: String = "flac", val runTimeTicks: Long = 10_000_000,
)

/** A tiny in-memory Jellyfin behind a Ktor MockEngine, for engine tests on a fake file system. */
class MockJellyfin(var tracks: List<MockTrack> = emptyList(), var playlists: Map<String, List<String>> = emptyMap()) {
    var downloadCalls = 0
    /** The Range header of every download request (null: the whole file), with the track id. */
    val downloadRanges = ArrayList<Pair<String, String?>>()
    /** When set, downloading starting from this call number (1-based) is refused as not allowed. */
    var refuseFromCall: Int? = null

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    fun client() = JellyfinClient(
        HttpClient(
            MockEngine { req ->
                val path = req.url.encodedPath
                when {
                    path == "/System/Info/Public" -> respond("""{"Id":"srv1","ServerName":"Mock","Version":"10.11"}""", headers = json)
                    path == "/Users/Me" -> respond("""{"Id":"u1","Name":"alex","Policy":{"EnableContentDownloading":true,"IsAdministrator":false}}""", headers = json)
                    path == "/UserViews" -> respond("""{"Items":[{"Id":"lib1","Name":"Music","CollectionType":"music"}]}""", headers = json)
                    path == "/Library/VirtualFolders" -> respond("", HttpStatusCode.Forbidden)
                    path == "/Items" && req.url.parameters["IncludeItemTypes"] == "Playlist" ->
                        respond("""{"Items":[${playlists.keys.joinToString(",") { """{"Id":"pl-$it","Name":"$it"}""" }}]}""", headers = json)
                    path == "/Items" -> respond(itemsJson(), headers = json)
                    path.startsWith("/Playlists/") -> {
                        val name = path.removePrefix("/Playlists/pl-").removeSuffix("/Items")
                        respond("""{"Items":[${playlists[name].orEmpty().joinToString(",") { """{"Id":"$it"}""" }}]}""", headers = json)
                    }
                    path.endsWith("/Images/Primary") -> respond(byteArrayOf(1, 2, 3))
                    path.endsWith("/Lyrics") -> respond("""{"Lyrics":[{"Text":"la","Start":10000000}]}""", headers = json)
                    path.endsWith("/Download") -> {
                        downloadCalls++
                        if (refuseFromCall?.let { downloadCalls >= it } == true) return@MockEngine respond("", HttpStatusCode.Forbidden)
                        val t = tracks.first { it.id == path.removePrefix("/Items/").removeSuffix("/Download") }
                        downloadRanges += t.id to req.headers[HttpHeaders.Range]
                        val from = req.headers[HttpHeaders.Range]?.substringAfter("bytes=")?.substringBefore('-')?.toInt() ?: 0
                        respond(t.data.copyOfRange(from, t.data.size), if (from > 0) HttpStatusCode.PartialContent else HttpStatusCode.OK)
                    }
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        ),
        ServerConfig("http://mock", "dev", "test"),
        "tok",
    )

    private fun itemsJson(): String {
        val items = tracks.joinToString(",") { t ->
            """{"Id":"${t.id}","Name":"${t.id}","Album":"Alb","AlbumId":"${t.albumId}","AlbumArtist":"${t.artist}","Artists":["${t.artist}"],"Path":"/lib/${t.path}",
            "Etag":"${t.etag}","HasLyrics":${t.lyrics},${if (t.art) "\"AlbumPrimaryImageTag\":\"p\"," else ""}"RunTimeTicks":${t.runTimeTicks},"MediaSources":[{"Size":${t.data.size},"Container":"${t.container}"}]}"""
        }
        return """{"Items":[$items],"TotalRecordCount":${tracks.size}}"""
    }
}

fun flac(n: Int, seed: Int = 0) = "fLaC".encodeToByteArray() + ByteArray(n) { (it + seed).toByte() }
