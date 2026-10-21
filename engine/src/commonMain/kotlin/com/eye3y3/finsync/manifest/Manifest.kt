package com.eye3y3.finsync.manifest

import com.eye3y3.finsync.jellyfin.FinSyncError
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** What FinSync wrote (or adopted) at one path. [kind] is track, art, lyrics or playlist. */
@Serializable
data class ManifestEntry(
    val itemId: String,
    val kind: String,
    val size: Long,
    val changeKey: String = "",
    val adopted: Boolean = false,
)

/** The record of every file FinSync owns in a destination. Only files listed here are ever replaced or deleted. */
@Serializable
class Manifest(
    val version: Int = 1,
    var serverId: String = "",
    var userId: String = "",
    var libraryId: String = "",
    val files: MutableMap<String, ManifestEntry> = linkedMapOf(),
) {
    fun tracks() = files.filterValues { it.kind == KIND_TRACK }

    companion object {
        const val KIND_TRACK = "track"
        const val KIND_ART = "art"
        const val KIND_LYRICS = "lyrics"
        const val KIND_PLAYLIST = "playlist"
    }
}

/**
 * Reads and writes `<dest>/.finsync/manifest.json`, and guards the folder with a lock file.
 * The lock records its owner and a heartbeat that a running sync refreshes ([heartbeat]); a lock whose
 * heartbeat is older than [STALE_MS] was left by a sync that was killed, and is taken over.
 * [now] is the wall clock in milliseconds.
 */
class ManifestStore(
    private val fs: FileSystem,
    val dest: Path,
    private val now: () -> Long = ::wallClockMillis,
) {
    val stateDir: Path = dest / STATE_DIR
    val partialDir: Path = stateDir / "partial"
    private val file = stateDir / "manifest.json"
    private val lockFile = stateDir / "lock"

    fun load(): Manifest? {
        if (!fs.exists(file)) return null
        val text = fs.read(file) { readUtf8() }
        return try {
            Json { ignoreUnknownKeys = true }.decodeFromString(Manifest.serializer(), text)
        } catch (e: Exception) {
            throw FinSyncError.Problem("The record of synced files (.finsync/manifest.json) is damaged. Move it aside and sync again; existing files that match will be adopted.")
        }
    }

    /** Writes to a temporary file first, then renames it, so a crash never leaves half a manifest. */
    fun save(m: Manifest) {
        fs.createDirectories(stateDir)
        val tmp = stateDir / "manifest.json.tmp"
        fs.write(tmp) { writeUtf8(JSON.encodeToString(Manifest.serializer(), m)) }
        fs.atomicMove(tmp, file)
    }

    /** Runs [block] while holding the destination's lock. */
    inline fun <T> withLock(block: () -> T): T {
        lock()
        try {
            return block()
        } finally {
            unlock()
        }
    }

    private val owner = Random.nextLong().toULong().toString(16)

    fun lock() {
        fs.createDirectories(stateDir)
        if (tryCreateLock()) return
        val beat = lastHeartbeat()
        if (beat != null && now() - beat <= STALE_MS) throw FinSyncError.Busy()
        // Left behind by a sync that stopped without unlocking (killed, crashed, battery died).
        fs.delete(lockFile, mustExist = false)
        if (!tryCreateLock() || lockOwner() != owner) throw FinSyncError.Busy()
    }

    /** Tells other syncs this one is still running. Does nothing if the lock isn't ours (any more). */
    fun heartbeat() {
        if (lockOwner() != owner) return
        val tmp = stateDir / "lock.tmp"
        fs.write(tmp) { writeUtf8(lockText()) }
        fs.atomicMove(tmp, lockFile)
    }

    /** Removes the lock, unless another sync has since taken it over. */
    fun unlock() {
        if (lockOwner() == owner) fs.delete(lockFile, mustExist = false)
    }

    private fun lockText() = "FinSync is syncing this folder\nowner=$owner\nheartbeat=${now()}\n"

    private fun tryCreateLock(): Boolean = try {
        fs.write(lockFile, mustCreate = true) { writeUtf8(lockText()) }
        true
    } catch (e: okio.IOException) {
        false
    }

    private fun lockLines(): List<String>? = try {
        fs.read(lockFile) { readUtf8() }.lines()
    } catch (e: okio.IOException) {
        null
    }

    private fun lockOwner(): String? = lockLines()?.firstNotNullOfOrNull { it.removePrefix("owner=").takeIf { o -> o != it } }

    /** The lock's heartbeat; for a lock without one (older FinSync), the time the file was last written. */
    private fun lastHeartbeat(): Long? {
        val lines = lockLines() ?: return null
        lines.firstNotNullOfOrNull { it.removePrefix("heartbeat=").takeIf { h -> h != it }?.toLongOrNull() }?.let { return it }
        return try {
            fs.metadata(lockFile).lastModifiedAtMillis
        } catch (e: okio.IOException) {
            null
        }
    }

    companion object {
        const val STATE_DIR = ".finsync"
        /** A lock not refreshed for this long belongs to a sync that is no longer running. */
        const val STALE_MS = 5 * 60_000L
        /** How often a running sync refreshes its lock. */
        const val HEARTBEAT_MS = 60_000L
        private val JSON = Json { prettyPrint = true; encodeDefaults = true }

        /** Relative path (with "/") of every file under [dest] except FinSync's own state, mapped to its size. */
        fun scanDisk(fs: FileSystem, dest: Path): Map<String, Long> {
            if (!fs.exists(dest)) return emptyMap()
            val out = HashMap<String, Long>()
            for (p in fs.listRecursively(dest)) {
                val rel = p.relativeTo(dest).toString().replace('\\', '/')
                if (rel == STATE_DIR || rel.startsWith("$STATE_DIR/")) continue
                val meta = fs.metadata(p)
                if (meta.isRegularFile) out[rel] = meta.size ?: 0L
            }
            return out
        }

        fun resolve(dest: Path, rel: String): Path {
            val p = rel.toPath(normalize = true)
            require(!p.isAbsolute && p.segments.none { it == ".." }) { "Path outside the FinSync folder: $rel" }
            return dest / p
        }
    }
}

@OptIn(ExperimentalTime::class)
internal fun wallClockMillis(): Long = Clock.System.now().toEpochMilliseconds()
