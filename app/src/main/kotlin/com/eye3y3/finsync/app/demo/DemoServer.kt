package com.eye3y3.finsync.app.demo

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * "Try it with sample music": a tiny Jellyfin that lives inside the app (no network), serving the
 * sample library bundled in assets/demo (made by scripts/make-demo-assets.py; all CC0). It answers the
 * same requests the engine sends to a real server, including Range requests, so everything else in the
 * app (folder, check, sync, notification, Stop, playlists) is the real thing.
 *
 * The samples are tiny, so whole-track downloads are paced to [paceMillis] each, one at a time;
 * otherwise the sync is over before its progress, notification and Stop button can be seen.
 */
class DemoServer(
    libraryJson: String,
    private val paceMillis: Long = 0,
    private val file: (String) -> ByteArray,
) {
    private val lib = Json.parseToJsonElement(libraryJson).jsonObject
    private val tracks = lib["tracks"]!!.jsonArray.map { it.jsonObject }
    private val albums = lib["albums"]!!.jsonArray.map { it.jsonObject }
    private val playlists = lib["playlists"]!!.jsonArray.map { it.jsonObject }
    private val bytes = HashMap<String, ByteArray>()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val pace = Mutex()

    private fun data(name: String) = synchronized(bytes) { bytes.getOrPut(name) { file(name) } }
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    fun httpClient(): HttpClient = HttpClient(MockEngine { req ->
        if (paceMillis > 0 && req.url.encodedPath.endsWith("/Download") && req.headers[HttpHeaders.Range] == null)
            pace.withLock { delay(paceMillis) }
        handle(req)
    })

    private fun MockRequestHandleScope.handle(req: HttpRequestData) = req.url.encodedPath.let { path ->
        val q = req.url.parameters
        when {
            path == "/System/Info/Public" -> ok("""{"Id":"$SERVER_ID","ServerName":${Json.encodeToString(lib["server"]!!.jsonPrimitive.content)},"Version":"10.11.0"}""")
            path == "/Users/Me" -> ok("""{"Id":"sample-user","Name":"Sample","Policy":{"EnableContentDownloading":true,"IsAdministrator":false}}""")
            path == "/UserViews" -> ok("""{"Items":[{"Id":"samplelib","Name":"Music","CollectionType":"music"}]}""")
            path == "/Library/VirtualFolders" -> respond("", HttpStatusCode.Forbidden)
            path == "/Items" && q["IncludeItemTypes"] == "Playlist" ->
                ok(buildJsonObject { putJsonArray("Items") { playlists.forEach { p -> add(buildJsonObject { put("Id", p.s("id")); put("Name", p.s("name")) }) } } }.toString())
            path == "/Items" -> {
                val start = q["StartIndex"]?.toIntOrNull() ?: 0
                val limit = q["Limit"]?.toIntOrNull() ?: tracks.size
                ok(buildJsonObject {
                    put("TotalRecordCount", tracks.size)
                    put("Items", JsonArray(tracks.drop(start).take(limit).map(::item)))
                }.toString())
            }
            path.startsWith("/Playlists/") && path.endsWith("/Items") -> {
                val p = playlists.firstOrNull { it.s("id") == path.removePrefix("/Playlists/").removeSuffix("/Items") }
                    ?: return@let respond("", HttpStatusCode.NotFound)
                ok(buildJsonObject { putJsonArray("Items") { p["trackIds"]!!.jsonArray.forEach { add(buildJsonObject { put("Id", it.jsonPrimitive.content) }) } } }.toString())
            }
            path.startsWith("/Items/") && path.endsWith("/Images/Primary") -> {
                val id = path.removePrefix("/Items/").removeSuffix("/Images/Primary")
                if (albums.none { it.s("id") == id }) respond("", HttpStatusCode.NotFound)
                else respond(data("$id.jpg"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg"))
            }
            path.startsWith("/Audio/") && path.endsWith("/Lyrics") -> {
                val t = track(path.removePrefix("/Audio/").removeSuffix("/Lyrics"))
                val lines = t?.get("lyrics")?.jsonArray.orEmpty()
                if (lines.isEmpty()) respond("", HttpStatusCode.NotFound)
                else ok(buildJsonObject {
                    put("Lyrics", buildJsonArray { lines.forEach { l -> add(buildJsonObject { put("Text", l.jsonObject.s("text")); put("Start", l.jsonObject["start"]!!.jsonPrimitive.long) }) } })
                }.toString())
            }
            path.startsWith("/Items/") && path.endsWith("/Download") -> {
                val t = track(path.removePrefix("/Items/").removeSuffix("/Download")) ?: return@let respond("", HttpStatusCode.NotFound)
                val all = data(t.s("file"))
                val range = req.headers[HttpHeaders.Range]?.removePrefix("bytes=")
                if (range == null) respond(all, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "audio/${t.s("container")}"))
                else {
                    val from = range.substringBefore('-').toInt().coerceIn(0, all.size)
                    val to = range.substringAfter('-').toIntOrNull()?.let { minOf(it + 1, all.size) } ?: all.size
                    respond(all.copyOfRange(from, to), HttpStatusCode.PartialContent,
                        headersOf(HttpHeaders.ContentRange, "bytes $from-${to - 1}/${all.size}"))
                }
            }
            else -> respond("", HttpStatusCode.NotFound)
        }
    }

    private fun MockRequestHandleScope.ok(body: String) = respond(body, HttpStatusCode.OK, json)
    private fun track(id: String) = tracks.firstOrNull { it.s("id") == id }

    private fun item(t: JsonObject) = buildJsonObject {
        put("Id", t.s("id")); put("Name", t.s("name")); put("Album", t.s("album")); put("AlbumId", t.s("albumId"))
        put("AlbumArtist", t.s("artist")); putJsonArray("Artists") { add(t["artist"]!!) }
        put("IndexNumber", t["track"]!!.jsonPrimitive.int); put("ProductionYear", t["year"]!!.jsonPrimitive.int)
        put("Path", t.s("path")); put("Etag", "sample-1"); put("RunTimeTicks", t["runTimeTicks"]!!.jsonPrimitive.long)
        put("HasLyrics", t["lyrics"]!!.jsonArray.isNotEmpty()); put("AlbumPrimaryImageTag", "sample")
        putJsonArray("MediaSources") { add(buildJsonObject { put("Size", data(t.s("file")).size); put("Container", t.s("container")) }) }
    }

    companion object {
        const val URL = "http://sample-library.finsync"
        const val TOKEN = "sample"
        const val SERVER_ID = "finsync-sample-library"

        /** About 2 s per track: 13 samples take roughly 30 s. */
        const val PACE_MILLIS = 2_000L

        fun fromAssets(context: Context) = DemoServer(
            context.assets.open("demo/library.json").use { it.readBytes().decodeToString() }, PACE_MILLIS,
        ) { name ->
            context.assets.open("demo/$name").use { it.readBytes() }
        }
    }
}
