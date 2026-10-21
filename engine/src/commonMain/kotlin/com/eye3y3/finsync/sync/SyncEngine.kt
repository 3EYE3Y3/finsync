package com.eye3y3.finsync.sync

import com.eye3y3.finsync.download.Downloader
import com.eye3y3.finsync.extras.Sidecars
import com.eye3y3.finsync.jellyfin.FinSyncError
import com.eye3y3.finsync.jellyfin.JellyfinClient
import com.eye3y3.finsync.manifest.Manifest
import com.eye3y3.finsync.manifest.ManifestEntry
import com.eye3y3.finsync.manifest.ManifestStore
import com.eye3y3.finsync.manifest.wallClockMillis
import com.eye3y3.finsync.plan.MirrorPlanner
import com.eye3y3.finsync.plan.PlanOptions
import com.eye3y3.finsync.plan.SyncPlan
import com.eye3y3.finsync.playlist.Playlists
import com.eye3y3.finsync.plan.Download
import com.eye3y3.finsync.plan.Tally
import com.eye3y3.finsync.model.RemoteTrack
import com.eye3y3.finsync.reuse.ByteReader
import com.eye3y3.finsync.reuse.CardFile
import com.eye3y3.finsync.reuse.CardFiles
import com.eye3y3.finsync.reuse.CardMatcher
import com.eye3y3.finsync.reuse.CardReport
import com.eye3y3.finsync.reuse.FileByteReader
import com.eye3y3.finsync.reuse.Integrity
import com.eye3y3.finsync.reuse.Leftover
import com.eye3y3.finsync.reuse.Reuse
import com.eye3y3.finsync.reuse.ServerByteReader
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.buffer
import okio.use
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okio.FileSystem
import okio.Path

data class SyncOptions(
    val plan: PlanOptions = PlanOptions(),
    /** How many files download at once. */
    val concurrency: Int = 2,
    /** Which music library to use when the server has several. */
    val libraryName: String? = null,
    /**
     * A folder holding music copied to the card some other way (it may contain the destination).
     * Its audio files are matched with the library by content: matches are moved into the mirror
     * instead of downloaded. Null: don't look.
     */
    val reuseFrom: Path? = null,
)

/** Receives progress in plain words. */
fun interface SyncListener {
    /** Progress in plain words for the user (callers may show only some of them). */
    fun report(message: String)

    /** Details for a log: phases starting and finishing, per-file timings. */
    fun log(message: String) {}
}

data class SyncResult(
    val downloaded: Int, val adopted: Int, val moved: Int, val deleted: Int,
    val art: Int, val lyrics: Int, val playlists: Int,
    /** Files that could not be fetched, each with the reason. */
    val failures: List<String>,
    /** Tracks taken from music already on the card. */
    val reused: Int = 0,
) {
    val ok get() = failures.isEmpty()
}

