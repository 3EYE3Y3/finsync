package com.eye3y3.finsync.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eye3y3.finsync.app.saf.ContentResolverTree
import com.eye3y3.finsync.createHttpClient
import com.eye3y3.finsync.jellyfin.FinSyncError
import com.eye3y3.finsync.jellyfin.JellyfinClient
import com.eye3y3.finsync.jellyfin.ServerConfig
import com.eye3y3.finsync.plan.SyncPlan
import com.eye3y3.finsync.app.demo.DemoServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF6ED3F5), background = Color(0xFF00283A), surface = Color(0xFF00384C))) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { App() }
            }
        }
    }
}

private enum class Screen { SERVER, FOLDER, CHECK, HOME, SPARES, NOT_IN_LIBRARY, CHOOSE }

@Composable
private fun App() {
    val ctx = LocalContext.current
    val s = remember { Settings(ctx) }
    var chooseFrom by rememberSaveable { mutableStateOf(Screen.HOME) }
    var screen by rememberSaveable {
        mutableStateOf(when { !s.signedIn -> Screen.SERVER; s.treeUri == null -> Screen.FOLDER; !s.setupDone -> Screen.CHECK; else -> Screen.HOME })
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
        when (screen) {
            Screen.SERVER -> ServerScreen(s) { screen = Screen.FOLDER }
            Screen.FOLDER -> FolderScreen(s) { screen = if (s.setupDone) Screen.HOME else Screen.CHECK }
            Screen.CHECK -> CheckScreen(s, choose = { chooseFrom = Screen.CHECK; screen = Screen.CHOOSE }) { screen = Screen.HOME }
            Screen.HOME -> HomeScreen(s, go = { if (it == Screen.CHOOSE) chooseFrom = Screen.HOME; screen = it })
            // After a change, the check screen shows what the new choice means before anything is removed.
            Screen.CHOOSE -> WhatToSyncScreen(s, back = { screen = chooseFrom }) { screen = Screen.CHECK }
            Screen.SPARES -> SparesScreen { screen = Screen.HOME }
            Screen.NOT_IN_LIBRARY -> NotInLibraryScreen { screen = Screen.HOME }
        }
    }
}

@Composable internal fun Title(text: String) = Text(text, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
@Composable internal fun Body(text: String) = Text(text, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
@Composable internal fun ErrorText(text: String?) { if (text != null) Text(text, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }

// --- 1. Server and sign-in ---------------------------------------------------------------------

@Composable
private fun ServerScreen(s: Settings, done: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf(s.server ?: "http://") }
    var usePassword by rememberSaveable { mutableStateOf(false) }
    var user by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Title("Find your server")
        Body("Type the address of your Jellyfin server, as you would in a browser.")
        OutlinedTextField(url, { url = it }, label = { Text("Server address") }, placeholder = { Text("http://192.168.1.10:8096") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        if (plainHttpOutsideHome(url)) Text(
            "This address isn't on your home network and doesn't use https, so others on the network could read your sign-in. Use https://, or a VPN such as Tailscale.",
            color = MaterialTheme.colorScheme.error, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp),
        )
        if (usePassword) {
            OutlinedTextField(user, { user = it }, label = { Text("User name") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        ErrorText(error)
        if (code != null) {
            Card(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Body("On a phone, tablet or computer already signed in to Jellyfin, open Settings → Quick Connect and enter:")
                    Text(code!!, fontSize = 44.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp))
                    Body("Waiting for approval…")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
            Button(enabled = !busy, onClick = {
                error = null
                busy = true
                job = scope.launch {
                    val http = createHttpClient()
                    try {
                        val base = normaliseUrl(url)
                        val client = JellyfinClient(http, ServerConfig(base, s.deviceId, "FinSync on ${Build.MODEL}"))
                        client.serverInfo()
                        val session = if (usePassword) client.signInWithPassword(user.trim(), password)
                        else client.signInWithQuickConnect(10 * 60_000L) { code = it }
                        if (s.server != base) s.clearSelection()
                        s.demo = false; s.usedRealServer = true
                        s.server = base; s.token = session.token; s.userName = session.userName
                        SyncRunner.forgetPlan()
                        done()
                    } catch (e: FinSyncError) {
                        error = e.message
                    } catch (e: IllegalArgumentException) {
                        error = "That doesn't look like a server address."
                    } finally {
                        http.close(); busy = false; code = null
                    }
                }
            }) { Text(if (usePassword) "Sign in" else "Connect") }
            if (busy) OutlinedButton(onClick = { job?.cancel() }) { Text("Cancel") }
            else TextButton(onClick = { usePassword = !usePassword }) { Text(if (usePassword) "Use Quick Connect" else "Use a password instead") }
        }
        if (!s.usedRealServer && !busy) TextButton(onClick = {
            s.clearSelection()
            s.demo = true; s.server = DemoServer.URL; s.token = DemoServer.TOKEN; s.userName = "Sample"
            done()
        }, modifier = Modifier.padding(top = 4.dp)) { Text("No server yet? Try it with sample music", fontSize = 14.sp) }
    }
}

private fun normaliseUrl(raw: String): String {
    var u = raw.trim().trimEnd('/')
    require(u.isNotEmpty() && u != "http:" && u != "https:" && u != "http:/")
    if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
    return u
}

// --- 2. Where the music goes -------------------------------------------------------------------

@Composable
private fun FolderScreen(s: Settings, done: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(s.treeName) }
    var free by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val (n, f) = withContext(Dispatchers.IO) {
                    ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    val tree = ContentResolverTree(ctx, uri)
                    tree.root.name to tree.freeBytes()
                }
                s.treeUri = uri.toString(); s.treeName = n
                SyncRunner.forgetPlan()
                name = n; free = f; error = null
            } catch (e: Exception) {
                error = "FinSync can't use that folder: ${e.message}"
            }
        }
    }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Title("Choose where the music goes")
        if (s.demo) {
            Body("Pick your SD card or any folder. The sample music (about 1 MB) goes into a folder called “${Settings.DEMO_FOLDER}” inside it; nothing else is touched, and it is removed when you switch to your own server.")
        } else {
            Body("Pick your SD card, or the folder on it that holds your music now.")
            Body("FinSync puts your library in a folder called “${Settings.FOLDER}” inside it. Music already there that matches your library is moved into that folder instead of downloaded again. Nothing else is changed without asking.")
            Body("If the card itself can't be picked, choose its Music folder.")
        }
        Button(onClick = { pick.launch(null) }, modifier = Modifier.padding(vertical = 8.dp)) { Text(if (name == null) "Choose folder" else "Choose a different folder") }
        if (name != null) Body("Chosen: $name" + (free?.let { " · ${bytes(it)} free" } ?: ""))
        ErrorText(error)
        Button(enabled = name != null, onClick = done, modifier = Modifier.padding(top = 12.dp)) { Text("Continue") }
    }
}

