package com.tunnel.terminal

import java.net.URL

/**
 * Wave-8: Shared URL validation for AI base URLs and similar settings.
 */
object UrlValidator {
    data class Result(val ok: Boolean, val message: String = "")

    fun validateAiBaseUrl(baseUrl: String): Result {
        val raw = baseUrl.trim()
        if (raw.isBlank()) {
            return Result(false, "Base URL kosong")
        }
        val withScheme = if (raw.contains("://")) raw else "https://$raw"
        val url = try {
            URL(withScheme)
        } catch (e: Exception) {
            return Result(false, "URL tidak valid: ${e.message}")
        }
        val host = url.host?.lowercase().orEmpty()
        if (host.isBlank()) {
            return Result(false, "Host kosong")
        }
        /* v8.5.0 fix (C4): Pakai NetworkPolicy.isLocalOrPrivate (RFC1918 + loopback).
         * Sebelumnya: hardcoded 4 hosts, miss 192.168.x, 0.0.0.0, [::1]. */
        val isLocal = NetworkPolicy.isLocalOrPrivate(url)
        val protocol = url.protocol.lowercase()
        if (protocol != "https" && protocol != "http") {
            return Result(false, "Protocol harus http atau https")
        }
        if (protocol == "http" && !isLocal) {
            return Result(false, "HTTP hanya diizinkan untuk local/private IP (localhost, 127.0.0.1, 10.x, 192.168.x, 172.16-31.x)")
        }
        return Result(true, withScheme.trimEnd('/'))
    }
}
