package com.eye3y3.finsync.extras

import com.eye3y3.finsync.jellyfin.LyricLine
import com.eye3y3.finsync.naming.Naming

/** Paths and formats of the small files that sit next to tracks: cover pictures and lyrics. */
object Sidecars {
    const val COVER_NAME = "cover.jpg"

    /** `cover.jpg` in the same folder as the track. */
    fun coverPath(trackPath: String): String {
        val dir = trackPath.substringBeforeLast('/', "")
        return if (dir.isEmpty()) COVER_NAME else "$dir/$COVER_NAME"
    }

    /** The track's path with its extension swapped for `.lrc`. */
    fun lyricsPath(trackPath: String): String {
        val dir = trackPath.substringBeforeLast('/', "")
        val name = Naming.splitExtension(trackPath.substringAfterLast('/')).first + ".lrc"
        return if (dir.isEmpty()) name else "$dir/$name"
    }

    /** Lyrics as LRC text: timed lines get `[mm:ss.xx]`, untimed lines are written as they are. */
    fun toLrc(lines: List<LyricLine>): String = lines.joinToString("") { line ->
        val text = line.text.replace('\n', ' ').replace('\r', ' ')
        val ms = line.startTicks?.div(10_000)
        val stamp = if (ms == null) "" else "[${two(ms / 60_000)}:${two(ms / 1000 % 60)}.${two(ms % 1000 / 10)}]"
        "$stamp$text\n"
    }

    private fun two(n: Long) = n.toString().padStart(2, '0')
}