/** Plans and carries out a mirror of one Jellyfin music library into [dest]. */
class SyncEngine(
    private val client: JellyfinClient,
    private val fs: FileSystem,
    private val dest: Path,
    private val options: SyncOptions = SyncOptions(),
    private val freeSpace: (Path) -> Long? = { freeBytes(it.toString()) },
    private val retryBackoffMs: Long = 500,
) {
    private val store = ManifestStore(fs, dest)

    /**
     * Asks the server what it has, looks at the destination (and music already on the card, see
     * [SyncOptions.reuseFrom]) and works out what to do. Changes nothing.
     */
    suspend fun plan(listener: SyncListener = SyncListener {}): SyncPlan {
        val user = client.currentUser()
        if (!user.canDownload) throw FinSyncError.DownloadNotAllowed()
        val info = client.serverInfo()
        val manifest = store.load() ?: Manifest()
        if (manifest.serverId.isNotEmpty() && manifest.serverId != info.id) throw FinSyncError.WrongTarget("server")
        val libs = client.musicLibraries(user.id)
        val library = when {
            libs.isEmpty() -> throw FinSyncError.Problem("This account has no music library on the server.")
            options.libraryName != null -> libs.firstOrNull { it.name.equals(options.libraryName, true) }
                ?: throw FinSyncError.Problem("There is no music library called \"${options.libraryName}\". Choices: ${libs.joinToString { it.name }}.")
            libs.size == 1 -> libs.single()
            else -> libs.firstOrNull { it.id == manifest.libraryId }
                ?: throw FinSyncError.Problem("The server has several music libraries (${libs.joinToString { it.name }}). Choose one with --library.")
        }
        if (manifest.libraryId.isNotEmpty() && manifest.libraryId != library.id) throw FinSyncError.WrongTarget("library")
        val asked = wallClockMillis()
        listener.log("Plan: asking the server for the library")
        val tracks = client.tracks(user.id, library.id)
        val playlists = client.playlists(user.id)
        listener.log("Plan: ${tracks.size} tracks (${gb(tracks.sumOf { it.size })}), ${playlists.size} playlists, in ${secs(wallClockMillis() - asked)}")
        val folders = client.libraryFolders().orEmpty()
        val plan = MirrorPlanner.plan(tracks, playlists, manifest, ManifestStore.scanDisk(fs, dest), options.plan.copy(libraryFolders = folders.ifEmpty { options.plan.libraryFolders }))
            .copy(serverId = info.id, userId = user.id, libraryId = library.id)
        val root = options.reuseFrom ?: return plan
        return withReuse(plan, tracks, root, listener)
    }

    private suspend fun withReuse(plan: SyncPlan, tracks: List<RemoteTrack>, root: Path, say: SyncListener): SyncPlan {
        val listed = wallClockMillis()
        say.log("Card check: listing the card folder")
        val files = cardFiles(root)
        say.log("Card check: ${files.size} audio files (${gb(files.sumOf { it.size })}) found in ${secs(wallClockMillis() - listed)}")
        if (files.isEmpty()) return plan
        val wanted = plan.downloads.filter { !it.replace }.associate { it.track.id to it.path }
        val matcher = CardMatcher(object : CardFiles {
            override suspend fun <T> read(path: String, block: suspend (ByteReader) -> T): T =
                fs.openReadOnly(ManifestStore.resolve(root, path)).use { h -> block(FileByteReader(h)) }
        }) { t -> ServerByteReader(client, t.id, t.size) }
        val started = wallClockMillis()
        var lastReport = 0L
        var fileStarted = started
        val report = matcher.match(files, tracks, wanted, progress = { done, total ->
            val now = wallClockMillis()
            if (now - lastReport >= 1000 || done == total) {
                lastReport = now
                say.report("Looking at the music already on the card: $done of $total files${timeLeft(now - started, done.toLong(), total.toLong())}")
            }
        }, onFile = { f, t ->
            val now = wallClockMillis()
            say.log("Card check: ${f.path} (${mb(f.size)}): ${t?.let { "same audio as \"${it.name}\"" } ?: "no match"}, ${now - fileStarted} ms")
            fileStarted = now
        })
        say.log("Card check finished in ${secs(wallClockMillis() - started)}: ${report.reuses.size} to reuse, ${report.leftovers.size} spare copies, ${report.notInLibrary.size} not in the library")
        val reused = report.reuses.map { it.track.id }.toSet()
        val downloads = plan.downloads.filter { it.track.id !in reused || it.replace }
        val reuseBytes = report.reuses.sumOf { it.track.size }
        // A rebuilt (not in-place) file needs room for one copy at a time; the old one goes right after.
        val headroom = report.reuses.filter { !it.inPlace }.maxOfOrNull { it.track.size } ?: 0
        val t = plan.totals
        val totals = t.copy(
            download = Tally(downloads.size, downloads.sumOf { it.track.size }),
            reuse = Tally(report.reuses.size, reuseBytes),
            bytesNeeded = (t.bytesNeeded - reuseBytes + headroom).coerceAtLeast(0),
        )
        return plan.copy(downloads = downloads, totals = totals, card = report)
    }

    /** Audio files under [root], except FinSync's own folder and system/hidden folders. */
    private fun cardFiles(root: Path): List<CardFile> {
        if (!fs.exists(root)) return emptyList()
        val out = ArrayList<CardFile>()
        val destRel = if (dest.toString().startsWith(root.toString())) dest.relativeTo(root).toString().replace('\\', '/') else null
        val skip = setOf("Android", "LOST.DIR", "System Volume Information", "\$RECYCLE.BIN")
        fun walk(dir: Path) {
            for (p in fs.listOrNull(dir).orEmpty()) {
                val rel = p.relativeTo(root).toString().replace('\\', '/')
                val meta = fs.metadataOrNull(p) ?: continue
                if (meta.isDirectory) {
                    if (p.name.startsWith(".") || p.name in skip || rel == destRel) continue
                    walk(p)
                } else if (meta.isRegularFile && p.name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS) {
                    out += CardFile(rel, meta.size ?: 0L)
                }
            }
        }
        walk(root)
        return out.sortedBy { it.path }
    }

    /** Carries out [plan]. Refuses a plan whose deletion brake tripped unless it was allowed. */
    suspend fun execute(plan: SyncPlan, listener: SyncListener = SyncListener {}): SyncResult {
        if (!plan.executable) throw FinSyncError.DeletionBrake(plan.brakeReason ?: "too many deletions")
        fs.createDirectories(dest)
        val need = plan.totals.bytesNeeded
        if (need > 0) {
            val free = freeSpace(dest)
            val required = need + need / 100 + MARGIN_BYTES
            if (free != null && free < required) throw FinSyncError.NotEnoughSpace(required, free)
        }
        return store.withLock {
            coroutineScope {
                val beat = launch {
                    while (true) {
                        delay(ManifestStore.HEARTBEAT_MS)
                        store.heartbeat()
                    }
                }
                try {
                    run(plan, listener)
                } finally {
                    beat.cancel()
                }
            }
        }
    }

    private suspend fun run(plan: SyncPlan, say: SyncListener): SyncResult {
        val m = store.load() ?: Manifest()
        m.serverId = plan.serverId; m.userId = plan.userId; m.libraryId = plan.libraryId
        val lock = Mutex()
        val failures = ArrayList<String>()
        suspend fun commit(change: Manifest.() -> Unit) = lock.withLock { m.change(); store.save(m) }
        suspend fun attempt(path: String, work: suspend () -> Boolean): Boolean = try {
            work()
        } catch (e: FinSyncError) {
            if (e is FinSyncError.SignedOut || e is FinSyncError.DownloadNotAllowed) throw e
            lock.withLock { failures += "$path: ${e.message}" }
            false
        }

        if (plan.adopts.isNotEmpty()) {
            say.report("Recording ${plan.adopts.size} files that are already there.")
            commit { plan.adopts.forEach { files[it.path] = ManifestEntry(it.track.id, Manifest.KIND_TRACK, it.track.size, it.track.changeKey, adopted = true) } }
        }

        for (mv in plan.moves) {
            val from = ManifestStore.resolve(dest, mv.from)
            val to = ManifestStore.resolve(dest, mv.to)
            if (!fs.exists(from)) continue
            if (fs.exists(to) && !sameFile(from, to)) { failures += "${mv.to}: ${notOurs()}"; continue }
            say.report("Renaming ${mv.from} to ${mv.to}")
            to.parent?.let { fs.createDirectories(it) }
            fs.atomicMove(from, to)
            commit { files.remove(mv.from)?.let { files[mv.to] = it } }
            pruneEmpty(from.parent)
        }

        val retry = ArrayList<Download>()
        val damaged = ArrayList<CardFile>()
        var reused = 0
        val root = options.reuseFrom
        if (root != null && plan.card.reuses.isNotEmpty()) {
            val n = plan.card.reuses.size
            val totalBytes = plan.card.reuses.sumOf { it.source.size }
            var doneBytes = 0L
            var lastReport = 0L
            val started = wallClockMillis()
            say.report("Reusing tracks already on the card: 0 of $n (0 of ${gb(totalBytes)})")
            say.log("Reuse: $n tracks, ${gb(totalBytes)}; each is read in full and checked before it is moved")
            for ((i, x) in plan.card.reuses.withIndex()) {
                currentCoroutineContext().ensureActive()
                val t0 = wallClockMillis()
                val outcome = try {
                    reuse(x, root, m, ::commit)
                } catch (e: FinSyncError) {
                    if (e is FinSyncError.SignedOut) throw e
                    ReuseOutcome.FAILED
                }
                if (outcome == ReuseOutcome.DONE) reused++ else retry += Download(x.track, x.target, replace = false)
                if (outcome == ReuseOutcome.DAMAGED) damaged += x.source
                val now = wallClockMillis()
                doneBytes += x.source.size
                val ms = (now - t0).coerceAtLeast(1)
                say.log("Reuse: ${x.source.path} -> ${x.target}: ${outcome.name.lowercase()}${if (outcome == ReuseOutcome.DONE) (if (x.inPlace) ", moved" else ", rebuilt") else ""}, " +
                    "${mb(x.source.size)} in ${secs(ms)} (${mb(x.source.size * 1000 / ms)}/s)")
                if (now - lastReport >= 1000 || i + 1 == n) {
                    lastReport = now
                    say.report("Reusing tracks already on the card: ${i + 1} of $n (${gb(doneBytes)} of ${gb(totalBytes)})${timeLeft(now - started, doneBytes, totalBytes)}")
                }
            }
            say.log("Reuse finished in ${secs(wallClockMillis() - started)}: $reused reused, ${retry.size} to download instead (${damaged.size} damaged)")
        }
        if (damaged.isNotEmpty()) say.report("${damaged.size} files on the card are damaged; fresh copies are downloaded instead and the damaged ones left where they are.")
        if (root != null) saveCardReport(plan.card, damaged)

        var done = 0
        val downloadsStarted = wallClockMillis()
        say.log("Downloads: ${plan.downloads.size + retry.size} files")
        val slots = Semaphore(options.concurrency.coerceAtLeast(1))
        val downloader = Downloader(client, fs, store.partialDir, backoffMs = retryBackoffMs)
        val fetched = coroutineScope {
            (plan.downloads + retry).map { d ->
                async {
                    slots.withPermit {
                        attempt(d.path) {
                            if (!ownsOrFree(m, d.path)) throw FinSyncError.Problem(notOurs())
                            downloader.fetch(d.track.id, d.track.size, ManifestStore.resolve(dest, d.path), d.path)
                            commit {
                                files.entries.removeAll { it.value.kind == Manifest.KIND_TRACK && it.value.itemId == d.track.id && it.key != d.path }
                                files[d.path] = ManifestEntry(d.track.id, Manifest.KIND_TRACK, d.track.size, d.track.changeKey)
                            }
                            lock.withLock { done++ }
                            say.report("Downloaded $done of ${plan.downloads.size + retry.size}: ${d.path}")
                            true
                        }
                    }
                }
            }.awaitAll().count { it }
        }

        say.log("Downloads finished in ${secs(wallClockMillis() - downloadsStarted)}: $fetched fetched, ${failures.size} failed")
        var art = 0
        for (a in plan.art) if (attempt(a.path) {
                val bytes = client.albumArt(a.albumId) ?: return@attempt false
                writeFile(m, a.path, bytes, Manifest.KIND_ART, a.albumId, "", ::commit)
            }
        ) art++
        say.report("Cover pictures: $art")

        var lyrics = 0
        for (l in plan.lyrics) if (attempt(l.path) {
                val lines = client.lyrics(l.trackId)?.takeIf { it.isNotEmpty() } ?: return@attempt false
                writeFile(m, l.path, Sidecars.toLrc(lines).encodeToByteArray(), Manifest.KIND_LYRICS, l.trackId, "", ::commit)
            }
        ) lyrics++
        say.report("Lyrics files: $lyrics")

        for (p in plan.playlists) attempt(p.path) {
            writeFile(m, p.path, p.content.encodeToByteArray(), Manifest.KIND_PLAYLIST, p.playlistId, Playlists.fingerprint(p.content), ::commit)
        }
        say.report("Playlists: ${plan.playlists.size}")

        // Deletes come last, and only for files the manifest lists.
        var deleted = 0
        for (d in plan.deletes) {
            if (m.files[d.path] == null) continue
            val file = ManifestStore.resolve(dest, d.path)
            if (fs.exists(file)) fs.delete(file)
            commit { files.remove(d.path) }
            pruneEmpty(file.parent)
            deleted++
        }
        if (deleted > 0) say.report("Removed $deleted files that are no longer on the server.")
        return SyncResult(fetched, plan.adopts.size, plan.moves.size, deleted, art, lyrics, plan.playlists.size, failures, reused)
    }

    /**
     * Turns card file [x] into the library's file at its target: moved, then the server's tags written
     * over the old ones where they take the same room; otherwise rebuilt from the server's tags and the
     * card's audio, after which the old file is removed. Either way the result is the server's file
     * byte for byte, which is checked. The card file is first read in full and checked for damage
     * ([Integrity]), which sampled comparisons can't see. When it can't be reused the track is downloaded.
     */
    private suspend fun reuse(x: Reuse, root: Path, m: Manifest, commit: suspend (Manifest.() -> Unit) -> Unit): ReuseOutcome {
        val src = ManifestStore.resolve(root, x.source.path)
        val dst = ManifestStore.resolve(dest, x.target)
        if (fs.metadataOrNull(src)?.size != x.source.size) return ReuseOutcome.FAILED
        if (!ownsOrFree(m, x.target)) return ReuseOutcome.FAILED
        if (!fs.openReadOnly(src).use { Integrity.check(FileByteReader(it), x.card) }) return ReuseOutcome.DAMAGED
        val server = ServerByteReader(client, x.track.id, x.track.size)
        val header = server.read(0, x.server.headerLength.toInt())
        val trailer = server.read(x.server.payloadEnd, x.server.trailerLength.toInt())
        if (header.size.toLong() != x.server.headerLength || trailer.size.toLong() != x.server.trailerLength) return ReuseOutcome.FAILED
        dst.parent?.let { fs.createDirectories(it) }
        if (x.inPlace) {
            fs.atomicMove(src, dst)
            fs.openReadWrite(dst).use { h ->
                h.write(0, header, 0, header.size)
                h.write(x.server.payloadEnd, trailer, 0, trailer.size)
                h.flush()
            }
        } else {
            fs.createDirectories(store.partialDir)
            val part = store.partialDir / "${x.track.id}.reuse"
            fs.openReadOnly(src).use { input ->
                fs.sink(part).buffer().use { out ->
                    out.write(header)
                    val buf = ByteArray(1 shl 20)
                    var pos = x.card.payloadStart
                    while (pos < x.card.payloadEnd) {
                        val n = input.read(pos, buf, 0, minOf(buf.size.toLong(), x.card.payloadEnd - pos).toInt())
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        pos += n
                    }
                    out.write(trailer)
                }
            }
            if (fs.metadataOrNull(part)?.size != x.track.size) { fs.delete(part, mustExist = false); return ReuseOutcome.FAILED }
            fs.atomicMove(part, dst)
        }
        val ok = fs.metadataOrNull(dst)?.size == x.track.size && fs.openReadOnly(dst).use { h ->
            val local = FileByteReader(h)
            local.read(0, minOf(header.size, 4096)).contentEquals(header.copyOf(minOf(header.size, 4096))) &&
                CardMatcher.samePayload(server, x.server, local, x.server)
        }
        if (!ok) {
            // The audio was checked when planning, so this is a write problem: throw the result away and download.
            fs.delete(dst, mustExist = false)
            return ReuseOutcome.FAILED
        }
        if (!x.inPlace) fs.delete(src, mustExist = false)
        commit { files[x.target] = ManifestEntry(x.track.id, Manifest.KIND_TRACK, x.track.size, x.track.changeKey) }
        pruneEmptyUnder(root, src.parent)
        return ReuseOutcome.DONE
    }

    private enum class ReuseOutcome { DONE, DAMAGED, FAILED }

    /** Writes `.finsync/card.json`: the card's spare copies and the music that isn't in the library. */
    private fun saveCardReport(r: CardReport, damaged: List<CardFile>) {
        fs.createDirectories(store.stateDir)
        val tmp = store.stateDir / "card.json.tmp"
        fs.write(tmp) { writeUtf8(CARD_JSON.encodeToString(CardReportFile.serializer(), CardReportFile(r.leftovers, r.notInLibrary, damaged))) }
        fs.atomicMove(tmp, store.stateDir / "card.json")
    }

    /** What the last sync found on the card (see [saveCardReport]), or null before the first sync with a card folder. */
    fun cardReport(): CardReportFile? {
        val f = store.stateDir / "card.json"
        if (!fs.exists(f)) return null
        return try { CARD_JSON.decodeFromString(CardReportFile.serializer(), fs.read(f) { readUtf8() }) } catch (e: Exception) { null }
    }

    /**
     * Removes the spare copies listed by the last sync (only after the user confirmed), each only if it is
     * still there with the same size, and folders left empty. Returns (files, bytes) removed.
     */
    fun removeLeftovers(): Pair<Int, Long> {
        val root = options.reuseFrom ?: return 0 to 0L
        val report = cardReport() ?: return 0 to 0L
        var n = 0
        var bytes = 0L
        val kept = ArrayList<Leftover>()
        store.withLock {
            val m = store.load() ?: Manifest()
            // Only tracks the mirror really holds now: a spare copy is never the last copy on the card.
            val held = m.tracks().filter { (path, e) -> fs.exists(ManifestStore.resolve(dest, path)) && fs.metadataOrNull(ManifestStore.resolve(dest, path))?.size == e.size }
                .values.map { it.itemId }.toSet()
            for (l in report.leftovers) {
                val p = ManifestStore.resolve(root, l.source.path)
                if (l.trackId in held && fs.metadataOrNull(p)?.size == l.source.size) {
                    fs.delete(p)
                    n++; bytes += l.source.size
                    pruneEmptyUnder(root, p.parent)
                } else if (fs.exists(p)) kept += l
            }
            saveCardReport(CardReport(emptyList(), kept, report.notInLibrary), report.damaged)
        }
        return n to bytes
    }

    /** Removes [start] and its parents while they hold nothing at all, never going above [root]. */
    private fun pruneEmptyUnder(root: Path, start: Path?) {
        var dir = start
        while (dir != null && dir != root && dir.toString().startsWith(root.toString()) && fs.exists(dir) && fs.list(dir).isEmpty()) {
            fs.delete(dir)
            dir = dir.parent
        }
    }

    private suspend fun writeFile(
        m: Manifest, path: String, bytes: ByteArray, kind: String, itemId: String, changeKey: String,
        commit: suspend (Manifest.() -> Unit) -> Unit,
    ): Boolean {
        val target = ManifestStore.resolve(dest, path)
        if (fs.exists(target) && path !in m.files) throw FinSyncError.Problem(notOurs())
        fs.createDirectories(store.partialDir)
        target.parent?.let { fs.createDirectories(it) }
        val tmp = store.partialDir / "write-${path.hashCode()}.tmp"
        fs.write(tmp) { write(bytes) }
        fs.atomicMove(tmp, target)
        commit { files[path] = ManifestEntry(itemId, kind, bytes.size.toLong(), changeKey) }
        return true
    }

    /** A file appeared at [path] after planning and it isn't FinSync's: leave it alone. */
    private fun ownsOrFree(m: Manifest, path: String) = path in m.files || !fs.exists(ManifestStore.resolve(dest, path))

    /** True when [a] and [b] name the same file (a case-only rename on a case-insensitive card). */
    private fun sameFile(a: Path, b: Path) = a.toString().equals(b.toString(), ignoreCase = true)

    private fun notOurs() = "a file FinSync didn't put there is in the way, so it was left alone"

    /** Removes [start] and its parents while they are empty, never going above the destination. */
    private fun pruneEmpty(start: Path?) {
        var dir = start
        while (dir != null && dir != dest && dir.toString().startsWith(dest.toString()) && fs.exists(dir) && fs.list(dir).isEmpty()) {
            fs.delete(dir)
            dir = dir.parent
        }
    }

    private companion object {
        const val MARGIN_BYTES = 200_000_000L
    }
}

