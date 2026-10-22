package com.eye3y3.finsync.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eye3y3.finsync.sync.Catalog
import com.eye3y3.finsync.sync.CatalogAlbum

/**
 * The What to sync screen's choices: the libraries, then either the whole library less what is
 * excluded, or only what is picked. Artist names are kept lower-case (the engine ignores case).
 */
internal data class Choice(
    val pickOnly: Boolean,
    val libraries: Set<String>,
    val excludedArtists: Set<String> = emptySet(),
    val excludedAlbums: Set<String> = emptySet(),
    val pickArtists: Set<String> = emptySet(),
    val pickAlbums: Set<String> = emptySet(),
    val pickPlaylists: Set<String> = emptySet(),
) {
    fun artistOn(artist: String) = if (pickOnly) artist.lowercase() in pickArtists else artist.lowercase() !in excludedArtists
    fun albumOn(a: CatalogAlbum) = if (pickOnly) artistOn(a.artist) || a.key in pickAlbums else artistOn(a.artist) && a.key !in excludedAlbums

    fun toggleArtist(artist: String) = artist.lowercase().let {
        if (pickOnly) copy(pickArtists = pickArtists.toggle(it)) else copy(excludedArtists = excludedArtists.toggle(it))
    }

    fun toggleAlbum(a: CatalogAlbum) = if (pickOnly) copy(pickAlbums = pickAlbums.toggle(a.key)) else copy(excludedAlbums = excludedAlbums.toggle(a.key))
    fun togglePlaylist(id: String) = copy(pickPlaylists = pickPlaylists.toggle(id))
    fun toggleLibrary(id: String) = copy(libraries = libraries.toggle(id))

    /** Every artist (and album) on, or every one off. Playlists are left as they are. */
    fun all(artists: Collection<String>, on: Boolean) = when {
        pickOnly && on -> copy(pickArtists = artists.mapTo(HashSet()) { it.lowercase() })
        pickOnly -> copy(pickArtists = emptySet(), pickAlbums = emptySet())
        on -> copy(excludedArtists = emptySet(), excludedAlbums = emptySet())
        else -> copy(excludedArtists = artists.mapTo(HashSet()) { it.lowercase() })
    }

    fun albums(c: Catalog) = c.albums.filter { it.libraryId in libraries }

    /** Bytes of everything in the chosen libraries, and of what this choice syncs. */
    fun sizes(c: Catalog): Pair<Long, Long> {
        val albums = albums(c)
        val total = albums.sumOf { it.bytes }
        if (!pickOnly) return total to albums.filter { albumOn(it) }.sumOf { it.bytes }
        val inLibraries = albums.flatMapTo(HashSet()) { it.trackIds }
        val ids = albums.filter { albumOn(it) }.flatMapTo(HashSet()) { it.trackIds }
        c.playlists.filter { it.id in pickPlaylists }.forEach { p -> p.trackIds.filterTo(ids) { it in inLibraries } }
        return total to ids.sumOf { c.trackBytes[it] ?: 0L }
    }

    /** Why this can't be saved, or null. */
    fun problem(): String? = when {
        libraries.isEmpty() -> "Choose at least one library."
        pickOnly && pickArtists.isEmpty() && pickAlbums.isEmpty() && pickPlaylists.isEmpty() -> "Tick at least one artist, album or playlist."
        else -> null
    }

    fun save(s: Settings, c: Catalog) {
        s.pickOnly = pickOnly
        s.libraryIds = libraries.takeIf { it != c.libraries.mapTo(HashSet()) { l -> l.id } }
        s.excludedArtists = excludedArtists; s.excludedAlbums = excludedAlbums
        s.pickArtists = pickArtists; s.pickAlbums = pickAlbums; s.pickPlaylists = pickPlaylists
    }

    companion object {
        fun from(s: Settings, c: Catalog) = Choice(
            s.pickOnly, s.libraryIds ?: c.libraries.mapTo(HashSet()) { it.id },
            s.excludedArtists, s.excludedAlbums, s.pickArtists, s.pickAlbums, s.pickPlaylists,
        )
    }
}

private fun <T> Set<T>.toggle(x: T) = if (x in this) this - x else this + x

/** One line for the home screen: what the next sync covers. */
internal fun selectionSummary(s: Settings): String {
    val parts = if (s.pickOnly) listOfNotNull(
        s.pickArtists.size.takeIf { it > 0 }?.let { count(it, "artist") },
        s.pickAlbums.size.takeIf { it > 0 }?.let { count(it, "album") },
        s.pickPlaylists.size.takeIf { it > 0 }?.let { count(it, "playlist") },
    ) else listOfNotNull(
        s.excludedArtists.size.takeIf { it > 0 }?.let { count(it, "artist") },
        s.excludedAlbums.size.takeIf { it > 0 }?.let { count(it, "album") },
    )
    val what = when {
        s.pickOnly -> "Only " + parts.joinToString(", ").ifEmpty { "what you choose" }
        parts.isEmpty() -> "Whole library"
        else -> "Whole library except " + parts.joinToString(" and ")
    }
    return what + if (s.libraryIds != null) " (some libraries)" else ""
}

