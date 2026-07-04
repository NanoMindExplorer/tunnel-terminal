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

        const val ROOTFS_URL_ARM64 =
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.2-base-arm64.tar.gz"
        const val ROOTFS_URL_AMD64 =
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.2-base-amd64.tar.gz"

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

    private fun pickRootfsUrl(): String {
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
        for (libName in PROOT_LIBS) {
            try {
                context.assets.open("$ASSET_PROOT_LIB_DIR/$libName").use { input ->
                    FileOutputStream(File(libDir, libName)).use { output -> input.copyTo(output) }
                }
                Log.i(TAG, "Library $libName disalin ke ${libDir.absolutePath}")
            } catch (e: Exception) {
                Log.w(TAG, "Library $libName tidak ditemukan di assets — proot mungkin akan gagal jalan: ${e.message}")
                /* Tidak throw — biarkan tetap install, error akan muncul saat proot dijalankan
                 * kalau ternyata lib dibutuhkan tapi tidak ada. */
            }
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
        val rootfsUrl = pickRootfsUrl()
        downloadWithProgress(rootfsUrl, rootfsTarball) { percent ->
            listener.onProgress("Mengunduh Ubuntu rootfs", percent)
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

        // 7. Bersihkan tarball.
        rootfsTarball.delete()

        // 8. Tulis marker.
        markerFile.writeText(System.currentTimeMillis().toString())
        Log.i(TAG, "Instalasi Ubuntu proot selesai di ${rootfsDir.absolutePath}")
    }

    fun setupResolvConf() {
        val resolvConf = File(rootfsDir, "etc/resolv.conf")
        resolvConf.parentFile?.mkdirs()
        resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
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

    fun uninstall() {
        try {
            baseDir.deleteRecursively()
            Log.i(TAG, "Instalasi Linux environment dihapus")
        } catch (e: Exception) {
            Log.w(TAG, "Gagal hapus instalasi: ${e.message}")
        }
    }

    fun getFreeSpaceMb(): Long = baseDir.usableSpace / 1024 / 1024

    fun getRootfsSizeMb(): Long {
        if (!rootfsDir.isDirectory) return 0
        return rootfsDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } / 1024 / 1024
    }
}
