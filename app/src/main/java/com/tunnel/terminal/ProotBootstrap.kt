package com.tunnel.terminal

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * ProotBootstrap — Mengelola instalasi Linux environment (proot + rootfs Ubuntu).
 *
 * Phase 37 (proot/Ubuntu): Semua data disimpan di context.filesDir/linux/ — private
 * ke app, tidak butuh permission storage apa pun.
 *
 * Alur instalasi:
 *  1. Salin binary `proot` dari assets APK ke filesDir/linux/proot (assets tidak
 *     bisa di-exec langsung, harus disalin dulu + setExecutable).
 *  2. Cek storage cukup (minimal ~1.5GB untuk rootfs + apt cache).
 *  3. Download rootfs Ubuntu Base (tarball .tar.gz) dari cdimage.ubuntu.com.
 *  4. Ekstrak tarball via /system/bin/tar (toybox bawaan Android).
 *  5. Setup /etc/resolv.conf di rootfs supaya DNS jalan (`apt update` bisa resolve).
 *  6. Tulis marker `.installed` supaya `isInstalled` true di launch berikutnya.
 *
 * Directory layout (setelah install sukses):
 *   context.filesDir/linux/
 *     ├── proot                   (executable binary, dari assets)
 *     ├── ubuntu/                 (rootfs hasil ekstrak tarball)
 *     │   ├── bin/bash
 *     │   ├── usr/bin/apt
 *     │   ├── etc/resolv.conf     (di-setup oleh setupResolvConf)
 *     │   └── ...
 *     └── .installed              (marker file: timestamp instalasi)
 *
 * Catatan Play Store: Fitur ini mendownload+mengeksekusi binary native saat runtime
 * → melanggar kebijakan Play Store. Distribusikan lewat GitHub Releases/F-Droid saja.
 */
class ProotBootstrap(private val context: Context) {

    companion object {
        private const val TAG = "ProotBootstrap"

        /**
         * URL rootfs Ubuntu Base 24.04 (arm64). Format URL konsisten antar versi —
         * kalau perlu ganti versi, cek https://cdimage.ubuntu.com/ubuntu-base/releases/.
         *
         * Catatan: ini cuma contoh untuk arm64. Untuk device x86_64 (emulator),
         * ganti `arm64` → `amd64` di URL + nama file. TODO: deteksi ABI saat runtime.
         */
        const val ROOTFS_URL_ARM64 =
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.2-base-arm64.tar.gz"
        const val ROOTFS_URL_AMD64 =
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.2-base-amd64.tar.gz"

        /** Minimal free space yang dibutuhkan: 1.5GB (rootfs + apt cache + tool dev). */
        const val MIN_FREE_BYTES = 1_500L * 1024 * 1024

        /** Nama binary proot di folder assets. */
        const val ASSET_PROOT_PATH = "proot/proot"
    }

    /** Base directory untuk seluruh instalasi Linux environment. */
    val baseDir = File(context.filesDir, "linux")

    /** Directory rootfs Ubuntu hasil ekstrak. */
    val rootfsDir = File(baseDir, "ubuntu")

    /** Binary proot (disalin dari assets, executable). */
    val prootBin = File(baseDir, "proot")

    /** Marker file — keberadaannya = instalasi sukses. */
    private val markerFile = File(baseDir, ".installed")

    /** Tarball sementara (dihapus setelah ekstrak). */
    private val rootfsTarball = File(baseDir, "rootfs.tar.gz")

    /** True jika instalasi sudah pernah selesai (marker file ada + proot executable). */
    val isInstalled: Boolean
        get() = markerFile.exists() && prootBin.exists() && prootBin.canExecute() && rootfsDir.isDirectory

