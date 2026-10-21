package com.eye3y3.finsync.app

import com.eye3y3.finsync.BuildConfig
import android.os.Build
import java.net.URLEncoder

/** The feedback page, told only the app version, how it was installed, the Android version and the device model. */
internal fun feedbackUrl(): String = feedbackUrl(
    BuildConfig.FEEDBACK_URL, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", BuildConfig.CHANNEL,
    Build.VERSION.RELEASE ?: "?", listOfNotNull(Build.MANUFACTURER, Build.MODEL).joinToString(" "),
)

internal fun feedbackUrl(base: String, version: String, channel: String, android: String, device: String): String =
    base + "?" + listOf("version" to version, "channel" to channel, "android" to android, "device" to device)
        .joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8").replace("+", "%20") }
