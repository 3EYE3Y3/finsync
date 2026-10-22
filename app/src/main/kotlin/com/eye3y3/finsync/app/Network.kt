package com.eye3y3.finsync.app

import java.net.URI

/**
 * True for a plain http:// address that doesn't look like the home network or a VPN: there, others on
 * the network could read the sign-in and the access token. Home addresses (192.168.x.x and the other
 * private ranges, Tailscale's 100.64/10, single names like "jellyfin", .local and similar) are fine.
 * [address] is what the user typed: like the sign-in, no scheme means http.
 */
internal fun plainHttpOutsideHome(address: String): Boolean {
    var a = address.trim()
    if (a.isEmpty() || a == "http://" || a == "https://") return false
    if (!a.contains("://")) a = "http://$a"
    val uri = try { URI(a) } catch (e: Exception) { return true }
    if (!uri.scheme.equals("http", ignoreCase = true)) return false
    // Anything that can't be read as a host is treated as unsafe rather than waved through.
    val host = (uri.host ?: return true).lowercase().trim('[', ']').trimEnd('.')
    if (':' in host) return !(host == "::1" || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80:"))
    val labels = host.split('.')
    if (labels.all { it.isNotEmpty() && it.all(Char::isDigit) }) {
        // An IP address: exactly four numbers (a single number like 134744072 is an IP too, and isn't home).
        val ip = labels.map { it.toIntOrNull() ?: return true }.takeIf { it.size == 4 && it.all { n -> n in 0..255 } } ?: return true
        return !when (ip[0]) {
            10, 127 -> true
            192 -> ip[1] == 168
            172 -> ip[1] in 16..31
            169 -> ip[1] == 254
            100 -> ip[1] in 64..127
            else -> false
        }
    }
    if (labels.size == 1 || host == "localhost") return false
    return listOf(".local", ".lan", ".home", ".home.arpa", ".internal", ".localdomain", ".ts.net").none { host.endsWith(it) }
}
