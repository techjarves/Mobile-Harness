package com.jarves.mh.network

import java.net.URI

/**
 * Plain-HTTP policy for user-entered provider URLs.
 *
 * Self-hosted model servers (llama.cpp, LM Studio, Ollama, Strata…) usually speak plain HTTP on the
 * local network or over Tailscale, so the platform config permits cleartext. This check keeps API
 * traffic encrypted everywhere else: http:// is only accepted for loopback, private, link-local and
 * Tailscale addresses, and for local-only host names.
 */
object LocalNetwork {
    private val localSuffixes = listOf(".local", ".lan", ".home", ".home.arpa", ".internal", ".localdomain", ".ts.net")

    fun isPlainHttpAllowed(url: String): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        if (!uri.scheme.equals("http", ignoreCase = true)) return true
        return isLocalHost(uri.host ?: return false)
    }

    fun isLocalHost(rawHost: String): Boolean {
        val host = rawHost.trim().trim('[', ']').lowercase().trimEnd('.')
        if (host.isEmpty()) return false
        if (host == "localhost" || localSuffixes.any(host::endsWith)) return true
        if (host.contains(':')) return isLocalIpv6(host)
        val octets = host.split('.')
        if (octets.size == 4 && octets.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }) {
            val (a, b) = octets.take(2).map(String::toInt)
            return a == 10 || a == 127 ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 169 && b == 254) ||
                (a == 100 && b in 64..127) // Tailscale / carrier-grade NAT
        }
        // A single-label name (e.g. "my-pc") can only resolve on the local network.
        return !host.contains('.')
    }

    private fun isLocalIpv6(host: String): Boolean =
        host == "::1" || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe8") ||
            host.startsWith("fe9") || host.startsWith("fea") || host.startsWith("feb")
}
