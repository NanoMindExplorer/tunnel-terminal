package com.tunnel.terminal

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import java.io.File
import java.net.NetworkInterface

/**
 * SystemInfo - Kumpulan info sistem untuk MOTD banner saat shell pertama start.
 *
 * Memperbaiki bug: sebelumnya MOTD hanya banner statis "Tunnel Terminal v3.2".
 * Sekarang menampilkan info dinamis: Android version, device model, CPU arch,
 * memory, disk, network IP, uptime.
 *
 * System info for MOTD banner. Previously MOTD was a static string; now
 * shows dynamic info: Android version, device, CPU, memory, disk, network, uptime.
 */
object SystemInfo {
    private const val TAG = "SystemInfo"

    /**
     * Build MOTD banner lengkap untuk ditampilkan saat shell start.
     * Build full MOTD banner shown on shell startup.
     */
    fun buildMotd(context: Context): String {
        val sb = StringBuilder()
        sb.append("\u001B[32m") // hijau
        sb.appendLine("╔══════════════════════════════════════════════════════════════╗")
        sb.appendLine("║       TUNNEL TERMINAL v3.0 - AI Native Dev Environment       ║")
        sb.appendLine("║          NDK PTY + Multi-Provider AI Copilot                ║")
        sb.appendLine("╚══════════════════════════════════════════════════════════════╝")
        sb.append("\u001B[0m") // reset

        sb.append("\u001B[36m") // cyan
        sb.appendLine("─ System Info ────────────────────────────────────────────────")
        sb.append("\u001B[0m")

        sb.appendLine("  OS        : Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("  Device    : ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("  CPU Arch  : ${cpuArch()}")
        sb.appendLine("  Cores     : ${Runtime.getRuntime().availableProcessors()}")

        /* Memory info */
        memInfo(context)?.let { mem ->
            sb.appendLine("  Memory    : ${mem.first} MB used / ${mem.second} MB total")
        }

        /* Disk info */
        diskInfo()?.let { disk ->
            sb.appendLine("  Storage   : ${disk.first} GB free / ${disk.second} GB total")
        }

        /* Network IP */
        networkIp()?.let { ip ->
            sb.appendLine("  Network   : $ip")
        } ?: sb.appendLine("  Network   : <offline>")

        /* Uptime */
        sb.appendLine("  Uptime    : ${uptimeString()}")

        /* Working directory */
        sb.appendLine("  Home      : ${context.filesDir.absolutePath}/home")

        sb.append("\u001B[36m")
        sb.appendLine("─ Quick Help ─────────────────────────────────────────────────")
        sb.append("\u001B[0m")
        sb.appendLine("  help              Tampilkan menu bantuan lengkap")
        sb.appendLine("  setup-storage     Bridge ke /sdcard via Storage Access Framework")
        sb.appendLine("  open <file>       Edit file di Tunnel Editor UI")
        sb.appendLine("  clear             Bersihkan layar terminal")
        sb.appendLine("  Volume Up/Down    Navigasi riwayat perintah")
        sb.appendLine("  Pinch             Zoom in/out ukuran font")
        sb.appendLine()
        return sb.toString()
    }

    private fun cpuArch(): String {
        /* minSdk = 24, jadi SUPPORTED_ABIS selalu tersedia.
         * minSdk = 24, so SUPPORTED_ABIS is always available. */
        val abis = Build.SUPPORTED_ABIS
        return abis.joinToString(", ").ifEmpty { "unknown" }
    }

    @SuppressLint("ServiceCast")
    private fun memInfo(context: Context): Pair<Long, Long>? {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val used = (mi.totalMem - mi.availMem) / (1024 * 1024)
            val total = mi.totalMem / (1024 * 1024)
            Pair(used, total)
        } catch (e: Exception) {
            Log.w(TAG, "memInfo error: ${e.message}")
            null
        }
    }

    private fun diskInfo(): Pair<Double, Double>? {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val free = stat.availableBlocksLong * stat.blockSizeLong / 1_000_000_000.0
            val total = stat.blockCountLong * stat.blockSizeLong / 1_000_000_000.0
            Pair(
                "%.1f".format(free).toDouble(),
                "%.1f".format(total).toDouble()
            )
        } catch (e: Exception) {
            Log.w(TAG, "diskInfo error: ${e.message}")
            null
        }
    }

    private fun networkIp(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                /* Skip interface yang nama-namanya tidak relevan.
                 * Skip non-relevant interfaces. */
                val name = iface.name.lowercase()
                if (name.contains("dummy") || name.contains("rmnet")) continue

                val addrs = iface.inetAddresses?.toList() ?: continue
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        val host = addr.hostAddress ?: continue
                        /* Filter IPv4 saja untuk readability.
                         * Filter IPv4 only for readability. */
                        if (!host.contains(":")) return "${iface.name}: $host"
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "networkIp error: ${e.message}")
            null
        }
    }

    private fun uptimeString(): String {
        return try {
            /* Baca /proc/uptime untuk Android uptime (bukan seit kernel boot).
             * Read /proc/uptime. */
            val uptimeSec = File("/proc/uptime").useLines { it.firstOrNull() }
                ?.split(" ")?.firstOrNull()?.toLongOrNull() ?: return "unknown"
            val days = uptimeSec / 86400
            val hours = (uptimeSec % 86400) / 3600
            val mins = (uptimeSec % 3600) / 60
            val secs = uptimeSec % 60
            buildString {
                if (days > 0) append("${days}d ")
                if (hours > 0 || days > 0) append("${hours}h ")
                if (mins > 0 || hours > 0 || days > 0) append("${mins}m ")
                append("${secs}s")
            }
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * Cek apakah perangkat sedang online (untuk fitur AI).
     * Check if device is online (for AI features).
     */
    @SuppressLint("ServiceCast")
    fun isOnline(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val nw = cm.activeNetwork ?: return false
            val cap = cm.getNetworkCapabilities(nw) ?: return false
            cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            false
        }
    }
}