// --- 3. Check and go ---------------------------------------------------------------------------

@Composable
private fun CheckScreen(s: Settings, choose: () -> Unit, done: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by SyncRunner.status.collectAsState()
    var plan by remember { mutableStateOf(SyncRunner.lastPlan?.second) }
    var free by remember { mutableStateOf<Long?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var busyElsewhere by remember { mutableStateOf(false) }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { start(ctx, s, done) }
    LaunchedEffect(attempt) {
        if (plan == null) try {
            plan = SyncRunner.plan(ctx)
            busyElsewhere = plan == null && SyncRunner.isRunning
        } catch (e: Exception) { }
        free = withContext(Dispatchers.IO) { try { ContentResolverTree(ctx, Uri.parse(s.treeUri)).freeBytes() } catch (e: Exception) { null } }
    }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Title("Check and go")
        if (s.demo) SampleLabel()
        val p = plan
        if (p == null) {
            if (busyElsewhere) {
                Body("A sync is already running. Its progress is on the home screen.")
                Button(onClick = { s.setupDone = true; done() }) { Text("Go to home screen") }
            } else if (status.error != null) {
                ErrorText(status.error)
                Button(onClick = { SyncRunner.clearError(); attempt++ }) { Text("Try again") }
            } else {
                Body("Looking at your library and at the music already on the card. With a big card this takes a few minutes.")
                Progress(status)
            }
            return@Column
        }
        PlanSummary(p, free, s.folderName)
        Text("What to sync: ${selectionSummary(s)}", fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        OutlinedButton(onClick = choose, modifier = Modifier.padding(top = 4.dp)) { Text("Choose what to sync") }
        if (!p.executable) {
            Body("If you've just changed what to sync, this is expected: those tracks are removed from the “${s.folderName}” folder (nothing outside it is touched).")
            OutlinedButton(onClick = { plan = SyncRunner.allowRemovals(p) }) { Text("Yes, remove ${count(p.totals.delete.files, "file")}") }
        }
        Button(enabled = p.executable, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(56.dp), onClick = {
            if (Build.VERSION.SDK_INT >= 33) notify.launch(Manifest.permission.POST_NOTIFICATIONS) else start(ctx, s, done)
        }) { Text("Sync", fontSize = 20.sp) }
        Body("You can close the app: syncing carries on in the background and picks up where it left off if it's interrupted.")
    }
}

private fun start(ctx: Context, s: Settings, done: () -> Unit) {
    s.setupDone = true
    SyncWorker.schedule(ctx, s.autoSync && !s.demo)
    SyncService.start(ctx)
    done()
}

@Composable
private fun PlanSummary(p: SyncPlan, free: Long?, folder: String) {
    val t = p.totals
    if (t.reuse.files > 0) Body("Already on the card: ${count(t.reuse.files, "track")} (${bytes(t.reuse.bytes)}) will be moved into the “${Settings.FOLDER}” folder. No need to download them.")
    Body(if (t.download.files == 0) "Nothing needs downloading." else "To download: ${count(t.download.files, "track")}, ${bytes(t.download.bytes)}.")
    if (t.adopt.files > 0) Body("${count(t.adopt.files, "track")} ${pick(t.adopt.files, "is", "are")} already in place.")
    if (t.delete.files > 0) Body("${count(t.delete.files, "file")} in the “$folder” folder ${pick(t.delete.files, "is", "are")} no longer in the library or no longer chosen, and will be removed.")
    if (t.art.files + t.lyrics.files + t.playlists.files > 0) Body("Also: ${count(t.art.files, "album cover")}, ${count(t.lyrics.files, "lyrics file")}, ${count(t.playlists.files, "playlist")}.")
    if (p.card.leftovers.isNotEmpty()) Body("On the card: ${count(p.card.leftovers.size, "extra copy", "extra copies")} of library tracks. After the sync you can review and remove ${pick(p.card.leftovers.size, "it", "them")}.")
    if (p.card.notInLibrary.isNotEmpty()) Body("${count(p.card.notInLibrary.size, "music file")} on the card ${pick(p.card.notInLibrary.size, "isn't", "aren't")} in your library. ${pick(p.card.notInLibrary.size, "It is", "They are")} left alone.")
    if (free != null) Body(if (free >= t.bytesNeeded) "The card has ${bytes(free)} free. Everything fits." else "The card has ${bytes(free)} free but this needs about ${bytes(t.bytesNeeded)}.")
    if (p.brakeTripped && !p.executable) ErrorText("Stopped to be safe: ${p.brakeReason}. If you didn't change what to sync, check that the server shows your whole library.")
}

// --- Home --------------------------------------------------------------------------------------

@Composable
private fun HomeScreen(s: Settings, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by SyncRunner.status.collectAsState()
    val card by SyncRunner.card.collectAsState()
    var auto by remember { mutableStateOf(s.autoSync) }
    LaunchedEffect(Unit) { if (!SyncRunner.isRunning) SyncRunner.loadCard(ctx) }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Title("FinSync")
        if (s.demo) SampleLabel()
        UpdatePrompt(status.running)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                if (status.running) {
                    Text(status.title, fontWeight = FontWeight.Bold)
                    Progress(status)
                } else {
                    val at = s.lastSyncAt
                    Text(if (at == 0L) "Not synced yet" else "Last sync " + DateUtils.getRelativeTimeSpanString(at), fontWeight = FontWeight.Bold)
                    s.lastSummary?.let { Text(it, modifier = Modifier.padding(top = 4.dp)) }
                    ErrorText(status.error)
                }
            }
        }
        Button(enabled = !status.running, onClick = { SyncRunner.clearError(); SyncService.start(ctx) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).height(52.dp)) { Text("Sync now", fontSize = 18.sp) }
        if (!s.demo) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Sync automatically on Wi-Fi while charging", modifier = Modifier.weight(1f))
            Switch(auto, { auto = it; s.autoSync = it; SyncWorker.schedule(ctx, it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("What to sync: ${selectionSummary(s)}", modifier = Modifier.weight(1f))
            OutlinedButton(enabled = !status.running, onClick = { go(Screen.CHOOSE) }) { Text("Change") }
        }
        card?.let { c ->
            if (c.leftovers.isNotEmpty()) Notice(
                "${count(c.leftovers.size, "extra copy", "extra copies")} (${bytes(c.leftovers.sumOf { it.source.size })}) of tracks now in the “${Settings.FOLDER}” folder ${pick(c.leftovers.size, "is", "are")} still on the card.",
                "Review and remove",
            ) { go(Screen.SPARES) }
            if (c.notInLibrary.isNotEmpty()) Notice(
                "${count(c.notInLibrary.size, "music file")} on the card ${pick(c.notInLibrary.size, "isn't", "aren't")} in your library (for example music downloaded straight to this player). ${pick(c.notInLibrary.size, "It is", "They are")} left alone.",
                "Show ${pick(c.notInLibrary.size, "it", "them")}",
            ) { go(Screen.NOT_IN_LIBRARY) }
            if (c.damaged.isNotEmpty()) Notice(
                "${count(c.damaged.size, "old copy", "old copies")} on the card ${pick(c.damaged.size, "is", "are")} damaged. ${pick(c.damaged.size, "A good copy was", "Good copies were")} downloaded into the “${Settings.FOLDER}” folder; the damaged ${pick(c.damaged.size, "one is left where it is", "ones are left where they are")}.",
                null,
            ) {}
        }
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        if (s.demo) {
            Text("Card folder: ${s.treeName ?: "?"} → ${s.folderName}", fontSize = 14.sp)
            Button(enabled = !status.running, modifier = Modifier.padding(top = 8.dp), onClick = {
                scope.launch { SyncRunner.leaveDemo(ctx); go(Screen.SERVER) }
            }) { Text("Use my own server") }
            Text("Removes the sample music from the card.", fontSize = 13.sp)
        } else {
            Text("Signed in as ${s.userName ?: "?"} on ${s.server}", fontSize = 14.sp)
            Text("Card folder: ${s.treeName ?: "?"} → ${s.folderName}", fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(enabled = !status.running, onClick = { go(Screen.FOLDER) }) { Text("Change folder") }
                OutlinedButton(enabled = !status.running, onClick = {
                    // End the sign-in on the server too, so the token stops working (best effort: offline, it is just forgotten).
                    val server = s.server; val token = s.token
                    if (server != null && token != null) SyncRunner.signOut(ctx, server, token)
                    s.token = null; s.setupDone = false; go(Screen.SERVER)
                }) { Text("Sign out") }
            }
        }
        TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(feedbackUrl()))) }, modifier = Modifier.padding(top = 8.dp)) {
            Text("Send feedback")
        }
    }
}

