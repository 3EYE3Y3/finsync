package com.eye3y3.finsync.cli

import com.eye3y3.finsync.createHttpClient
import com.eye3y3.finsync.jellyfin.FinSyncError
import com.eye3y3.finsync.jellyfin.JellyfinClient
import com.eye3y3.finsync.jellyfin.ServerConfig
import com.eye3y3.finsync.plan.PlanOptions
import com.eye3y3.finsync.plan.SyncPlan
import com.eye3y3.finsync.plan.toJson
import com.eye3y3.finsync.sync.SyncEngine
import com.eye3y3.finsync.sync.SyncOptions
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File
import java.io.PrintStream
import java.nio.file.Path
import java.util.UUID
import kotlin.system.exitProcess

const val EXIT_OK = 0
const val EXIT_ERROR = 1
const val EXIT_BRAKE = 2
const val EXIT_SIGNED_OUT = 3

private val HELP = """
    finsync - mirror a Jellyfin music library to a folder or card (private test tool)

      finsync login  --server http://HOST:8096 [--wait 300]
      finsync status
      finsync plan   --dest DIR [--root PATH] [--library NAME] [--list] [--json FILE]
      finsync sync   --dest DIR [--max-bytes 5G] [--artist NAME]... [--allow-large-delete]
                     [--concurrency 2] [--root PATH] [--library NAME] [--reuse CARD_DIR]
      finsync remove-spares --dest DIR --reuse CARD_DIR

    --reuse CARD_DIR: music already in CARD_DIR (outside DIR) that matches library tracks by content is
    moved into the mirror instead of downloaded; spare copies are only listed until remove-spares.

    Exit codes: 0 ok, 1 error, 2 deletion brake stopped the sync, 3 not signed in.
""".trimIndent()

/** The command line. Output goes to [out] so tests can read it. */
class Cli(private val out: PrintStream = System.out, private val credentialsPath: Path = Credentials.defaultPath) {
    fun run(argv: List<String>): Int {
        if (argv.any { it == "--help" || it == "-h" || it == "help" }) { out.println(HELP); return EXIT_OK }
        val http = createHttpClient()
        return try {
            runBlocking { dispatch(Args.parse(argv), http) }
        } catch (e: FinSyncError) {
            out.println("Error: ${e.message}")
            when (e) { is FinSyncError.SignedOut -> EXIT_SIGNED_OUT; is FinSyncError.DeletionBrake -> EXIT_BRAKE; else -> EXIT_ERROR }
        } catch (e: IllegalArgumentException) {
            out.println("Error: ${e.message}")
            EXIT_ERROR
        } catch (e: Exception) {
            out.println("Something unexpected went wrong: ${e.message ?: e::class.simpleName}")
            EXIT_ERROR
        } finally {
            http.close()
        }
    }

    private suspend fun dispatch(args: Args, http: HttpClient): Int = when (args.command) {
        "login" -> login(args, http)
        "status" -> status(http)
        "plan" -> plan(args, http)
        "sync" -> sync(args, http)
        "remove-spares" -> removeSpares(args, http)
        else -> { out.println(HELP); if (args.command == null) EXIT_OK else EXIT_ERROR }
    }

    private suspend fun login(args: Args, http: HttpClient): Int {
        val server = args.require("server").trimEnd('/')
        val waitSeconds = args.get("wait")?.toLongOrNull() ?: 300L
        val deviceId = Credentials.load(credentialsPath)?.deviceId ?: UUID.randomUUID().toString()
        val client = JellyfinClient(http, ServerConfig(server, deviceId, "FinSync CLI on ${hostName()}"))
        val info = client.serverInfo()
        out.println("Connected to ${info.name} (Jellyfin ${info.version}).")
        val session = client.signInWithQuickConnect(waitSeconds * 1000) { code ->
            out.println("Quick Connect code: $code")
            out.println("On a device already signed in to Jellyfin, open Settings > Quick Connect and enter it. Waiting up to $waitSeconds seconds...")
        }
        Credentials(server, deviceId, session.userId, session.token).save(credentialsPath)
        out.println("Signed in as ${session.userName}. Saved to $credentialsPath (readable by you only).")
        return EXIT_OK
    }

    private suspend fun status(http: HttpClient): Int {
        val client = signedInClient(http) ?: return EXIT_SIGNED_OUT
        val info = client.serverInfo()
        val user = client.currentUser()
        out.println("Server:    ${info.name} (Jellyfin ${info.version}) at ${client.config.baseUrl}")
        out.println("Signed in: ${user.name}${if (user.isAdmin) " (administrator)" else ""}")
        out.println("Downloads: ${if (user.canDownload) "allowed" else "NOT allowed for this account"}")
        val libs = client.musicLibraries(user.id)
        out.println("Music libraries: ${if (libs.isEmpty()) "none" else libs.joinToString { it.name }}")
        return EXIT_OK
    }

    private suspend fun plan(args: Args, http: HttpClient): Int {
        val engine = engine(args, http) ?: return EXIT_SIGNED_OUT
        val plan = engine.plan { out.println(it) }
        describe(plan, args.flag("list"))
        args.get("json")?.let { File(it).writeText(plan.toJson()); out.println("Plan written to $it") }
        return if (plan.brakeTripped) EXIT_BRAKE else EXIT_OK
    }

