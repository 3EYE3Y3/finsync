package com.eye3y3.finsync.app

import android.content.Context
import java.util.UUID

/** What FinSync remembers: the server and sign-in, the card folder, and the last sync. Private to the app. */
class Settings(context: Context) {
    private val p = context.getSharedPreferences("finsync", Context.MODE_PRIVATE)

    var server: String? get() = p.getString("server", null); set(v) = p.edit().putString("server", v).apply()
    var token: String? get() = p.getString("token", null); set(v) = p.edit().putString("token", v).apply()
    var userName: String? get() = p.getString("userName", null); set(v) = p.edit().putString("userName", v).apply()
    var treeUri: String? get() = p.getString("treeUri", null); set(v) = p.edit().putString("treeUri", v).apply()
    var treeName: String? get() = p.getString("treeName", null); set(v) = p.edit().putString("treeName", v).apply()
    var setupDone: Boolean get() = p.getBoolean("setupDone", false); set(v) = p.edit().putBoolean("setupDone", v).apply()
    var autoSync: Boolean get() = p.getBoolean("autoSync", true); set(v) = p.edit().putBoolean("autoSync", v).apply()
    var lastSyncAt: Long get() = p.getLong("lastSyncAt", 0L); set(v) = p.edit().putLong("lastSyncAt", v).apply()
    var lastSummary: String? get() = p.getString("lastSummary", null); set(v) = p.edit().putString("lastSummary", v).apply()

    /** Trying FinSync with the built-in sample library (no server). */
    var demo: Boolean get() = p.getBoolean("demo", false); set(v) = p.edit().putBoolean("demo", v).apply()
    /** Once signed in to a real server, the sample library is never offered again. */
    var usedRealServer: Boolean get() = p.getBoolean("usedRealServer", false); set(v) = p.edit().putBoolean("usedRealServer", v).apply()

    /** FinSync's own folder inside the granted one (the samples get a folder of their own). */
    val folderName get() = if (demo) DEMO_FOLDER else FOLDER

    val deviceId: String
        get() = p.getString("deviceId", null) ?: UUID.randomUUID().toString().also { p.edit().putString("deviceId", it).apply() }

    val signedIn get() = server != null && token != null
    val ready get() = signedIn && treeUri != null

    companion object {
        const val FOLDER = "Jellyfin"
        const val DEMO_FOLDER = "FinSync sample music"
    }
}