/** Shown only while trying the built-in sample library. */
@Composable
private fun SampleLabel() = Text(
    "Sample library: built-in sample music, no server",
    fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp),
)

@Composable
internal fun Notice(text: String, action: String?, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(text)
            if (action != null) TextButton(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
internal fun Progress(st: Status) {
    // Until something is counted, a moving bar: an empty one at "0 of 7114" looks stuck.
    if (st.total > 0 && st.done > 0) LinearProgressIndicator(progress = { st.done.toFloat() / st.total }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
    Text(st.detail, fontSize = 14.sp)
}

// --- Spare copies and music not in the library ---------------------------------------------------

@Composable
private fun SparesScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val card by SyncRunner.card.collectAsState()
    val status by SyncRunner.status.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = back)
    val spares = card?.leftovers.orEmpty()
    Title("Extra copies")
    Body("These files are copies of tracks that are now in the “${Settings.FOLDER}” folder. Removing them frees ${bytes(spares.sumOf { it.source.size })}. Each is removed only if the library copy is in place.")
    result?.let { Body(it) }
    ErrorText(status.error)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = spares.isNotEmpty() && !status.running, onClick = { confirm = true }) { Text("Remove ${count(spares.size, "file")}") }
        OutlinedButton(onClick = back) { Text("Back") }
    }
    LazyColumn(Modifier.padding(top = 12.dp)) {
        items(spares) { Text("${it.source.path}  (${bytes(it.source.size)})", fontSize = 13.sp, modifier = Modifier.padding(vertical = 3.dp)) }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Remove ${count(spares.size, "extra copy", "extra copies")}?") },
        text = { Text("They are deleted from the card. Your library on the server and the copies in the “${Settings.FOLDER}” folder are not touched.") },
        confirmButton = { TextButton(onClick = {
            confirm = false
            scope.launch {
                result = try { SyncRunner.removeSpares(ctx)?.let { (n, b) -> "Removed ${count(n, "file")} (${bytes(b)})." } } catch (e: Exception) { null }
            }
        }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep them") } },
    )
}

@Composable
private fun NotInLibraryScreen(back: () -> Unit) {
    val card by SyncRunner.card.collectAsState()
    BackHandler(onBack = back)
    val files = card?.notInLibrary.orEmpty()
    Title("Not in your library")
    Body("${count(files.size, "music file")} on the card ${pick(files.size, "doesn't", "don't")} match anything in your Jellyfin library, so FinSync leaves ${pick(files.size, "it where it is", "them where they are")}. A later version can send them to the server's Incoming folder.")
    OutlinedButton(onClick = back) { Text("Back") }
    LazyColumn(Modifier.padding(top = 12.dp)) {
        items(files) { Text("${it.path}  (${bytes(it.size)})", fontSize = 13.sp, modifier = Modifier.padding(vertical = 3.dp)) }
    }
}

internal fun bytes(n: Long): String = when {
    n >= 1_000_000_000 -> "%.1f GB".format(n / 1e9)
    n >= 1_000_000 -> "%.0f MB".format(n / 1e6)
    else -> "%.0f KB".format(n / 1e3)
}
