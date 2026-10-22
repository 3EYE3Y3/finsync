package com.eye3y3.finsync.app

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.eye3y3.finsync.app.saf.ContentResolverTree
import com.eye3y3.finsync.app.saf.SafFileSystem
import com.eye3y3.finsync.createHttpClient
import com.eye3y3.finsync.jellyfin.FinSyncError
import com.eye3y3.finsync.jellyfin.JellyfinClient
import com.eye3y3.finsync.jellyfin.ServerConfig
import com.eye3y3.finsync.plan.PlanOptions
import com.eye3y3.finsync.plan.SyncPlan
import com.eye3y3.finsync.sync.CardReportFile
import com.eye3y3.finsync.sync.SyncEngine
import com.eye3y3.finsync.app.demo.DemoServer
import com.eye3y3.finsync.app.demo.removeSamples
import com.eye3y3.finsync.sync.SyncListener
import com.eye3y3.finsync.sync.SyncOptions
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Path.Companion.toPath

/** What the app shows about the current (or last) piece of work. */
data class Status(
    val running: Boolean = false,
    val title: String = "",
    val detail: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val error: String? = null,
)

/** Runs plans, syncs and spare-copy removal one at a time, for the screens, the service and the scheduled worker. */
object SyncRunner {
    const val TAG = "FinSync"
    private val mutex = Mutex()
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    /** The plan the check screen showed, reused by the sync that follows if it is still fresh. */
    @Volatile var lastPlan: Pair<Long, SyncPlan>? = null
        private set

    /** What the last sync found on the card (spare copies, music not in the library, damaged files). */
    private val _card = MutableStateFlow<CardReportFile?>(null)
    val card: StateFlow<CardReportFile?> = _card

    val isRunning get() = mutex.isLocked

    private val progress = Regex("""(\d+) of (\d+)""")
    @Volatile private var lastReport = 0L

    /** Card and server work, never on the main thread (it blocks for minutes on a full card). */
    internal var workDispatcher: CoroutineDispatcher = Dispatchers.IO

    /** Progress for the screen and notification, at most about 4 times a second (and always the last one). */
    private fun report(title: String, message: String) {
        val m = progress.find(message)
        val done = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val total = m?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val now = System.currentTimeMillis()
        if (now - lastReport < 250 && (total == 0 || done < total)) return
        lastReport = now
        Log.i(TAG, "$title: $message")
        _status.value = Status(true, title, message, done, total)
    }

    private fun <T> withEngine(context: Context, block: suspend (SyncEngine, Settings) -> T): suspend () -> T = {
        val s = Settings(context)
        val server = s.server ?: throw FinSyncError.SignedOut()
        val tree = ContentResolverTree(context, Uri.parse(s.treeUri ?: throw FinSyncError.Problem("Choose the SD card folder first.")))
        val http: HttpClient = if (s.demo) DemoServer.fromAssets(context).httpClient() else createHttpClient()
        try {
            val client = JellyfinClient(http, ServerConfig(server, s.deviceId, "FinSync on ${Build.MODEL}"), s.token)
            val engine = SyncEngine(
                client, SafFileSystem(tree), "/${s.folderName}".toPath(),
                // The samples never touch other music on the card.
                SyncOptions(PlanOptions(), concurrency = 2, reuseFrom = if (s.demo) null else "/".toPath()),
                freeSpace = { tree.freeBytes() },
            )
            block(engine, s)
        } finally {
            http.close()
        }
    }

