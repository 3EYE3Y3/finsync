package com.eye3y3.finsync.jellyfin

import com.eye3y3.finsync.ENGINE_VERSION
import com.eye3y3.finsync.model.RemotePlaylist
import com.eye3y3.finsync.model.RemoteTrack
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.discard
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

/** Where and as whom this device talks to the server. */
data class ServerConfig(val baseUrl: String, val deviceId: String, val deviceName: String)

data class ServerInfo(val id: String, val name: String, val version: String)
data class QuickConnectCode(val secret: String, val code: String)
data class Session(val token: String, val userId: String, val userName: String)
data class UserInfo(val id: String, val name: String, val canDownload: Boolean, val isAdmin: Boolean)
data class Library(val id: String, val name: String)
data class LyricLine(val text: String, val startTicks: Long?)

/** Talks to one Jellyfin server. The [http] client (and its engine) is supplied by the caller. */
class JellyfinClient(private val http: HttpClient, val config: ServerConfig, var token: String? = null) {
    private val base = config.baseUrl.trimEnd('/')

    suspend fun serverInfo(): ServerInfo {
        val o = getJson("/System/Info/Public").obj()
        return ServerInfo(o.str("Id") ?: "", o.str("ServerName") ?: "Jellyfin", o.str("Version") ?: "")
    }

    suspend fun quickConnectEnabled(): Boolean =
        send(HttpMethod.Get, "/QuickConnect/Enabled").bodyAsText().trim().equals("true", ignoreCase = true)

    suspend fun quickConnectInitiate(): QuickConnectCode {
        val o = json.parseToJsonElement(send(HttpMethod.Post, "/QuickConnect/Initiate").bodyAsText()).obj()
        return QuickConnectCode(o.str("Secret") ?: throw FinSyncError.QuickConnectOff(), o.str("Code") ?: "")
    }

    suspend fun quickConnectApproved(secret: String): Boolean =
        getJson("/QuickConnect/Connect") { parameter("secret", secret) }.obj().bool("Authenticated") == true

    suspend fun finishQuickConnect(secret: String): Session =
        signedIn("/Users/AuthenticateWithQuickConnect", buildJsonObject { put("Secret", secret) })

    /**
     * Signs in with Quick Connect: [onCode] shows the code, then the server is polled until it is approved or [timeoutMs] passes.
     * Jellyfin forgets a code after a few minutes; a fresh one is then requested and shown again.
     */
    suspend fun signInWithQuickConnect(timeoutMs: Long, pollMs: Long = 2000, onCode: (String) -> Unit): Session {
        if (!quickConnectEnabled()) throw FinSyncError.QuickConnectOff()
        var start = quickConnectInitiate()
        onCode(start.code)
        var waited = 0L
        while (!(try { quickConnectApproved(start.secret) } catch (e: FinSyncError.NotFound) {
                start = quickConnectInitiate()
                onCode(start.code)
                false
            })) {
            if (waited >= timeoutMs) throw FinSyncError.QuickConnectTimeout()
            currentCoroutineContext().ensureActive()
            delay(pollMs)
            waited += pollMs
        }
        return finishQuickConnect(start.secret)
    }

    /** Ends this sign-in on the server, so the token stops working. */
    suspend fun signOut() {
        send(HttpMethod.Post, "/Sessions/Logout")
    }

    suspend fun signInWithPassword(user: String, password: String): Session =
        signedIn("/Users/AuthenticateByName", buildJsonObject { put("Username", user); put("Pw", password) })

    suspend fun currentUser(): UserInfo {
        val o = getJson("/Users/Me").obj()
        val policy = o.objOrNull("Policy")
        return UserInfo(o.str("Id") ?: "", o.str("Name") ?: "", policy?.bool("EnableContentDownloading") ?: false, policy?.bool("IsAdministrator") ?: false)
    }

    suspend fun musicLibraries(userId: String): List<Library> {
        val items = getJson("/UserViews") { parameter("userId", userId) }.obj().arr("Items")
        return items.map { it.obj() }.filter { it.str("CollectionType") == "music" }
            .map { Library(it.str("Id") ?: "", it.str("Name") ?: "Music") }
    }

