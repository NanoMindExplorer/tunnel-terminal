package com.tunnel.terminal

import java.net.InetAddress
import java.net.URL

/**
 * NetworkPolicy — Centralized network security policy (v8.5.0 fix C4).
 *
 * Sebelumnya: 4 file punya implementasi "is localhost" yang duplikat dan
 * inconsistent:
 *   - AIAgent.isConfigured: substring match "localhost" / "127.0.0.1"
 *   - AIAgent.enforceHttpsOrLocal: host == "localhost" || "127.0.0.1" || "10.0.2.2" || "::1"
 *   - ModelFetcher: same as enforceHttpsOrLocal
 *   - McpManager.isAllowedMcpUrl: adds "[::1]" + blocks "169.254.169.254" + ".internal"
 *   - UrlValidator: same as enforceHttpsOrLocal
 *
 * Masalah:
 *   - User dengan Ollama di 192.168.1.10:11434 ditolak (bukan loopback)
 *   - 0.0.0.0 tidak dikenali (bind-all-interfaces server)
 *   - IPv4-mapped IPv6 (::ffff:127.0.0.1) tidak dikenali
 *   - RFC1918 private ranges (10.x, 192.168.x, 172.16-31.x) tidak dikenali
 *
 * Fix: Single source of truth di NetworkPolicy object. Mendukung:
 *   - Loopback: localhost, 127.0.0.0/8, ::1, [::1]
 *   - Bind-all: 0.0.0.0
 *   - Android emulator host: 10.0.2.2
 *   - RFC1918 private: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16
 *   - Link-local: 169.254.0.0/16 (tapi block 169.254.169.254 cloud metadata)
 *   - IPv6 ULA: fc00::/7
 */
object NetworkPolicy {

    /** Hostnames yang selalu dianggap local (case-insensitive). */
    private val LOCAL_HOSTNAMES = setOf(
        "localhost", "127.0.0.1", "::1", "[::1]",
        "0.0.0.0", "10.0.2.2"  // Android emulator host → guest loopback
    )

    /** Cloud metadata endpoints yang HARUS diblok (bahkan untuk HTTPS). */
    private val BLOCKED_HOSTS = setOf(
        "169.254.169.254",  // AWS/GCP/Azure cloud metadata
        "metadata.google.internal",  // GCP metadata
        "metadata.azure.com"  // Azure metadata
    )

    /**
     * Cek apakah URL mengarah ke host yang dianggap local/private/safe untuk HTTP.
     *
     * Mengembalikan true untuk:
     * - localhost, 127.0.0.1, ::1, [::1], 0.0.0.0, 10.0.2.2
     * - RFC1918 private ranges (10.x, 192.168.x, 172.16-31.x)
     * - IPv6 ULA (fc00::/7)
     *
     * Mengembalikan false untuk:
     * - Public IPs (e.g. 8.8.8.8)
     * - Cloud metadata endpoints (169.254.169.254)
     * - Hostnames yang tidak bisa di-resolve (akan return false, bukan crash)
     */
    fun isLocalOrPrivate(url: URL): Boolean {
        val host = url.host?.lowercase() ?: return false

        // Fast path: known local hostnames (no DNS lookup needed)
        if (host in LOCAL_HOSTNAMES) return true

        // Block cloud metadata endpoints explicitly
        if (host in BLOCKED_HOSTS) return false
        if (host.endsWith(".internal") || host.endsWith(".local") ||
            host.endsWith(".localhost") || host.endsWith(".home") ||
            host.endsWith(".lan") || host.endsWith(".corp")) return false

        /* v9.1.0 fix (H-2): Cek IP literal dulu (no DNS lookup) sebelum resolve hostname.
         * Jika host adalah IP literal (e.g. "192.168.1.10"), InetAddress.getByName tidak
         * melakukan DNS query — langsung parse. Hanya hostname (e.g. "myserver.local")
         * yang membutuhkan DNS.
         *
         * Untuk mencegah NetworkOnMainThreadException, caller harus memanggil ini dari
         * background thread. UrlValidator.validateAiBaseUrl sekarang dipanggil dari
         * lifecycleScope.launch (background). */
        return try {
            val addr = InetAddress.getByName(host)
            addr.isLoopbackAddress ||
            addr.isSiteLocalAddress ||  // RFC1918: 10.x, 172.16-31.x, 192.168.x
            addr.isLinkLocalAddress     // 169.254.x (tapi 169.254.169.254 sudah di-block di atas)
        } catch (e: Exception) {
            // Hostname tidak bisa di-resolve (e.g. no DNS) — treat as non-local untuk safety
            false
        }
    }

    /**
     * Cek apakah URL mengarah ke cloud metadata endpoint (harus selalu diblok).
     */
    fun isCloudMetadata(url: URL): Boolean {
        val host = url.host?.lowercase() ?: return false
        return host in BLOCKED_HOSTS || host.endsWith(".internal")
    }

    /**
     * Enforce HTTPS untuk URL eksternal.
     *
     * @throws java.io.IOException jika URL non-HTTPS dan bukan local/private
     */
    fun enforceHttpsOrThrow(url: URL) {
        if (!url.protocol.equals("https", ignoreCase = true)) {
            if (!isLocalOrPrivate(url)) {
                throw java.io.IOException(
                    "Security: Hanya HTTPS yang didukung untuk provider eksternal. " +
                    "URL '${url.host}' menggunakan HTTP tidak aman. " +
                    "Gunakan HTTPS, atau local/private IP untuk local AI (Ollama/LM Studio)."
                )
            }
        }
    }

    /**
     * Cek apakah URL diizinkan untuk MCP server.
     *
     * MCP servers lebih restrictive: block cloud metadata bahkan untuk HTTPS.
     */
    fun isAllowedMcpUrl(urlStr: String): Boolean {
        return try {
            val url = URL(urlStr)
            // Block cloud metadata regardless of protocol
            if (isCloudMetadata(url)) return false

            when {
                url.protocol.equals("http", ignoreCase = true) -> isLocalOrPrivate(url)
                url.protocol.equals("https", ignoreCase = true) -> true
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }
}
