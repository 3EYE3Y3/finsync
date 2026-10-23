package com.eye3y3.finsync.jellyfin

import io.ktor.http.URLProtocol
import io.ktor.http.Url

const val CLEARTEXT_WARNING = "HTTP does not encrypt your password or Jellyfin token. HTTP is common for local Jellyfin servers; use it only on a network you trust."

fun normaliseServerUrl(raw: String): String {
    val address = raw.trim().trimEnd('/')
    require(address.isNotEmpty() && !address.endsWith(':') && !address.endsWith(":/")) { "Enter a server address." }
    val url = Url(if ("://" in address) address else "https://$address")
    require(url.protocol == URLProtocol.HTTP || url.protocol == URLProtocol.HTTPS) { "Use an http:// or https:// server address." }
    require(url.host.isNotBlank() && url.user == null && url.password == null && url.parameters.isEmpty() && url.fragment.isEmpty()) {
        "Enter a server address without credentials, a query or a fragment."
    }
    return url.toString().trimEnd('/')
}

fun isPlainHttp(address: String): Boolean = address.trim().startsWith("http://", ignoreCase = true)
