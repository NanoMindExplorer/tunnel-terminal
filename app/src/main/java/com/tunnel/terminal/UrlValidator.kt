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
        val isLocal = host == "localhost" || host == "127.0.0.1" ||
            host == "10.0.2.2" || host == "::1"
        val protocol = url.protocol.lowercase()
        if (protocol != "https" && protocol != "http") {
            return Result(false, "Protocol harus http atau https")
        }
        if (protocol == "http" && !isLocal) {
            return Result(false, "HTTP hanya diizinkan untuk localhost / 10.0.2.2")
        }
        return Result(true, withScheme.trimEnd('/'))
    }
}
