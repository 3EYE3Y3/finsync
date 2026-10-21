package com.eye3y3.finsync.app.demo

import com.eye3y3.finsync.manifest.ManifestStore
import okio.FileSystem
import okio.Path

/**
 * Removes what the sample syncs wrote in [dir]: the files its manifest lists, FinSync's state folder,
 * then folders left empty (and [dir] itself if nothing else is in it). Anything else is left alone.
 */
fun removeSamples(fs: FileSystem, dir: Path) {
    val m = ManifestStore(fs, dir).load() ?: return
    m.files.keys.forEach { fs.delete(ManifestStore.resolve(dir, it), mustExist = false) }
    fs.deleteRecursively(dir / ManifestStore.STATE_DIR, mustExist = false)
    fun prune(p: Path) {
        fs.listOrNull(p)?.forEach { c -> if (fs.metadataOrNull(c)?.isDirectory == true) prune(c) }
        if (fs.listOrNull(p)?.isEmpty() == true) fs.delete(p, mustExist = false)
    }
    prune(dir)
}