    internal suspend fun <T> exclusive(title: String, work: suspend () -> T): T? {
        if (!mutex.tryLock()) return null
        try {
            _status.value = Status(true, title, "Starting…")
            val r = withContext(workDispatcher) { work() }
            _status.value = Status(false, title, _status.value.detail)
            return r
        } catch (e: FinSyncError) {
            Log.w(TAG, "$title stopped: ${e.message}")
            _status.value = Status(false, title, error = e.message)
            throw e
        } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) {
                _status.value = Status(false, title, error = "Stopped. The next sync carries on where this one stopped.")
                throw e
            }
            Log.e(TAG, "$title failed", e)
            _status.value = Status(false, title, error = "Something went wrong: ${e.message ?: e::class.simpleName}")
            throw e
        } finally {
            mutex.unlock()
        }
    }

    /** Works out what a sync would do, including what can be taken from the card. Changes nothing. */
    suspend fun plan(context: Context): SyncPlan? = exclusive("Checking") {
        withEngine(context) { engine, _ ->
            Log.i(TAG, "Check started")
            engine.plan(listener("Checking")).also { lastPlan = System.currentTimeMillis() to it; Log.i(TAG, "Check finished") }
        }()
    }

    /** Ends a sign-in on the server in the background; failures (offline, server gone) are ignored. */
    @OptIn(DelicateCoroutinesApi::class)
    fun signOut(context: Context, server: String, token: String) {
        val deviceId = Settings(context).deviceId
        GlobalScope.launch(Dispatchers.IO) {
            val http = createHttpClient()
            try {
                withTimeout(10_000) { JellyfinClient(http, ServerConfig(server, deviceId, "FinSync on ${Build.MODEL}"), token).signOut() }
            } catch (e: Exception) {
                Log.i(TAG, "Sign-out on the server didn't go through: ${e.message}")
            } finally {
                http.close()
            }
        }
    }

    /** The check screen's plan is out of date (the folder, server or what to sync changed). */
    fun forgetPlan() { lastPlan = null }

    /** A full sync. Uses the plan from the check screen when it's less than half an hour old. */
    suspend fun sync(context: Context): Boolean? = exclusive("Syncing") {
        withEngine(context) { engine, s ->
            val cached = lastPlan?.takeIf { System.currentTimeMillis() - it.first < 30 * 60_000 }?.second
            lastPlan = null
            Log.i(TAG, "Sync started" + if (cached != null) " (with the plan from the check screen)" else "")
            val plan = cached ?: engine.plan(listener("Checking"))
            if (!plan.executable) throw FinSyncError.DeletionBrake(plan.brakeReason ?: "too many deletions")
            val r = engine.execute(plan, listener("Syncing"))
            Log.i(TAG, "Sync finished: ${r.downloaded} downloaded, ${r.reused} reused, ${r.deleted} removed, ${r.failures.size} failed")
            _card.value = engine.cardReport()
            s.lastSyncAt = System.currentTimeMillis()
            s.lastSummary = buildString {
                append(if (r.ok) "Up to date. " else "Mostly done. ")
                val parts = listOfNotNull(
                    r.downloaded.takeIf { it > 0 }?.let { "$it downloaded" },
                    r.reused.takeIf { it > 0 }?.let { "$it taken from the card" },
                    r.deleted.takeIf { it > 0 }?.let { "$it removed" },
                )
                append(if (parts.isEmpty()) "Nothing new." else parts.joinToString(", ") + ".")
                if (!r.ok) append(" ${r.failures.size} couldn't be fetched; the next sync tries again.")
            }
            _status.value = Status(true, "Syncing", s.lastSummary!!)
            r.ok
        }()
    }

    /** Removes the spare copies the last sync listed (after the user confirmed). Returns (files, bytes). */
    suspend fun removeSpares(context: Context): Pair<Int, Long>? = exclusive("Removing spare copies") {
        withEngine(context) { engine, _ ->
            engine.removeLeftovers().also { _card.value = engine.cardReport() }
        }()
    }

    /**
     * Leaves the sample library: removes the files the sample syncs wrote (and their folder if that
     * leaves it empty), and forgets the sample "server" and the folder choice.
     */
    suspend fun leaveDemo(context: Context) {
        val s = Settings(context)
        if (!s.demo) return
        exclusive("Removing the sample music") {
            val uri = s.treeUri
            if (uri != null) try {
                removeSamples(SafFileSystem(ContentResolverTree(context, Uri.parse(uri))), "/${Settings.DEMO_FOLDER}".toPath())
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't remove all sample music: ${e.message}")
            }
            Unit
        }
        s.demo = false; s.server = null; s.token = null; s.userName = null
        s.treeUri = null; s.treeName = null; s.setupDone = false
        s.lastSyncAt = 0L; s.lastSummary = null
        lastPlan = null
        _card.value = null
    }

    /** Loads the card report without syncing (e.g. when the app opens). */
    suspend fun loadCard(context: Context) {
        try {
            withContext(workDispatcher) { withEngine(context) { engine, _ -> _card.value = engine.cardReport() }() }
        } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
        }
    }

    /** The engine's listener: progress for the screens, details to logcat (adb logcat -s FinSync). */
    private fun listener(title: String) = object : SyncListener {
        override fun report(message: String) = report(title, message)
        override fun log(message: String) { Log.i(TAG, message) }
    }

    fun clearError() { _status.value = _status.value.copy(error = null) }
}
