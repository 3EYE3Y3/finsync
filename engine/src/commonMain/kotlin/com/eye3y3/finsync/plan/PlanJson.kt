package com.eye3y3.finsync.plan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The plan as readable JSON: totals and the list of paths for each action. */
fun SyncPlan.toJson(): String {
    fun tally(t: Tally) = buildJsonObject { put("files", t.files); put("bytes", t.bytes) }
    fun paths(list: List<String>): JsonElement = JsonArray(list.map(::JsonPrimitive))
    val obj = buildJsonObject {
        put("totals", buildJsonObject {
            put("download", tally(totals.download)); put("adopt", tally(totals.adopt)); put("move", tally(totals.move))
            put("delete", tally(totals.delete)); put("art", tally(totals.art)); put("lyrics", tally(totals.lyrics))
            put("playlists", tally(totals.playlists)); put("conflicts", tally(totals.conflicts)); put("reuse", tally(totals.reuse))
            put("cardLeftovers", card.leftovers.size); put("cardNotInLibrary", card.notInLibrary.size)
            put("bytesNeeded", totals.bytesNeeded); put("namesChanged", totals.namesChanged)
        })
        put("brakeTripped", brakeTripped)
        brakeReason?.let { put("brakeReason", it) }
        put("download", paths(downloads.map { it.path }))
        put("adopt", paths(adopts.map { it.path }))
        put("move", paths(moves.map { "${it.from} -> ${it.to}" }))
        put("delete", paths(deletes.map { it.path }))
        put("art", paths(art.map { it.path }))
        put("lyrics", paths(lyrics.map { it.path }))
        put("playlists", paths(playlists.map { it.path }))
        put("conflicts", paths(conflicts.map { it.path }))
    }
    return Json { prettyPrint = true }.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), obj)
}
