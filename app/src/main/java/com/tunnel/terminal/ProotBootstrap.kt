package com.tunnel.terminal

import android.content.Context
import android.os.StatFs
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * ProotBootstrap — Mengelola instalasi Linux environment (proot + rootfs Ubuntu).
 *
 * Wave-22: Robust download pipeline — IO dispatcher (caller), multi-mirror,
 * Range resume, Content-LengthLong, gzip magic check, SHA256 fail-closed on
 * *mismatch*, multi-strategy extract.
 *
 * Wave-26 (v8.4.1): Install after 100% download often failed on extract /
 * permissions. Fixes:
 *  - Primary extract via pure-Java tar.gz (no toybox mknod/hardlink failures)
 *  - proot --link2symlink + system tar as fallback
 *  - Accept system tar non-zero exit if bash+etc present (device-node warnings)
 *  - Finalize rootfs + proot/lib file modes (owner-writable dirs, sticky tmp)
 *  - Do not delete valid tarball when SHA256SUMS is merely unreachable
 *  - Accurate free-space check via StatFs on app filesDir
 *
 * Layout: context.filesDir/linux/{proot,lib/,ubuntu/,.installed}
 * Storage: **app-private** — no SAF / MANAGE_EXTERNAL_STORAGE required for Ubuntu.
 */
class ProotBootstrap(private val context: Context) {

    val appContext: Context get() = context.applicationContext

    companion object {
        private const val TAG = "ProotBootstrap"
        private const val USER_AGENT =
            "TunnelTerminal/8.4.1 (Android; Ubuntu-Rootfs-Bootstrap)"

        val ROOTFS_URLS_ARM64 = listOf(
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.3/release/ubuntu-base-24.04.3-base-arm64.tar.gz",
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"
        )
        val ROOTFS_URLS_AMD64 = listOf(
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-amd64.tar.gz",
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-amd64.tar.gz",
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.3/release/ubuntu-base-24.04.3-base-amd64.tar.gz",
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-amd64.tar.gz"
        )

        /** Compressed rootfs ~29MB; require at least 15MB to reject HTML error pages. */
        const val MIN_TARBALL_BYTES = 15L * 1024 * 1024
        /** Free space for tarball (~30MB) + extract (~150–250MB) + apt headroom. */
        const val MIN_FREE_BYTES = 800L * 1024 * 1024

        const val ASSET_PROOT_PATH = "proot/proot"
        const val ASSET_PROOT_LIB_DIR = "proot/lib"
        val PROOT_LIBS = listOf("libtalloc.so.2", "libandroid-shmem.so")

        private const val MAX_RETRIES_PER_URL = 3
        private const val CONNECT_TIMEOUT_MS = 45_000
        private const val READ_TIMEOUT_MS = 300_000
    }

    val baseDir = File(context.filesDir, "linux")
    val rootfsDir = File(baseDir, "ubuntu")
    val prootBin = File(baseDir, "proot")
    val libDir = File(baseDir, "lib")
    private val markerFile = File(baseDir, ".installed")
    private val rootfsTarball = File(baseDir, "rootfs.tar.gz")
    private val downloadMeta = File(baseDir, ".rootfs_download_url")

    val isInstalled: Boolean
        get() = markerFile.exists() &&
            prootBin.exists() &&
            prootBin.canExecute() &&
            TarGzipRootfsExtractor.rootfsLooksValid(rootfsDir)