    private suspend fun sync(args: Args, http: HttpClient): Int {
        val engine = engine(args, http) ?: return EXIT_SIGNED_OUT
        val plan = engine.plan { out.println(it) }
        describe(plan, false)
        if (!plan.executable) throw FinSyncError.DeletionBrake(plan.brakeReason!!)
        if (plan.isEmpty) { out.println("Nothing to do."); return EXIT_OK }
        val r = engine.execute(plan) { out.println(it) }
        out.println("Done: ${r.downloaded} downloaded, ${r.reused} reused from the card, ${r.adopted} adopted, ${r.moved} renamed, ${r.deleted} removed.")
        r.failures.forEach { out.println("Could not fetch $it") }
        if (r.failures.isNotEmpty()) out.println("Run the same command again to retry the ${r.failures.size} that failed.")
        return if (r.ok) EXIT_OK else EXIT_ERROR
    }

    private suspend fun removeSpares(args: Args, http: HttpClient): Int {
        val engine = engine(args, http) ?: return EXIT_SIGNED_OUT
        if (args.get("reuse") == null) throw IllegalArgumentException("remove-spares needs --reuse CARD_DIR")
        val (n, bytes) = engine.removeLeftovers()
        out.println("Removed $n spare copies (${humanBytes(bytes)}).")
        return EXIT_OK
    }

    private fun engine(args: Args, http: HttpClient): SyncEngine? {
        val dest = args.require("dest")
        val client = signedInClient(http) ?: return null
        val options = SyncOptions(
            plan = PlanOptions(
                root = args.get("root"),
                allowLargeDelete = args.flag("allow-large-delete"),
                maxBytes = args.get("max-bytes")?.let(::parseSize),
                artists = args.all("artist"),
            ),
            concurrency = args.get("concurrency")?.toIntOrNull()?.takeIf { it > 0 } ?: 2,
            libraryName = args.get("library"),
            reuseFrom = args.get("reuse")?.let { File(it).absoluteFile.path.toPath() },
        )
        return SyncEngine(client, FileSystem.SYSTEM, File(dest).absoluteFile.path.toPath(), options)
    }

    private fun signedInClient(http: HttpClient): JellyfinClient? {
        val c = Credentials.load(credentialsPath)
        if (c == null) {
            out.println("You are not signed in. Run: finsync login --server http://HOST:8096")
            return null
        }
        return JellyfinClient(http, ServerConfig(c.server, c.deviceId, "FinSync CLI on ${hostName()}"), c.token)
    }

    private fun describe(plan: SyncPlan, list: Boolean) {
        val t = plan.totals
        out.println("Plan:")
        out.println("  download  ${t.download.files} files, ${humanBytes(t.download.bytes)}")
        out.println("  adopt     ${t.adopt.files} files already there with the right size")
        out.println("  rename    ${t.move.files} files")
        out.println("  remove    ${t.delete.files} files no longer on the server")
        out.println("  extras    ${t.art.files} cover pictures, ${t.lyrics.files} lyrics, ${t.playlists.files} playlists")
        out.println("  skipped   ${t.conflicts.files} files that are not FinSync's and differ (left alone)")
        out.println("  names changed by the naming rules: ${t.namesChanged}")
        out.println("  space needed: ${humanBytes(t.bytesNeeded)}")
        if (t.reuse.files > 0 || plan.card.leftovers.isNotEmpty() || plan.card.notInLibrary.isNotEmpty()) {
            out.println("  on the card already: ${t.reuse.files} tracks reused (${humanBytes(t.reuse.bytes)}), " +
                "${plan.card.leftovers.size} spare copies, ${plan.card.notInLibrary.size} files not in the library")
        }
        plan.brakeReason?.let { out.println("  DELETION BRAKE: $it.${if (plan.allowLargeDelete) " (allowed by --allow-large-delete)" else ""}") }
        if (list) {
            plan.downloads.forEach { out.println("download ${it.path}${if (it.replace) " (replace)" else ""}") }
            plan.adopts.forEach { out.println("adopt    ${it.path}") }
            plan.moves.forEach { out.println("rename   ${it.from} -> ${it.to}") }
            plan.deletes.forEach { out.println("remove   ${it.path}") }
            plan.art.forEach { out.println("cover    ${it.path}") }
            plan.lyrics.forEach { out.println("lyrics   ${it.path}") }
            plan.playlists.forEach { out.println("playlist ${it.path}") }
            plan.conflicts.forEach { out.println("skipped  ${it.path}") }
            plan.card.reuses.forEach { out.println("reuse    ${it.source.path} -> ${it.target}${if (it.inPlace) "" else " (rebuilt)"}") }
            plan.card.leftovers.forEach { out.println("spare    ${it.source.path}") }
            plan.card.notInLibrary.forEach { out.println("unknown  ${it.path}") }
        }
    }

    private fun hostName() = try { java.net.InetAddress.getLocalHost().hostName } catch (e: Exception) { "this computer" }
}

fun main(args: Array<String>): Unit = exitProcess(Cli().run(args.toList()))
