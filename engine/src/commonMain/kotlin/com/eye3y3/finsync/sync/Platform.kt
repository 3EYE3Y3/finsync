package com.eye3y3.finsync.sync

/** Free bytes on the volume holding [path] (an existing folder), or null if unknown. */
expect fun freeBytes(path: String): Long?
