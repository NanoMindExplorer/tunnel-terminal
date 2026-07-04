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
 * Phase 39.1: Updated untuk handle shared library dependencies proot:
 *  - libtalloc.so.2 (dari package libtalloc Termux)
 *  - libandroid-shmem.so (dari package libandroid-shmem Termux)
 * Library-library ini di-bundle di assets/proot/lib/ dan disalin ke baseDir/lib/
 * saat install. ProotShellExecutor men-set LD_LIBRARY_PATH ke baseDir/lib/.
 *
 * Alur instalasi:
 *  1. Salin binary `proot` dari assets APK ke filesDir/linux/proot (assets tidak
 *     bisa di-exec langsung, harus disalin dulu + setExecutable).
 *  2. Salin library `libtalloc.so.2` + `libandroid-shmem.so` dari assets ke filesDir/linux/lib/.
 *  3. Cek storage cukup (minimal ~1.5GB untuk rootfs + apt cache).
 *  4. Download rootfs Ubuntu Base (tarball .tar.gz) dari cdimage.ubuntu.com.
 *  5. Ekstrak tarball via /system/bin/tar (toybox bawaan Android).
 *  6. Setup /etc/resolv.conf di rootfs supaya DNS jalan (`apt update` bisa resolve).
 *  7. Tulis marker `.installed` supaya `isInstalled` true di launch berikutnya.
 *
 * Directory layout (setelah install sukses):
 *   context.filesDir/linux/
 *     ├── proot                   (executable binary, dari assets)
 *     ├── lib/
 *     │   ├── libtalloc.so.2      (shared lib untuk proot)
 *     │   └── libandroid-shmem.so (shared lib untuk proot)
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
         * Phase 40 fix (A2): URL rootfs Ubuntu Base — daftar fallback.
         *
         * OLD BUG: Hanya 1 URL hardcode (24.04.2) yang sudah 404 di server Ubuntu.
         * Ubuntu menghapus point release lama dari cdimage server — hanya 24.04.3
         * dan 24.04.4 yang tersedia saat audit (4 Jul 2026).
         *
         * FIX: Daftar URL berurutan, download mencoba satu per satu sampai ada
         * yang berhasil (HTTP 200). Kalau semua gagal, throw error yang jelas.
         */
        val ROOTFS_URLS_ARM64 = listOf(
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"
        )
        val ROOTFS_URLS_AMD64 = listOf(
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-amd64.tar.gz",
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-amd64.tar.gz"
        )

        const val MIN_FREE_BYTES = 1_500L * 1024 * 1024

        const val ASSET_PROOT_PATH = "proot/proot"
        /** Folder di assets yang berisi shared libraries proot. */
        const val ASSET_PROOT_LIB_DIR = "proot/lib"
        /** Library yang dibutuhkan proot (urutan penting untuk loading). */
        val PROOT_LIBS = listOf("libtalloc.so.2", "libandroid-shmem.so")
    }

    val baseDir = File(context.filesDir, "linux")
    val rootfsDir = File(baseDir, "ubuntu")
    val prootBin = File(baseDir, "proot")
    /** Directory untuk shared libraries proot (libtalloc, libandroid-shmem). */
    val libDir = File(baseDir, "lib")
    private val markerFile = File(baseDir, ".installed")
    private val rootfsTarball = File(baseDir, "rootfs.tar.gz")

    val isInstalled: Boolean
        get() = markerFile.exists() && prootBin.exists() && prootBin.canExecute() && rootfsDir.isDirectory

    /**
     * Pilih daftar URL rootfs berdasarkan ABI device.
     * Phase 40 fix (H4): Untuk ABI 32-bit, throw error (tidak ada rootfs 32-bit yang available).
     */
    private fun pickRootfsUrls(): List<String> {
        val abis = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            android.os.Build.SUPPORTED_ABIS.toList()
        } else {
            listOf(android.os.Build.CPU_ABI)
        }
        return when {
            abis.any { it.equals("arm64-v8a", true) } -> ROOTFS_URLS_ARM64
            abis.any { it.equals("x86_64", true) } -> ROOTFS_URLS_AMD64
            else -> throw IllegalStateException(
                "Device 32-bit ($abis) tidak didukung. Linux Environment butuh device 64-bit (arm64-v8a atau x86_64)."
            )
        }
    }

    fun interface ProgressListener {
        fun onProgress(stage: String, percent: Int)
    }

    suspend fun install(listener: ProgressListener) {
        baseDir.mkdirs()

        // 1. Salin proot binary dari assets APK ke storage app.
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

        // 2. Salin shared libraries proot (libtalloc.so.2, libandroid-shmem.so).
        listener.onProgress("Menyiapkan proot libraries", 0)
        libDir.mkdirs()
        val missingLibs = mutableListOf<String>()
        for (libName in PROOT_LIBS) {
            try {
                context.assets.open("$ASSET_PROOT_LIB_DIR/$libName").use { input ->
                    FileOutputStream(File(libDir, libName)).use { output -> input.copyTo(output) }
                }
                Log.i(TAG, "Library $libName disalin ke ${libDir.absolutePath}")
            } catch (e: Exception) {
                Log.w(TAG, "Library $libName tidak ditemukan di assets — proot mungkin akan gagal jalan: ${e.message}")
                missingLibs.add(libName)
                /* Phase 43 fix (MED-07): Track lib yang missing — akan di-warning ke user
                 * SEBELUM proot dijalankan, bukan menunggu error generik "Exec format error". */
            }
        }
        /* Phase 43 fix (MED-07): Tulis file manifest lib yang missing supaya
         * ProotShellExecutor bisa tampilkan pesan jelas saat start gagal. */
        if (missingLibs.isNotEmpty()) {
            File(baseDir, ".missing_libs").writeText(missingLibs.joinToString("\n"))
            Log.w(TAG, "Missing libs: $missingLibs — proot mungkin tidak akan jalan")
        } else {
            File(baseDir, ".missing_libs").delete()
        }
        listener.onProgress("Menyiapkan proot libraries", 100)

        // 3. Cek storage cukup.
        val freeBytes = baseDir.usableSpace
        if (freeBytes < MIN_FREE_BYTES) {
            throw IllegalStateException(
                "Storage tidak cukup. Butuh minimal ${MIN_FREE_BYTES / 1024 / 1024}MB, " +
                "tersisa ${freeBytes / 1024 / 1024}MB. " +
                "Bebas kan storage atau uninstall Linux environment yang lama."
            )
        }

        // 4. Download rootfs tarball.
        // Phase 40 fix (A2): Coba daftar URL fallback sampai ada yang berhasil.
        val rootfsUrls = pickRootfsUrls()
        var downloadSuccess = false
        var lastError: Exception? = null
        for ((idx, url) in rootfsUrls.withIndex()) {
            try {
                listener.onProgress("Mengunduh Ubuntu rootfs (mirror ${idx + 1}/${rootfsUrls.size})", 0)
                downloadWithProgress(url, rootfsTarball) { percent ->
                    listener.onProgress("Mengunduh Ubuntu rootfs", percent)
                }
                downloadSuccess = true
                break
            } catch (e: Exception) {
                Log.w(TAG, "Download gagal dari $url: ${e.message}, coba mirror berikutnya...")
                lastError = e
                try { if (rootfsTarball.exists()) rootfsTarball.delete() } catch (_: Exception) {}
            }
        }
        if (!downloadSuccess) {
            throw IllegalStateException(
                "Semua mirror download gagal (${rootfsUrls.size} URL dicoba). " +
                "Error terakhir: ${lastError?.message ?: "unknown"}"
            )
        }

        // 5. Ekstrak via tar bawaan Android (toybox).
        rootfsDir.mkdirs()
        listener.onProgress("Mengekstrak rootfs", 0)
        val extractProcess = ProcessBuilder(
            "/system/bin/tar", "-xzf", rootfsTarball.absolutePath,
            "-C", rootfsDir.absolutePath
        ).redirectErrorStream(true).start()

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
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }

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

        // 6. Setup DNS.
        setupResolvConf()

        // 6.5. Phase 46 (Pilar 3): Setup non-interactive apt environment.
        // Cegah prompt interaktif dari akarnya — jangan cuma andalkan deteksi idle-timeout.
        setupNonInteractiveApt()

        // 7. Phase 40 fix (H5): Validate proot binary bisa di-exec sebelum tulis marker.
        // Kalau binary corrupt / wrong ABI / missing libs, error di sini (bukan saat start session).
        listener.onProgress("Memvalidasi proot binary", 0)
        try {
            val validateProcess = ProcessBuilder(
                prootBin.absolutePath, "--version"
            ).redirectErrorStream(true).start()
            val validateOutput = validateProcess.inputStream.bufferedReader().readText()
            val validateExit = validateProcess.waitFor()
            if (validateExit != 0 || !validateOutput.contains("proot", ignoreCase = true)) {
                baseDir.deleteRecursively()
                throw IllegalStateException(
                    "Binary proot tidak valid atau tidak bisa di-exec di device ini. " +
                    "Output: ${validateOutput.take(200)}"
                )
            }
        } catch (e: IllegalStateException) { throw e }
        catch (e: Exception) {
            Log.w(TAG, "Validasi proot --version gagal (non-fatal): ${e.message}")
            /* Non-fatal — beberapa proot build mungkin tidak support --version flag.
             * Lanjutkan install; error akan muncul saat start session kalau benar-benar broken. */
        }
        listener.onProgress("Memvalidasi proot binary", 100)

        // 8. Phase 40 fix (H9): Tulis marker SEBELUM hapus tarball.
        // OLD BUG: hapus tarball dulu, lalu tulis marker. Kalau app crash di antara,
        // tarball hilang tapi marker belum ada → user harus re-download.
        // FIX: tulis marker dulu (akui install sukses), baru hapus tarball (best-effort).
        markerFile.writeText(System.currentTimeMillis().toString())
        Log.i(TAG, "Instalasi Ubuntu proot selesai di ${rootfsDir.absolutePath}")

        // 9. Bersihkan tarball (best-effort, tidak fatal kalau gagal).
        try { rootfsTarball.delete() } catch (_: Exception) {}
    }

    fun setupResolvConf() {
        val resolvConf = File(rootfsDir, "etc/resolv.conf")
        resolvConf.parentFile?.mkdirs()
        resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
    }

    /**
     * Phase 46 (Pilar 3): Setup non-interactive apt environment.
     *
     * Cegah prompt interaktif dari akarnya — jangan cuma andalkan deteksi idle-timeout
     * di MarkerExecutor (yang cuma jaring pengaman).
     *
     * Dua flag yang perlu jalan bersamaan:
     * 1. DEBIAN_FRONTEND=noninteractive — tekan dialog konfigurasi debconf
     *    (mis. pemilihan region tzdata, konfigurasi mysql-server, dll).
     * 2. APT_LISTCHANGES_FRONTEND=none — tekan output apt-listchanges yang bisa interaktif.
     *
     * CATATAN: ini TIDAK menekan prompt "Do you want to continue? [Y/n]" dari apt-get
     * sendiri — itu perlu flag -y terpisah di command-nya (sudah di-instruksikan ke AI
     * di system prompt Pilar 3).
     *
     * Juga preseed timezone langsung (etc/timezone), supaya paket seperti tzdata
     * tidak pernah menampilkan dialog pilih region/kota sama sekali.
     */
    private fun setupNonInteractiveApt() {
        try {
            // Profile script — di-source otomatis oleh bash login shell.
            val profileScript = File(rootfsDir, "etc/profile.d/tunnel-noninteractive.sh")
            profileScript.parentFile?.mkdirs()
            val tz = java.util.TimeZone.getDefault().id
            profileScript.writeText("""
                # Phase 46 (Pilar 3): Non-interactive apt environment
                # Setup oleh Tunnel Terminal untuk mencegah prompt interaktif
                export DEBIAN_FRONTEND=noninteractive
                export APT_LISTCHANGES_FRONTEND=none
                export TZ=$tz
            """.trimIndent() + "\n")
            profileScript.setExecutable(false)

            // Preseed timezone langsung
            val timezoneFile = File(rootfsDir, "etc/timezone")
            timezoneFile.parentFile?.mkdirs()
            timezoneFile.writeText(tz + "\n")

            Log.i(TAG, "Non-interactive apt environment setup: DEBIAN_FRONTEND=noninteractive, TZ=$tz")
        } catch (e: Exception) {
            Log.w(TAG, "Gagal setup non-interactive apt: ${e.message} — non-fatal, apt tetap jalan tapi mungkin prompt interaktif")
        }
    }

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
            val totalBytes = connection.contentLength.toLong()
            var downloadedBytes = 0L
            var lastReportedPercent = -1
            connection.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    val buffer = ByteArray(64 * 1024)
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
            try { if (dest.exists()) dest.delete() } catch (_: Exception) {}
            throw IllegalStateException("Download gagal: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Hapus seluruh instalasi (untuk fitur "Uninstall Linux Environment").
     * Phase 40 fix (M8): Pakai rm -rf via ProcessBuilder (lebih cepat dari Kotlin's
     * deleteRecursively untuk ribuan file di rootfs Ubuntu).
     */
    fun uninstall() {
        try {
            // Pakai rm -rf via toybox (lebih cepat + reliable untuk file tree besar)
            val process = ProcessBuilder(
                "/system/bin/rm", "-rf", baseDir.absolutePath
            ).redirectErrorStream(true).start()
            val exit = process.waitFor()
            if (exit != 0) {
                // Fallback ke Kotlin's deleteRecursively
                baseDir.deleteRecursively()
            }
            Log.i(TAG, "Instalasi Linux environment dihapus")
        } catch (e: Exception) {
            Log.w(TAG, "Gagal hapus instalasi: ${e.message}")
            // Last resort fallback
            try { baseDir.deleteRecursively() } catch (_: Exception) {}
        }
    }

    fun getFreeSpaceMb(): Long = baseDir.usableSpace / 1024 / 1024

    fun getRootfsSizeMb(): Long {
        if (!rootfsDir.isDirectory) return 0
        return rootfsDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } / 1024 / 1024
    }
}