    /** Library folder locations on the server's disk, or null when this account may not ask (non-admins). */
    suspend fun libraryFolders(): List<String>? = try {
        getJson("/Library/VirtualFolders").arr().map { it.obj() }
            .filter { it.str("CollectionType") == "music" }
            .flatMap { f -> f.arr("Locations").mapNotNull { (it as? JsonPrimitive)?.contentOrNull } }
    } catch (e: FinSyncError.SignedOut) {
        null
    } catch (e: FinSyncError.NotFound) {
        null
    }

    suspend fun tracks(userId: String, libraryId: String): List<RemoteTrack> {
        val all = ArrayList<RemoteTrack>()
        var total = Int.MAX_VALUE
        while (all.size < total) {
            val page = getJson("/Items") {
                parameter("userId", userId)
                parameter("ParentId", libraryId)
                parameter("IncludeItemTypes", "Audio")
                parameter("Recursive", "true")
                parameter("Fields", "Path,MediaSources,Etag,DateCreated")
                parameter("StartIndex", all.size)
                parameter("Limit", PAGE_SIZE)
            }.obj()
            total = page.int("TotalRecordCount") ?: 0
            val items = page.arr("Items")
            if (items.isEmpty()) break
            items.mapTo(all) { toTrack(it.obj()) }
        }
        return all
    }

    suspend fun playlists(userId: String): List<RemotePlaylist> {
        val lists = getJson("/Items") {
            parameter("userId", userId); parameter("IncludeItemTypes", "Playlist"); parameter("Recursive", "true")
        }.obj().arr("Items").map { it.obj() }
        return lists.map { p ->
            val id = p.str("Id") ?: ""
            val entries = getJson("/Playlists/$id/Items") { parameter("userId", userId) }.obj().arr("Items")
            RemotePlaylist(id, p.str("Name") ?: "Playlist", entries.mapNotNull { it.obj().str("Id") })
        }
    }

    /** The album's cover picture as JPEG bytes, or null if the server has none. */
    suspend fun albumArt(albumId: String): ByteArray? = try {
        send(HttpMethod.Get, "/Items/$albumId/Images/Primary") { parameter("format", "Jpg") }.bodyAsBytes()
    } catch (e: FinSyncError.NotFound) {
        null
    }

    suspend fun lyrics(itemId: String): List<LyricLine>? = try {
        getJson("/Audio/$itemId/Lyrics").obj().arr("Lyrics").map { it.obj() }
            .map { LyricLine(it.str("Text") ?: "", it.long("Start")) }
    } catch (e: FinSyncError.NotFound) {
        null
    }

