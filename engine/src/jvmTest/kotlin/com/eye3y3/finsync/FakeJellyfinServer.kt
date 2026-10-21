package com.eye3y3.finsync

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class FakeTrack(
    val id: String, val path: String, val data: ByteArray, val albumId: String = "al1", val artist: String = "Art",
    val etag: String = "e1", val lyrics: Boolean = false, val art: Boolean = false,
)

/** An in-memory Jellyfin on a real local port (Ktor CIO) that speaks the endpoints FinSync uses. Nothing touches the disk. */
class FakeJellyfinServer(var tracks: List<FakeTrack> = emptyList(), var playlists: Map<String, List<String>> = emptyMap()) : AutoCloseable {
    val token = "secret-token"
    var canDownload = true
    var quickConnectEnabled = true
    /** The Quick Connect code is approved once it has been polled this many times. */
    var approveAfterPolls = 2
    /** The first Quick Connect code is forgotten (404) on this poll, as Jellyfin does after a few minutes. */
    var expireFirstCodeOnPoll: Int? = null
    private val initiations = AtomicInteger(0)
    /** How many upcoming downloads are cut off half way through. */
    val cutNext = AtomicInteger(0)
    /** If set, every download sends only this many bytes (with an honest Content-Length). */
    @Volatile var shortBody: Int? = null
    val ranges = CopyOnWriteArrayList<String?>()
    val authHeaders = CopyOnWriteArrayList<String>()
    private val polls = AtomicInteger(0)

    private val server: EmbeddedServer<*, *> = embeddedServer(CIO, port = 0) {
        routing {
            get("/System/Info/Public") { json(call, """{"Id":"srv1","ServerName":"Fake","Version":"10.11.0"}""") }
            get("/QuickConnect/Enabled") { call.respondText(quickConnectEnabled.toString(), ContentType.Application.Json) }
            post("/QuickConnect/Initiate") {
                val n = initiations.incrementAndGet()
                json(call, """{"Secret":"sec$n","Code":"12345$n","Authenticated":false}""")
            }
            get("/QuickConnect/Connect") {
                val poll = polls.incrementAndGet()
                if (call.request.queryParameters["secret"] == "sec1" && expireFirstCodeOnPoll?.let { poll >= it } == true) {
                    call.respond(HttpStatusCode.NotFound); return@get
                }
                json(call, """{"Authenticated":${poll > approveAfterPolls}}""")
            }
            post("/Users/AuthenticateWithQuickConnect") {
                json(call, """{"AccessToken":"$token","User":{"Id":"u1","Name":"alex"}}""")
            }
            post("/Users/AuthenticateByName") { json(call, """{"AccessToken":"$token","User":{"Id":"u1","Name":"alex"}}""") }
            get("/Users/Me") {
                authed(call) { json(call, """{"Id":"u1","Name":"alex","Policy":{"EnableContentDownloading":$canDownload,"IsAdministrator":false}}""") }
            }
            get("/UserViews") {
                authed(call) { json(call, """{"Items":[{"Id":"lib1","Name":"Films","CollectionType":"movies"},{"Id":"lib2","Name":"Music","CollectionType":"music"}]}""") }
            }
            get("/Library/VirtualFolders") { call.respond(HttpStatusCode.Forbidden) }
            get("/Items") {
                authed(call) {
                    val q = call.request.queryParameters
                    if (q["IncludeItemTypes"] == "Playlist") {
                        json(call, """{"Items":[${playlists.keys.joinToString(",") { """{"Id":"pl-$it","Name":"$it"}""" }}],"TotalRecordCount":${playlists.size}}""")
                    } else {
                        val start = q["StartIndex"]!!.toInt()
                        val limit = q["Limit"]!!.toInt()
                        val page = tracks.drop(start).take(limit)
                        json(call, """{"Items":[${page.joinToString(",") { itemJson(it) }}],"TotalRecordCount":${tracks.size}}""")
                    }
                }
            }
            get("/Playlists/{id}/Items") {
                authed(call) {
                    val name = call.parameters["id"]!!.removePrefix("pl-")
                    json(call, """{"Items":[${playlists[name].orEmpty().joinToString(",") { """{"Id":"$it"}""" }}]}""")
                }
            }
            get("/Items/{id}/Images/Primary") { authed(call) { call.respondBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2), ContentType.Image.JPEG) } }
            get("/Audio/{id}/Lyrics") { authed(call) { json(call, """{"Lyrics":[{"Text":"first line","Start":0},{"Text":"second","Start":65000000}]}""") } }
            get("/Items/{id}/Download") { authed(call) { download(call) } }
        }
    }.also { it.start(wait = false) }

    val port: Int = runBlocking { server.engine.resolvedConnectors().first().port }
    val url get() = "http://127.0.0.1:$port"

    override fun close() = server.stop(0, 0)

    private suspend fun json(call: ApplicationCall, body: String) = call.respondText(body, ContentType.Application.Json)

    private suspend fun authed(call: ApplicationCall, block: suspend () -> Unit) {
        val auth = call.request.headers[HttpHeaders.Authorization].orEmpty()
        authHeaders += auth
        if ("Token=\"$token\"" !in auth) call.respond(HttpStatusCode.Unauthorized) else block()
    }

    private suspend fun download(call: ApplicationCall) {
        if (!canDownload) return call.respond(HttpStatusCode.Forbidden)
        val t = tracks.firstOrNull { it.id == call.parameters["id"] } ?: return call.respond(HttpStatusCode.NotFound)
        val range = call.request.headers[HttpHeaders.Range]
        ranges += range
        val data = shortBody?.let { t.data.copyOf(it) } ?: t.data
        val from = range?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
        val slice = data.copyOfRange(from, data.size)
        val cut = cutNext.getAndUpdate { if (it > 0) it - 1 else 0 } > 0
        if (from > 0) call.response.header(HttpHeaders.ContentRange, "bytes $from-${data.size - 1}/${data.size}")
        call.respondBytesWriter(ContentType.Audio.Any, if (from > 0) HttpStatusCode.PartialContent else HttpStatusCode.OK, slice.size.toLong()) {
            if (cut) {
                writeFully(slice, 0, slice.size / 2)
                flush()
                kotlinx.coroutines.delay(300) // let the client read the half before the cut (flaky under load otherwise)
                throw java.io.IOException("connection cut for the test")
            }
            writeFully(slice, 0, slice.size)
        }
    }

    private fun itemJson(t: FakeTrack) =
        """{"Id":"${t.id}","Name":"${t.id}","Album":"Alb","AlbumId":"${t.albumId}","AlbumArtist":"${t.artist}","Artists":["${t.artist}"],"IndexNumber":1,
        "Path":"/media/Music/${t.path}","Etag":"${t.etag}","HasLyrics":${t.lyrics},${if (t.art) "\"AlbumPrimaryImageTag\":\"p\"," else ""}
        "RunTimeTicks":2000000000,"MediaSources":[{"Size":${t.data.size},"Container":"flac"}]}"""
}