@Composable
internal fun ColumnScope.WhatToSyncScreen(s: Settings, back: () -> Unit, saved: () -> Unit) {
    val ctx = LocalContext.current
    val status by SyncRunner.status.collectAsState()
    var catalog by remember { mutableStateOf<Catalog?>(null) }
    var busyElsewhere by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    var choice by remember { mutableStateOf<Choice?>(null) }
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(setOf<String>()) }
    var problem by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = back)
    LaunchedEffect(attempt) {
        try {
            catalog = SyncRunner.catalog(ctx)?.also { choice = Choice.from(s, it) }
            busyElsewhere = catalog == null && SyncRunner.isRunning
        } catch (e: Exception) { }
    }

    Title("What to sync")
    val c = catalog
    val ch = choice
    if (c == null || ch == null) {
        when {
            busyElsewhere -> Body("A sync is running. You can choose what to sync when it has finished.")
            status.error != null -> {
                ErrorText(status.error)
                Button(onClick = { SyncRunner.clearError(); attempt++ }) { Text("Try again") }
            }
            else -> { Body("Looking at your library…"); Progress(status) }
        }
        OutlinedButton(onClick = back, modifier = Modifier.padding(top = 8.dp)) { Text("Back") }
        return
    }

    val albums = ch.albums(c)
    // Artists by name, ignoring case; each with its albums.
    val artists = albums.groupBy { it.artist.lowercase() }.values
        .map { it.first().artist to it.sortedBy { a -> a.name.lowercase() } }
        .sortedBy { it.first.lowercase() }
    val shown = if (query.isBlank()) artists else artists.filter { (name, list) ->
        name.contains(query.trim(), true) || list.any { it.name.contains(query.trim(), true) }
    }
    val (total, chosen) = ch.sizes(c)

    LazyColumn(Modifier.weight(1f)) {
        item {
            Choose("Whole library", "Everything, except what you untick below.", !ch.pickOnly) { choice = ch.copy(pickOnly = false) }
            Choose("Only what I choose", "Nothing, except the artists, albums and playlists you tick.", ch.pickOnly) { choice = ch.copy(pickOnly = true) }
        }
        if (c.libraries.size > 1) {
            item { Section("Libraries") }
            items(c.libraries, key = { "lib:" + it.id }) { lib ->
                val list = c.albums.filter { it.libraryId == lib.id }
                Tick(lib.name, "${count(list.size, "album")} · ${bytes(list.sumOf { it.bytes })}", lib.id in ch.libraries) { choice = ch.toggleLibrary(lib.id) }
            }
        }
        if (ch.pickOnly && c.playlists.isNotEmpty()) {
            item { Section("Playlists") }
            items(c.playlists, key = { "pl:" + it.id }) { p ->
                Tick(p.name, "${count(p.trackIds.size, "track")} · ${bytes(p.bytes)}", p.id in ch.pickPlaylists) { choice = ch.togglePlaylist(p.id) }
            }
        }
        item {
            Section("Artists")
            OutlinedTextField(query, { query = it }, label = { Text("Search artists and albums") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { choice = ch.all(artists.map { it.first }, true) }) { Text("Tick all") }
                TextButton(onClick = { choice = ch.all(artists.map { it.first }, false) }) { Text("Untick all") }
            }
        }
        items(shown, key = { "ar:" + it.first.lowercase() }) { (name, list) ->
            val expanded = name.lowercase() in open || query.isNotBlank()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(ch.artistOn(name), { choice = ch.toggleArtist(name) })
                Column(Modifier.weight(1f).clickable { open = open.toggle(name.lowercase()) }.padding(vertical = 6.dp)) {
                    Text(name, fontWeight = FontWeight.Bold)
                    Text("${count(list.size, "album")} · ${bytes(list.sumOf { it.bytes })}", fontSize = 13.sp)
                }
                TextButton(onClick = { open = open.toggle(name.lowercase()) }) { Text(if (expanded) "Hide" else "Albums") }
            }
            if (expanded) for (a in list) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 32.dp)) {
                // An album follows its artist; it can be changed on its own only when the artist isn't (un)ticked as a whole.
                val own = if (ch.pickOnly) !ch.artistOn(name) else ch.artistOn(name)
                Checkbox(ch.albumOn(a), { choice = ch.toggleAlbum(a) }, enabled = own)
                Text("${a.name}  (${bytes(a.bytes)})", fontSize = 14.sp)
            }
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text("Selected: ${bytes(chosen)} of ${bytes(total)}", fontWeight = FontWeight.Bold)
    ErrorText(problem)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        Button(onClick = {
            problem = ch.problem()
            if (problem == null) { ch.save(s, c); SyncRunner.forgetPlan(); saved() }
        }) { Text("Save") }
        OutlinedButton(onClick = back) { Text("Cancel") }
    }
}

@Composable
private fun Section(text: String) = Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))

@Composable
private fun Choose(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        RadioButton(selected, onClick)
        Column(Modifier.padding(vertical = 6.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(detail, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Tick(title: String, detail: String, checked: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Checkbox(checked, { onClick() })
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(title)
            Text(detail, fontSize = 13.sp)
        }
    }
}