    /**
     * Opens the original file of [itemId], asking for bytes from [from] on. [block] gets the HTTP status
     * (206 when the server honoured the range), the declared length of this response and the body.
     */
    suspend fun <T> download(itemId: String, from: Long = 0, block: suspend (status: Int, length: Long?, body: ByteReadChannel) -> T): T {
        val statement = http.prepareGet("$base/Items/$itemId/Download") {
            applyAuth()
            if (from > 0) header(HttpHeaders.Range, "bytes=$from-")
        }
        try {
            return statement.execute { resp ->
                if (!resp.status.isSuccess()) throw errorFor(resp.status, "that file", download = true)
                block(resp.status.value, resp.headers[HttpHeaders.ContentLength]?.toLongOrNull(), resp.bodyAsChannel())
            }
        } catch (e: FinSyncError) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw FinSyncError.ServerUnreachable(base)
        }
    }

    /**
     * Bytes [offset, offset + length) of [itemId]'s original file (fewer only at the end of the file).
     * Used to compare files on the card with the server's without downloading them whole.
     */
    suspend fun readRange(itemId: String, offset: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val statement = http.prepareGet("$base/Items/$itemId/Download") {
            applyAuth()
            header(HttpHeaders.Range, "bytes=$offset-${offset + length - 1}")
        }
        try {
            return statement.execute { resp ->
                if (!resp.status.isSuccess()) throw errorFor(resp.status, "that file", download = true)
                val body = resp.bodyAsChannel()
                // A server that ignores Range sends the whole file: skip to the part we asked for.
                if (resp.status.value != 206 && offset > 0) body.discard(offset)
                // Never more than asked for: a server may send past the range's end, and readRemaining(max) can
                // return more than max when that much is already buffered (the reuse check compares exact samples).
                body.readRemaining(length.toLong()).readByteArray().let { if (it.size > length) it.copyOf(length) else it }
            }
        } catch (e: FinSyncError) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw FinSyncError.ServerUnreachable(base)
        }
    }

    private suspend fun signedIn(path: String, body: JsonObject): Session {
        val o = json.parseToJsonElement(
            send(HttpMethod.Post, path) { contentType(ContentType.Application.Json); setBody(body.toString()) }.bodyAsText(),
        ).obj()
        val token = o.str("AccessToken") ?: throw FinSyncError.SignInFailed()
        val user = o.objOrNull("User")
        this.token = token
        return Session(token, user?.str("Id") ?: "", user?.str("Name") ?: "")
    }

    private suspend fun getJson(path: String, extra: HttpRequestBuilder.() -> Unit = {}): JsonElement =
        json.parseToJsonElement(send(HttpMethod.Get, path, extra).bodyAsText())

    private suspend fun send(method: HttpMethod, path: String, extra: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
        val resp = try {
            http.request("$base$path") { this.method = method; applyAuth(); extra() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw FinSyncError.ServerUnreachable(base)
        }
        if (!resp.status.isSuccess()) throw errorFor(resp.status, "that")
        return resp
    }

    private fun errorFor(status: HttpStatusCode, what: String, download: Boolean = false): FinSyncError = when (status.value) {
        401 -> FinSyncError.SignedOut()
        403 -> if (download) FinSyncError.DownloadNotAllowed() else FinSyncError.SignedOut()
        404 -> FinSyncError.NotFound(what)
        else -> FinSyncError.ServerProblem(status.value)
    }

    private fun HttpRequestBuilder.applyAuth() = header(HttpHeaders.Authorization, authorization())

    internal fun authorization(): String {
        fun q(s: String) = s.replace("\"", "")
        val head = "MediaBrowser Client=\"FinSync\", Device=\"${q(config.deviceName)}\", DeviceId=\"${q(config.deviceId)}\", Version=\"$ENGINE_VERSION\""
        return token?.let { "$head, Token=\"$it\"" } ?: head
    }

    private fun toTrack(o: JsonObject): RemoteTrack {
        val source = o.arr("MediaSources").firstOrNull()?.obj()
        val size = source?.long("Size") ?: 0L
        val tag = o.str("Etag") ?: o.str("DateCreated") ?: ""
        return RemoteTrack(
            id = o.str("Id") ?: "",
            name = o.str("Name") ?: "",
            album = o.str("Album"),
            albumId = o.str("AlbumId"),
            albumArtist = o.str("AlbumArtist"),
            artists = o.arr("Artists").mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            trackNumber = o.int("IndexNumber"),
            discNumber = o.int("ParentIndexNumber"),
            discCount = null,
            year = o.int("ProductionYear"),
            serverPath = o.str("Path") ?: source?.str("Path"),
            size = size,
            container = source?.str("Container") ?: o.str("Container"),
            changeKey = "$size:$tag",
            runTimeTicks = o.long("RunTimeTicks") ?: 0L,
            hasLyrics = o.bool("HasLyrics") == true,
            albumHasArt = o.str("AlbumPrimaryImageTag") != null,
        )
    }

    private companion object {
        const val PAGE_SIZE = 500
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

private fun JsonElement.obj(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
private fun JsonElement.arr(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
private fun JsonObject.arr(k: String): JsonArray = (this[k] ?: JsonArray(emptyList())).arr()
private fun JsonObject.objOrNull(k: String): JsonObject? = this[k] as? JsonObject
private fun JsonObject.prim(k: String): JsonPrimitive? = this[k] as? JsonPrimitive
private fun JsonObject.str(k: String): String? = prim(k)?.contentOrNull
private fun JsonObject.int(k: String): Int? = prim(k)?.intOrNull
private fun JsonObject.long(k: String): Long? = prim(k)?.longOrNull
private fun JsonObject.bool(k: String): Boolean? = prim(k)?.booleanOrNull