/** `.finsync/card.json`: what the last sync found on the card outside the mirror. */
@Serializable
data class CardReportFile(
    val leftovers: List<Leftover> = emptyList(),
    val notInLibrary: List<CardFile> = emptyList(),
    /** Card copies of library tracks whose audio is damaged: left where they are, fresh copies downloaded. */
    val damaged: List<CardFile> = emptyList(),
)

private val CARD_JSON = Json { prettyPrint = true; ignoreUnknownKeys = true }

/** File extensions looked at when matching music already on the card. */
val AUDIO_EXTENSIONS = setOf("flac", "mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "wav", "aif", "aiff", "wv", "ape", "dsf", "dff", "wma", "alac", "mp4")

private fun gb(n: Long): String = if (n >= 1_000_000_000) "${(n / 100_000_000) / 10.0} GB" else "${n / 1_000_000} MB"
private fun mb(n: Long): String = if (n >= 1_000_000) "${(n / 100_000) / 10.0} MB" else "${n / 1000} KB"
private fun secs(ms: Long): String = if (ms >= 1000) "${(ms / 100) / 10.0} s" else "$ms ms"

/** ", about 12 min left" from how long [done] of [total] took; nothing until there is something to go on. */
private fun timeLeft(elapsedMs: Long, done: Long, total: Long): String {
    if (done <= 0 || done >= total || elapsedMs < 3000) return ""
    val left = elapsedMs * (total - done) / done / 1000
    return when {
        left < 60 -> ", less than a minute left"
        left < 5400 -> ", about ${(left + 30) / 60} min left"
        else -> ", about ${(left + 1800) / 3600} h left"
    }
}
