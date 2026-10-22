package com.eye3y3.finsync.app

import com.eye3y3.finsync.BuildConfig
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates for the APK from GitHub Releases (this build only; the Play build uses Play's In-App Updates and never
 * calls GitHub). At most once a day, asks for the latest release of [BuildConfig.GITHUB_REPO]; its `version.json`
 * asset says which versionCode it is. A newer one shows a card linking to its APK. Nothing is sent but the request.
 */
@Composable
internal fun UpdatePrompt(@Suppress("UNUSED_PARAMETER") syncing: Boolean) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("updates", Context.MODE_PRIVATE) }
    var newer by remember { mutableStateOf(GitHubUpdates.stored(prefs.getInt("code", 0), prefs.getString("name", null), prefs.getString("url", null))) }
    LaunchedEffect(Unit) {
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("checkedAt", 0L) < GitHubUpdates.INTERVAL_MS) return@LaunchedEffect
        prefs.edit().putLong("checkedAt", now).apply()
        val r = withContext(Dispatchers.IO) {
            runCatching { GitHubUpdates.latest(GitHubUpdates::get) }.onFailure { Log.i("FinSync", "Update check: $it") }.getOrNull()
        } ?: return@LaunchedEffect
        prefs.edit().putInt("code", r.versionCode).putString("name", r.versionName).putString("url", r.apkUrl).apply()
        newer = r
    }
    val r = newer ?: return
    if (r.versionCode > BuildConfig.VERSION_CODE) Notice("FinSync ${r.versionName} (${r.versionCode}) is available.", "Download") {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.apkUrl)))
    }
}

internal object GitHubUpdates {
    const val INTERVAL_MS = 24 * 60 * 60 * 1000L
    private val json = Json { ignoreUnknownKeys = true }

    data class Release(val versionCode: Int, val versionName: String, val apkUrl: String)

    fun stored(code: Int, name: String?, url: String?) = if (code > 0 && name != null && url != null) Release(code, name, url) else null

    /** The latest release, from the releases API and its version.json asset (fetched with [fetch]); null if it has none. */
    fun latest(fetch: (String) -> String): Release? {
        val rel = json.parseToJsonElement(fetch("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")).jsonObject
        val assets = rel["assets"]?.jsonArray?.associate {
            it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["browser_download_url"]!!.jsonPrimitive.content
        } ?: return null
        // Only links into this repository are followed or offered.
        val ours = "https://github.com/${BuildConfig.GITHUB_REPO}/"
        val v = json.parseToJsonElement(fetch(assets["version.json"]?.takeIf { it.startsWith(ours) } ?: return null)).jsonObject
        val apk = (assets[v["apk"]?.jsonPrimitive?.content] ?: rel["html_url"]?.jsonPrimitive?.content)?.takeIf { it.startsWith(ours) } ?: return null
        return Release(v["versionCode"]!!.jsonPrimitive.int, v["versionName"]!!.jsonPrimitive.content, apk)
    }

    fun get(url: String): String = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 10_000; readTimeout = 10_000
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "FinSync/${BuildConfig.VERSION_NAME}")
        try {
            if (responseCode != 200) error("HTTP $responseCode")
            inputStream.use { it.readBytes().decodeToString() }
        } finally { disconnect() }
    }
}