    private fun pickRootfsUrls(): List<String> {
        @Suppress("DEPRECATION")
        val abis = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            android.os.Build.SUPPORTED_ABIS.toList()
        } else {
            listOf(android.os.Build.CPU_ABI)
        }
        return when {
            abis.any { it.equals("arm64-v8a", true) } -> ROOTFS_URLS_ARM64
            abis.any { it.equals("x86_64", true) } -> ROOTFS_URLS_AMD64
            else -> throw IllegalStateException(
                "Device 32-bit ($abis) tidak didukung. " +
                    "Linux Environment butuh arm64-v8a atau x86_64."
            )
        }
    }

    fun interface ProgressListener {
        fun onProgress(stage: String, percent: Int)
    }

    /**
     * Install Ubuntu rootfs. **Must be called from a background dispatcher (IO)** —
     * performs network + disk I/O. Caller should hop to Main for UI updates in [listener].
     */
    fun install(listener: ProgressListener) {
        ensureInstallDirs()

        // 1. proot binary
        listener.onProgress("Menyiapkan proot binary", 0)
        installProotBinary()
        listener.onProgress("Menyiapkan proot binary", 100)

        // 2. shared libraries
        listener.onProgress("Menyiapkan proot libraries", 0)
        installProotLibraries()
        listener.onProgress("Menyiapkan proot libraries", 100)

        // 3. free space (app-private filesDir — no external storage permission)
        val freeBytes = availableBytes()
        if (freeBytes in 1 until MIN_FREE_BYTES) {
            throw IllegalStateException(
                "Storage app tidak cukup untuk Ubuntu. " +
                    "Butuh ≥${MIN_FREE_BYTES / 1024 / 1024}MB bebas di penyimpanan internal, " +
                    "tersisa ${freeBytes / 1024 / 1024}MB. " +
                    "Hapus app/cache lain atau uninstall rootfs lama, lalu coba lagi. " +
                    "(Ubuntu disimpan di folder privat app — tidak butuh izin Download/SAF.)"
            )
        }
        Log.i(TAG, "Free space for install: ${freeBytes / 1024 / 1024}MB at ${baseDir.absolutePath}")

        // 4. download with multi-mirror + resume
        val rootfsUrls = pickRootfsUrls()
        var downloadSuccess = false
        var lastError: Exception? = null
        var usedUrl: String? = null

        for ((idx, url) in rootfsUrls.withIndex()) {
            for (attempt in 1..MAX_RETRIES_PER_URL) {
                try {
                    listener.onProgress(
                        "Unduh rootfs (${idx + 1}/${rootfsUrls.size}, coba $attempt/$MAX_RETRIES_PER_URL)",
                        0
                    )
                    val prevUrl = try {
                        downloadMeta.readText().trim()
                    } catch (_: Exception) {
                        ""
                    }
                    if (prevUrl != url && rootfsTarball.exists()) {
                        rootfsTarball.delete()
                    }
                    downloadMeta.writeText(url)
                    downloadWithProgress(url, rootfsTarball) { percent ->
                        listener.onProgress("Mengunduh Ubuntu rootfs", percent)
                    }
                    validateTarball(rootfsTarball)
                    downloadSuccess = true
                    usedUrl = url
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Download gagal $url attempt=$attempt: ${e.message}")
                    lastError = e
                    if (e !is java.net.SocketTimeoutException && rootfsTarball.exists()) {
                        try {
                            rootfsTarball.delete()
                        } catch (_: Exception) {
                        }
                    }
                    if (attempt < MAX_RETRIES_PER_URL) {
                        try {
                            Thread.sleep(1500L * attempt)
                        } catch (_: InterruptedException) {
                        }
                    }
                }
            }
            if (downloadSuccess) break
        }
        if (!downloadSuccess || usedUrl == null) {
            throw IllegalStateException(formatDownloadError(lastError, rootfsUrls.size))
        }
        listener.onProgress("Mengunduh Ubuntu rootfs", 100)

        // 4.5 SHA256 — mismatch deletes tarball; network/SUMS miss keeps file for retry
        listener.onProgress("Memverifikasi SHA256 rootfs", 0)
        verifyRootfsSha256(rootfsTarball, usedUrl, rootfsUrls, listener)
        listener.onProgress("Memverifikasi SHA256 rootfs", 100)

        // 5. extract
        if (rootfsDir.exists()) {
            try {
                rootfsDir.deleteRecursively()
            } catch (_: Exception) {
            }
        }
        rootfsDir.mkdirs()
        chmodPrivateDir(rootfsDir, 0o755)
        listener.onProgress("Mengekstrak rootfs", 0)
        extractRootfs(rootfsTarball, rootfsDir, listener)

        if (!TarGzipRootfsExtractor.rootfsLooksValid(rootfsDir)) {
            throw IllegalStateException(
                "Ekstraksi selesai tapi rootfs tidak valid (bash/etc hilang). " +
                    "Path: ${rootfsDir.absolutePath}. Coba Uninstall lalu Install ulang."
            )
        }
        listener.onProgress("Mengekstrak rootfs", 100)

        // 6. permissions + DNS + noninteractive apt
        listener.onProgress("Mengatur izin & layout rootfs", 0)
        TarGzipRootfsExtractor.finalizeRootfsPermissions(rootfsDir, prootBin, libDir)
        chmodPrivateDir(baseDir, 0o700)
        chmodPrivateDir(rootfsDir, 0o755)
        chmodPrivateDir(libDir, 0o755)
        setupResolvConf()
        setupNonInteractiveApt()
        listener.onProgress("Mengatur izin & layout rootfs", 100)

        // 7. validate proot
        listener.onProgress("Memvalidasi proot binary", 0)
        validateProotBinary()
        listener.onProgress("Memvalidasi proot binary", 100)

        // 8. marker then delete tarball
        markerFile.writeText(
            "version=8.4.1\n" +
                "installedAt=${System.currentTimeMillis()}\n" +
                "rootfs=${rootfsDir.absolutePath}\n" +
                "source=$usedUrl\n"
        )
        try {
            downloadMeta.delete()
        } catch (_: Exception) {
        }
        try {
            rootfsTarball.delete()
        } catch (_: Exception) {
        }
        Log.i(TAG, "Ubuntu proot install OK → ${rootfsDir.absolutePath}")
        listener.onProgress("Selesai", 100)
    }

    private fun ensureInstallDirs() {
        baseDir.mkdirs()
        libDir.mkdirs()
        chmodPrivateDir(baseDir, 0o700)
        chmodPrivateDir(libDir, 0o755)
    }

    private fun chmodPrivateDir(dir: File, mode: Int) {
        dir.mkdirs()
        TarGzipRootfsExtractor.chmodBestEffort(dir, mode)
    }

    private fun installProotBinary() {
        val assetProotBytes = try {
            context.assets.open(ASSET_PROOT_PATH).use { it.readBytes() }
        } catch (e: Exception) {
            throw IllegalStateException(
                "Binary proot tidak ditemukan di assets APK ($ASSET_PROOT_PATH). " +
                    "Gunakan build Full dari GitHub Releases (bukan Play Store).",
                e
            )
        }
        if (assetProotBytes.size < 10_000) {
            throw IllegalStateException(
                "Binary proot di assets terlalu kecil (${assetProotBytes.size} bytes) — APK corrupt?"
            )
        }
        FileOutputStream(prootBin).use { it.write(assetProotBytes) }
        TarGzipRootfsExtractor.chmodBestEffort(prootBin, 0o755)
        if (!prootBin.setExecutable(true, false)) {
            /* Some OEMs return false even when mode is OK — verify canExecute. */
            if (!prootBin.canExecute()) {
                throw IllegalStateException(
                    "Gagal set executable pada proot di ${prootBin.absolutePath}. " +
                        "Device mungkin memblokir exec dari app storage (W^X)."
                )
            }
        }
    }

    private fun installProotLibraries() {
        libDir.mkdirs()
        val missingLibs = mutableListOf<String>()
        for (libName in PROOT_LIBS) {
            try {
                context.assets.open("$ASSET_PROOT_LIB_DIR/$libName").use { input ->
                    val outFile = File(libDir, libName)
                    FileOutputStream(outFile).use { output -> input.copyTo(output) }
                    TarGzipRootfsExtractor.chmodBestEffort(outFile, 0o755)
                    outFile.setReadable(true, false)
                }
                Log.i(TAG, "Library $libName OK")
            } catch (e: Exception) {
                Log.w(TAG, "Library $libName missing: ${e.message}")
                missingLibs.add(libName)
            }
        }
        if (missingLibs.isNotEmpty()) {
            File(baseDir, ".missing_libs").writeText(missingLibs.joinToString("\n"))
        } else {
            File(baseDir, ".missing_libs").delete()
        }
    }

    private fun validateProotBinary() {
        try {
            val validateProcess = ProcessBuilder(prootBin.absolutePath, "--version")
                .redirectErrorStream(true)
                .apply {
                    environment()["LD_LIBRARY_PATH"] = libDir.absolutePath
                }
                .start()
            val validateOutput = validateProcess.inputStream.bufferedReader().readText()
            val validateExit = validateProcess.waitFor()
            if (validateExit != 0 && validateOutput.isBlank()) {
                Log.w(TAG, "proot --version exit=$validateExit (non-fatal)")
            } else {
                Log.i(TAG, "proot --version: ${validateOutput.take(120)}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Validasi proot non-fatal: ${e.message}")
        }
    }

    /** Free bytes on the filesystem that holds [baseDir] (app-private). */
    fun availableBytes(): Long {
        return try {
            if (!baseDir.exists()) baseDir.mkdirs()
            val st = StatFs(baseDir.absolutePath)
            st.availableBytes
        } catch (e: Exception) {
            Log.w(TAG, "StatFs failed: ${e.message}")
            baseDir.usableSpace
        }
    }

    private fun formatDownloadError(lastError: Exception?, mirrorCount: Int): String {
        val detail = lastError?.message ?: "unknown"
        return when (lastError) {
            is java.net.SocketTimeoutException ->
                "Download timeout. Rootfs ~29MB — butuh Wi‑Fi stabil. Detail: $detail"
            is java.net.UnknownHostException ->
                "DNS gagal resolve cdimage.ubuntu.com. Cek internet / airplane mode. Detail: $detail"
            is android.os.NetworkOnMainThreadException ->
                "Bug internal: download di main thread. Update app ke v8.3.1+."
            is java.io.IOException ->
                "Network error: $detail. Coba Wi‑Fi, nonaktifkan VPN, atau coba lagi."
            else ->
                "Download gagal setelah $mirrorCount mirror × $MAX_RETRIES_PER_URL percobaan. " +
                    "Terakhir: $detail"
        }
    }

    /** Reject HTML/error pages and truncated files. */
    private fun validateTarball(file: File) {
        if (!file.exists()) throw IllegalStateException("File rootfs tidak ada setelah download")
        val len = file.length()
        if (len < MIN_TARBALL_BYTES) {
            val head = try {
                FileInputStream(file).use { ins ->
                    val b = ByteArray(200)
                    val n = ins.read(b)
                    if (n > 0) String(b, 0, n) else ""
                }
            } catch (_: Exception) {
                ""
            }
            throw IllegalStateException(
                "Rootfs terlalu kecil ($len bytes, min $MIN_TARBALL_BYTES). " +
                    "Mungkin halaman error HTML, bukan tarball. Head: ${head.take(80)}"
            )
        }
        FileInputStream(file).use { ins ->
            val b0 = ins.read()
            val b1 = ins.read()
            if (b0 != 0x1f || b1 != 0x8b) {
                throw IllegalStateException(
                    "File bukan gzip (magic=${b0.toString(16)},${b1.toString(16)}). " +
                        "Server mungkin mengembalikan HTML/redirect page."
                )
            }
        }
    }

    private fun extractRootfs(tarball: File, dest: File, listener: ProgressListener) {
        val errors = mutableListOf<String>()

        /* Strategy A (preferred): pure Java stream extract — reliable on all Android. */
        try {
            listener.onProgress("Mengekstrak rootfs (Java tar)", 5)
            TarGzipRootfsExtractor.extract(tarball, dest) { pct ->
                val mapped = 5 + (pct * 70 / 100)
                listener.onProgress("Mengekstrak rootfs (Java tar)", mapped.coerceIn(5, 75))
            }
            if (TarGzipRootfsExtractor.rootfsLooksValid(dest)) {
                Log.i(TAG, "Extract OK via Java tar → ${dest.absolutePath}")
                return
            }
            errors.add("Java tar: rootfs tidak valid setelah ekstraksi")
        } catch (e: Exception) {
            Log.w(TAG, "Java tar extract failed: ${e.message}")
            errors.add("Java tar: ${e.message}")
            try {
                dest.deleteRecursively()
            } catch (_: Exception) {
            }
            dest.mkdirs()
        }

        /* Strategy B: proot --link2symlink + system tar (Termux-style). */
        if (prootBin.canExecute()) {
            try {
                listener.onProgress("Mengekstrak rootfs (proot+tar)", 78)
                val pb = ProcessBuilder(
                    prootBin.absolutePath,
                    "--link2symlink",
                    "/system/bin/tar",
                    "-xzf",
                    tarball.absolutePath,
                    "-C",
                    dest.absolutePath
                ).redirectErrorStream(true)
                pb.environment()["LD_LIBRARY_PATH"] = libDir.absolutePath
                pb.environment()["PROOT_TMP_DIR"] = File(baseDir, "tmp").apply { mkdirs() }.absolutePath
                val p = pb.start()
                val out = p.inputStream.bufferedReader().readText()
                val finished = p.waitFor(12, java.util.concurrent.TimeUnit.MINUTES)
                if (!finished) {
                    p.destroyForcibly()
                    errors.add("proot+tar timeout")
                } else if (TarGzipRootfsExtractor.rootfsLooksValid(dest)) {
                    Log.i(TAG, "Extract OK via proot+tar exit=${p.exitValue()} out=${out.take(120)}")
                    return
                } else {
                    errors.add("proot+tar exit=${p.exitValue()}: ${out.take(300)}")
                }
            } catch (e: Exception) {
                errors.add("proot+tar: ${e.message}")
            }
        }

        /* Strategy C: system tar -xzf — accept non-zero if rootfs valid (mknod warnings). */
        try {
            listener.onProgress("Mengekstrak rootfs (tar -xzf)", 85)
            val p = ProcessBuilder(
                "/system/bin/tar", "-xzf", tarball.absolutePath, "-C", dest.absolutePath
            ).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            val finished = p.waitFor(12, java.util.concurrent.TimeUnit.MINUTES)
            if (!finished) {
                p.destroyForcibly()
                errors.add("tar -xzf timeout")
            } else if (TarGzipRootfsExtractor.rootfsLooksValid(dest)) {
                Log.i(TAG, "Extract OK via tar -xzf exit=${p.exitValue()} (warnings ok)")
                return
            } else {
                errors.add("tar -xzf exit=${p.exitValue()}: ${out.take(300)}")
            }
        } catch (e: Exception) {
            errors.add("tar -xzf: ${e.message}")
        }

        /* Strategy D: Java gunzip → tar -xf plain */
        try {
            listener.onProgress("Mengekstrak rootfs (gunzip+tar)", 92)
            val plain = File(baseDir, "rootfs.tar")
            try {
                plain.delete()
            } catch (_: Exception) {
            }
            GZIPInputStream(BufferedInputStream(FileInputStream(tarball))).use { gis ->
                FileOutputStream(plain).use { out -> gis.copyTo(out) }
            }
            val p = ProcessBuilder(
                "/system/bin/tar", "-xf", plain.absolutePath, "-C", dest.absolutePath
            ).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            val finished = p.waitFor(12, java.util.concurrent.TimeUnit.MINUTES)
            val okValid = finished && TarGzipRootfsExtractor.rootfsLooksValid(dest)
            try {
                plain.delete()
            } catch (_: Exception) {
            }
            if (okValid) {
                Log.i(TAG, "Extract OK via gunzip+tar")
                return
            }
            errors.add("gunzip+tar: ${out.take(200)}")
        } catch (e: Exception) {
            errors.add("gunzip+java: ${e.message}")
        }

        throw IllegalStateException(
            "Semua metode ekstraksi gagal:\n" + errors.joinToString("\n")
        )
    }

    fun setupResolvConf() {
        val resolvConf = File(rootfsDir, "etc/resolv.conf")
        resolvConf.parentFile?.mkdirs()
        /* resolv.conf is often a symlink to systemd stub — replace with real file. */
        try {
            if (resolvConf.exists() || android.system.Os.readlink(resolvConf.absolutePath) != null) {
                resolvConf.delete()
            }
        } catch (_: Throwable) {
            try {
                resolvConf.delete()
            } catch (_: Exception) {
            }
        }
        resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 9.9.9.9\n")
        TarGzipRootfsExtractor.chmodBestEffort(resolvConf, 0o644)
    }

    private fun setupNonInteractiveApt() {
        try {
            val profileScript = File(rootfsDir, "etc/profile.d/tunnel-noninteractive.sh")
            profileScript.parentFile?.mkdirs()
            val tz = java.util.TimeZone.getDefault().id
            profileScript.writeText(
                """
                export DEBIAN_FRONTEND=noninteractive
                export APT_LISTCHANGES_FRONTEND=none
                export TZ=$tz
                """.trimIndent() + "\n"
            )
            TarGzipRootfsExtractor.chmodBestEffort(profileScript, 0o644)
            File(rootfsDir, "etc/timezone").writeText(tz + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "noninteractive apt setup: ${e.message}")
        }
    }

    private fun verifyRootfsSha256(
        tarball: File,
        usedUrl: String,
        allUrls: List<String>,
        listener: ProgressListener
    ) {
        if (!tarball.exists() || tarball.length() == 0L) {
            throw IllegalStateException("Rootfs tarball kosong — SHA256 tidak bisa dihitung")
        }
        val actualHex = sha256Hex(tarball)
        val fileName = usedUrl.substringAfterLast('/')
        val sumsCandidates = linkedSetOf(
            usedUrl.substringBeforeLast('/') + "/SHA256SUMS"
        )
        allUrls.forEach { u ->
            sumsCandidates.add(u.substringBeforeLast('/') + "/SHA256SUMS")
        }

        var lastErr: Exception? = null
        var sawMismatch = false
        for (sumsUrl in sumsCandidates) {
            try {
                listener.onProgress("SHA256SUMS ← ${sumsUrl.substringAfter("ubuntu-base/")}", 40)
                val sumsText = downloadText(sumsUrl)
                val expected = parseSha256Sums(sumsText, fileName)
                    ?: throw IllegalStateException("Tidak ada entry $fileName di $sumsUrl")
                if (!actualHex.equals(expected, ignoreCase = true)) {
                    sawMismatch = true
                    try {
                        tarball.delete()
                    } catch (_: Exception) {
                    }
                    throw IllegalStateException(
                        "SHA256 mismatch $fileName\nExpected: $expected\nActual:   $actualHex\n" +
                            "File dihapus — unduh ulang."
                    )
                }
                Log.i(TAG, "SHA256 OK $fileName $actualHex")
                File(baseDir, ".rootfs_sha256").writeText("$fileName $actualHex\nsource=$usedUrl\n")
                return
            } catch (e: Exception) {
                Log.w(TAG, "SHA256 via $sumsUrl: ${e.message}")
                lastErr = e
                if (sawMismatch || e.message?.contains("mismatch", ignoreCase = true) == true) {
                    throw e
                }
            }
        }
        /* Network / SUMS unavailable: keep tarball so user can retry without re-download. */
        throw IllegalStateException(
            "Verifikasi SHA256 tidak bisa diselesaikan (SUMS tidak terjangkau). " +
                "Tarball tetap disimpan — coba Install lagi dengan internet stabil. " +
                "Detail: ${lastErr?.message ?: "no SUMS"}"
        )
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            var n: Int
            while (input.read(buffer).also { n = it } != -1) {
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { b -> "%02x".format(b) }
    }

    private fun parseSha256Sums(sumsText: String, fileName: String): String? {
        for (line in sumsText.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val parts = trimmed.split(Regex("\\s+"), limit = 2)
            if (parts.size < 2) continue
            val hex = parts[0]
            val name = parts[1].removePrefix("*").trim()
            if (name == fileName || name.endsWith("/$fileName")) {
                if (hex.matches(Regex("[0-9a-fA-F]{64}"))) return hex.lowercase()
            }
        }
        return null
    }

    private fun openGetConnection(url: String, extraHeaders: Map<String, String> = emptyMap()): HttpURLConnection {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/x-gzip, application/gzip, application/octet-stream, */*")
            setRequestProperty("Accept-Encoding", "identity")
            extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return connection
    }

    private fun downloadText(url: String): String {
        val connection = openGetConnection(url)
        try {
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code untuk $url")
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadWithProgress(url: String, dest: File, onProgress: (Int) -> Unit) {
        val existing = if (dest.exists()) dest.length() else 0L
        val headers = if (existing > 0) mapOf("Range" to "bytes=$existing-") else emptyMap()
        val connection = openGetConnection(url, headers)
        try {
            connection.connect()
            val code = connection.responseCode
            val resume = code == 206
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code saat download $url")
            }
            if (existing > 0 && !resume && code == 200) {
                dest.delete()
            }

            val contentLen = connection.contentLengthLong
            val totalBytes = when {
                resume && contentLen > 0 -> existing + contentLen
                contentLen > 0 -> contentLen
                else -> -1L
            }
            var downloadedBytes = if (resume) existing else 0L
            var lastReportedPercent = -1
            var lastReportedBytes = downloadedBytes
            val startTime = System.currentTimeMillis()

            connection.inputStream.use { input ->
                RandomAccessFile(dest, "rw").use { raf ->
                    if (resume) raf.seek(existing) else raf.setLength(0)
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        raf.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalBytes > 0) {
                            val percent = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                            if (percent != lastReportedPercent) {
                                onProgress(percent)
                                lastReportedPercent = percent
                            }
                        } else if (downloadedBytes - lastReportedBytes >= 1024 * 1024) {
                            val est = ((downloadedBytes * 100) / (30L * 1024 * 1024)).toInt().coerceAtMost(99)
                            onProgress(est)
                            lastReportedBytes = downloadedBytes
                        }
                    }
                }
            }
            if (totalBytes <= 0) onProgress(100)
            val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
            val mb = downloadedBytes / 1024.0 / 1024.0
            Log.i(TAG, "Download OK ${"%.1f".format(mb)}MB in ${elapsed.toInt()}s resume=$resume url=$url")
        } catch (e: Exception) {
            throw when (e) {
                is java.net.SocketTimeoutException -> e
                is java.net.UnknownHostException -> e
                is java.io.IOException -> e
                else -> IllegalStateException("Download gagal: ${e.message}", e)
            }
        } finally {
            connection.disconnect()
        }
    }

    fun uninstall() {
        try {
            val process = ProcessBuilder("/system/bin/rm", "-rf", baseDir.absolutePath)
                .redirectErrorStream(true).start()
            if (process.waitFor() != 0) baseDir.deleteRecursively()
        } catch (_: Exception) {
            try {
                baseDir.deleteRecursively()
            } catch (_: Exception) {
            }
        }
    }

    fun getFreeSpaceMb(): Long = availableBytes() / 1024 / 1024

    fun getSeccompFallbackEnabled(): Boolean =
        context.getSharedPreferences("TunnelLinux", Context.MODE_PRIVATE)
            .getBoolean("proot_no_seccomp", false)

    fun setSeccompFallbackEnabled(enabled: Boolean) {
        context.getSharedPreferences("TunnelLinux", Context.MODE_PRIVATE)
            .edit().putBoolean("proot_no_seccomp", enabled).apply()
    }

    fun getRootfsSizeMb(): Long {
        if (!rootfsDir.isDirectory) return 0
        return rootfsDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } / 1024 / 1024
    }

    /** Storage location summary for UI / diagnostics. */
    fun getInstallLocationDescription(): String =
        "App private: ${baseDir.absolutePath} (tidak butuh izin storage eksternal)"
}
