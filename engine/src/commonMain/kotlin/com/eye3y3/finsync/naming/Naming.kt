package com.eye3y3.finsync.naming

/** What the tidy layout needs to know about a track. */
data class TrackMeta(
    val albumArtist: String?,
    val artist: String?,
    val album: String?,
    val year: Int?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val discCount: Int?,
    val title: String,
    val ext: String,
)

/** The shared path rules from docs/NAMING.md. Names keep their original script. */
object Naming {
    const val MAX_BYTES = 100
    private const val FORBIDDEN = "<>:\"?*|"

    /** Makes one folder name (or a file name without extension) safe, then limits it to [maxBytes] of UTF-8. */
    fun safeComponent(s: String, maxBytes: Int = MAX_BYTES): String {
        val cleaned = clean(s)
        val short = truncateUtf8(cleaned, maxBytes)
        return if (short == cleaned) cleaned else clean(short)
    }

    /** Makes a file name safe. The lower-cased extension is kept whole and counts towards the limit. */
    fun safeFileName(name: String): String {
        val (base, ext) = splitExtension(name)
        if (ext.isEmpty()) return safeComponent(name)
        val suffix = "." + ext.lowercase()
        return safeComponent(base, MAX_BYTES - suffix.length) + suffix
    }

    /** Splits "Song.FLAC" into "Song" and "FLAC". An extension is 1-6 ASCII letters or digits after the last dot. */
    fun splitExtension(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return name to ""
        val ext = name.substring(dot + 1)
        val ok = ext.length in 1..6 && ext.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
        return if (ok) name.substring(0, dot) to ext else name to ""
    }

    /** Number of bytes [s] takes in UTF-8. */
    fun utf8Length(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            n += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> { i++; 4 }
                else -> 3
            }
            i++
        }
        return n
    }

    /** Cuts [s] to at most [maxBytes] of UTF-8 without splitting a code point. */
    fun truncateUtf8(s: String, maxBytes: Int): String {
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val pair = s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()
            val len = if (pair) 2 else 1
            val size = utf8Length(s.substring(i, i + len))
            if (bytes + size > maxBytes) break
            bytes += size
            i += len
        }
        return s.substring(0, i)
    }

    /** Makes every part of a server path safe. Empty, "." and ".." parts are dropped. */
    fun safeRelativePath(path: String): String {
        val parts = path.split('/', '\\').filter { it.isNotEmpty() && it != "." && it != ".." }
        return parts.mapIndexed { i, p -> if (i == parts.lastIndex) safeFileName(p) else safeComponent(p) }.joinToString("/")
    }

    /** The companion's layout: Album Artist/Album (Year)/NN Title.ext, as in docs/NAMING.md. */
    fun tidyPath(m: TrackMeta): String {
        val artist = m.artist?.takeIf { it.isNotBlank() } ?: m.albumArtist?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
        val ext = m.ext.lowercase()
        if (m.album.isNullOrBlank()) {
            return listOf(safeComponent(artist), "Non-Album", safeFileName("${m.title}.$ext")).joinToString("/")
        }
        val albumArtist = m.albumArtist?.takeIf { it.isNotBlank() } ?: artist
        val compilation = albumArtist.equals("Various Artists", ignoreCase = true)
        val folder = m.album + (m.year?.takeIf { it > 0 }?.let { " ($it)" } ?: "")
        val disc = if ((m.discCount ?: 1) > 1) pad2(m.discNumber ?: 1) + "-" else ""
        val number = m.trackNumber?.let { disc + pad2(it) + " " } ?: ""
        val name = number + (if (compilation) "$artist - " else "") + m.title
        return listOf(safeComponent(albumArtist), safeComponent(folder), safeFileName("$name.$ext")).joinToString("/")
    }

    /**
     * Gives every (item id, path) pair its own path, ignoring case. The later item (sorted by id)
     * gets " (2)", " (3)" ... before its extension. Returns item id to final path, in id order.
     */
    fun resolveClashes(items: List<Pair<String, String>>): Map<String, String> {
        val taken = HashSet<String>()
        val out = LinkedHashMap<String, String>()
        for ((id, path) in items.sortedBy { it.first }) {
            var candidate = path
            var n = 2
            while (!taken.add(candidate.lowercase())) candidate = withSuffix(path, " ($n)").also { n++ }
            out[id] = candidate
        }
        return out
    }

    private fun withSuffix(path: String, suffix: String): String {
        val dir = path.substringBeforeLast('/', "")
        val (base, ext) = splitExtension(path.substringAfterLast('/'))
        val dotExt = if (ext.isEmpty()) "" else ".$ext"
        val room = MAX_BYTES - utf8Length(dotExt) - utf8Length(suffix)
        val name = safeComponent(base, room) + suffix + dotExt
        return if (dir.isEmpty()) name else "$dir/$name"
    }

    private fun pad2(n: Int) = n.toString().padStart(2, '0')

    /** Steps 1-7 of the rules (leading whitespace goes before the leading-dot check). */
    private fun clean(s: String): String {
        val sb = StringBuilder()
        for (c in s) when {
            c == '/' || c == '\\' || c in FORBIDDEN -> sb.append('_')
            c.code < 0x20 -> {}
            else -> sb.append(c)
        }
        var r = sb.toString().trimStart()
        if (r.startsWith(".")) r = "_" + r.substring(1)
        r = r.trimEnd { it == '.' || it.isWhitespace() }
        return r.ifEmpty { "_" }
    }
}
