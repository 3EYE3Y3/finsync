package com.eye3y3.finsync.app

import java.net.URI

/**
 * True for a plain http:// address that doesn't look like the home network or a VPN: there, others on
 * the network could read the sign-in and the access token. Home addresses (192.168.x.x and the other
 * private ranges, Tailscale's 100.64/10, single names like "jellyfin", .local and similar) are fine.
 */
internal fun plainHttpOutsideHome(address: String): Boolean {
    val uri = try { URI(address.trim()) } catch (e: Exception) { return false }
    if (!uri.scheme.equals("http", ignoreCase = true)) return false
    val host = uri.host?.lowercase()?.trim('[', ']') ?: return false
    if ('.' !in host && ':' !in host) return false
    if (listOf(".local", ".lan", ".home", ".home.arpa", ".internal", ".localdomain", ".ts.net").any { host.endsWith(it) } || host == "localhost") return false
    if (':' in host) return !(host == "::1" || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80"))
    val ip = host.split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 } ?: return true
    return !when (ip[0]) {
        10, 127 -> true
        192 -> ip[1] == 168
        172 -> ip[1] in 16..31
        169 -> ip[1] == 254
        100 -> ip[1] in 64..127
        else -> false
    }
}