    /** Pilih URL rootfs berdasarkan ABI device. */
    private fun pickRootfsUrl(): String {
        /* Cek ABI utama device. arm64-v8a → arm64 rootfs; x86_64 → amd64 rootfs. */
        val abis = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            android.os.Build.SUPPORTED_ABIS.toList()
        } else {
            listOf(android.os.Build.CPU_ABI)
        }
        return when {
            abis.any { it.equals("arm64-v8a", true) } -> ROOTFS_URL_ARM64
            abis.any { it.equals("x86_64", true) } -> ROOTFS_URL_AMD64
            else -> {
                Log.w(TAG, "Unsupported ABI $abis — fallback ke arm64 rootfs (kemungkinan tidak akan jalan)")
                ROOTFS_URL_ARM64
            }
        }
    }

    /** Progress callback: (stage, persen 0-100). */
    fun interface ProgressListener {
        fun onProgress(stage: String, percent: Int)
    }

    /**
     * Jalankan seluruh proses instalasi. Panggil dari coroutine (Dispatchers.IO) —
     * ini melakukan I/O jaringan dan disk yang berat, JANGAN panggil dari main thread.
     *
     * Throws IllegalStateException pada kegagalan (asset hilang, storage tidak cukup,
     * download gagal, ekstrak gagal). Caller tangani via try/catch + tampilkan ke UI.
     */
    suspend fun install(listener: ProgressListener) {
        baseDir.mkdirs()

        // 1. Salin proot dari assets APK ke storage app. Assets tidak executable
        //    langsung, harus disalin ke filesystem biasa dulu + setExecutable.
        listener.onProgress("Menyiapkan proot binary", 0)
        val assetProotBytes = try {
            context.assets.open(ASSET_PROOT_PATH).use { it.readBytes() }
        } catch (e: Exception) {
            throw IllegalStateException(
                "Binary proot tidak ditemukan di assets APK ($ASSET_PROOT_PATH). " +
                "Letakkan binary proot di app/src/main/assets/proot/proot sebelum build. " +
                "Lihat app/src/main/assets/proot/README.md untuk cara mendapatkannya.",
                e
            )
        }
        FileOutputStream(prootBin).use { it.write(assetProotBytes) }
        if (!prootBin.setExecutable(true, true)) {
            throw IllegalStateException(
                "Gagal set executable permission pada proot binary. " +
                "Device ini mungkin memblokir eksekusi binary dari app storage (W^X policy)."
            )
        }
        listener.onProgress("Menyiapkan proot binary", 100)

        // 2. Cek storage cukup (perlu minimal 1.5GB bebas untuk rootfs + apt cache).
        val freeBytes = baseDir.usableSpace
        if (freeBytes < MIN_FREE_BYTES) {
            throw IllegalStateException(
                "Storage tidak cukup. Butuh minimal ${MIN_FREE_BYTES / 1024 / 1024}MB, " +
                "tersisa ${freeBytes / 1024 / 1024}MB. " +
                "Bebas kan storage atau uninstall Linux environment yang lama."
            )
        }

        // 3. Download rootfs tarball dengan progress.
        val rootfsUrl = pickRootfsUrl()
        downloadWithProgress(rootfsUrl, rootfsTarball) { percent ->
            listener.onProgress("Mengunduh Ubuntu rootfs", percent)
        }

        // 4. Ekstrak. Pakai tar bawaan Android (toybox) via ProcessBuilder —
        //    lebih simpel & cepat daripada implementasi tar parser sendiri di Kotlin.
        rootfsDir.mkdirs()
        listener.onProgress("Mengekstrak rootfs", 0)
        val extractProcess = ProcessBuilder(
            "/system/bin/tar", "-xzf", rootfsTarball.absolutePath,
            "-C", rootfsDir.absolutePath
        ).redirectErrorStream(true).start()

        // Stream output untuk hindari process block jika stdout pipe penuh.
        val processOutput = StringBuilder()
        val outputReader = Thread {
            try {
                extractProcess.inputStream.bufferedReader().use { r ->
                    var line = r.readLine()
                    while (line != null) {
                        processOutput.appendLine(line)
                        line = r.readLine()
                    }
                }
            } catch (_: Exception) { /* ignore — process exit akan handle */ }
        }.apply { isDaemon = true; start() }

        // Tunggu proses selesai dengan timeout 10 menit (rootfs besar bisa lama).
        val finished = extractProcess.waitFor(10, java.util.concurrent.TimeUnit.MINUTES)
        if (!finished) {
            extractProcess.destroyForcibly()
            throw IllegalStateException("Ekstraksi rootfs timeout (>10 menit). Mungkin storage lambat atau rusak.")
        }
        outputReader.join(2000)
        val exitCode = extractProcess.exitValue()
        if (exitCode != 0) {
            throw IllegalStateException(
                "Ekstraksi rootfs gagal (exit $exitCode): ${processOutput.toString().take(500)}"
            )
        }
        listener.onProgress("Mengekstrak rootfs", 100)

        // 5. Setup awal: DNS, supaya apt update bisa resolve hostname.
        setupResolvConf()

        // 6. Bersihkan tarball (sudah tidak perlu, hemat storage).
        rootfsTarball.delete()

        // 7. Tulis marker.
        markerFile.writeText(System.currentTimeMillis().toString())
        Log.i(TAG, "Instalasi Ubuntu proot selesai di ${rootfsDir.absolutePath}")
    }

    /**
     * Setup /etc/resolv.conf di rootfs supaya DNS resolve jalan.
     * Phase 39: Call ini tiap kali sesi Ubuntu dibuka juga (bukan cuma saat install)
     * supaya kalau user pindah jaringan (WiFi↔data), DNS tetap fresh.
     */
    fun setupResolvConf() {
        val resolvConf = File(rootfsDir, "etc/resolv.conf")
        resolvConf.parentFile?.mkdirs()
        resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
    }

    /**
     * Download file dengan progress callback. Throws IllegalStateException jika gagal.
     * Stream langsung ke disk (tidak load seluruh file ke memory) supaya rootfs
     * 30-60MB tidak OOM.
     */
    private fun downloadWithProgress(url: String, dest: File, onProgress: (Int) -> Unit) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30000
            readTimeout = 60000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            connection.connect()
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IllegalStateException("HTTP $responseCode saat download $url")
            }
            val totalBytes = connection.contentLength.toLong()  /* bisa -1 jika unknown */
            var downloadedBytes = 0L
            var lastReportedPercent = -1
            connection.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    val buffer = ByteArray(64 * 1024)  /* 64KB chunks */
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalBytes > 0) {
                            val percent = ((downloadedBytes * 100) / totalBytes).toInt()
                            if (percent != lastReportedPercent) {
                                onProgress(percent)
                                lastReportedPercent = percent
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            /* Hapus file parsial supaya retry bersih. */
            try { if (dest.exists()) dest.delete() } catch (_: Exception) {}
            throw IllegalStateException("Download gagal: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }

    /** Hapus seluruh instalasi (untuk fitur "Uninstall Linux Environment"). */
    fun uninstall() {
        try {
            baseDir.deleteRecursively()
            Log.i(TAG, "Instalasi Linux environment dihapus")
        } catch (e: Exception) {
            Log.w(TAG, "Gagal hapus instalasi: ${e.message}")
        }
    }

    /** Cek free space di baseDir, return dalam MB. */
    fun getFreeSpaceMb(): Long = baseDir.usableSpace / 1024 / 1024

    /** Total ukuran rootfs dalam MB (untuk info di Settings). */
    fun getRootfsSizeMb(): Long {
        if (!rootfsDir.isDirectory) return 0
        return rootfsDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } / 1024 / 1024
    }
}
