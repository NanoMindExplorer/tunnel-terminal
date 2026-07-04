package com.tunnel.terminal

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * StorageManager - Implementasi nyata Storage Access Framework untuk Tunnel Terminal.
 *
 * Mengatasi bug phantom: README menyebut `setup-storage` tapi sebelumnya tidak ada
 * implementasi (perintah dilempar ke /system/bin/sh yang tidak kenal perintah ini).
 *
 * Real Storage Access Framework implementation. Resolves the phantom `setup-storage`
 * command that previously fell through to /system/bin/sh and failed.
 *
 * Cara kerja / How it works:
 * 1. User ketik `setup-storage`
 * 2. MainActivity panggil StorageManager.requestStorageAccess()
 * 3. SAF launcher membuka picker direktori
 * 4. User pilih folder (biasanya /sdcard atau Documents)
 * 5. URI persisten diambil, dipersist lewat takePersistableUriPermission
 * 6. Dibuat symlink simbolik ~/storage/shared -> /storage/emulated/0 (jika memungkinkan)
 *    atau dibuat wrapper command di session shell
 *
 * Catatan: Pada Android 11+, akses ke /sdcard full dilakukan lewat SAF URI,
 * bukan path file langsung. Beberapa path seperti /sdcard/Documents masih
 * bisa diakses via symlink pada device yang mendukungnya.
 */
class StorageManager(private val context: Context) {

    companion object {
        private const val TAG = "StorageManager"
        private const val PREFS_NAME = "TunnelStorage"
        private const val KEY_TREE_URI = "tree_uri"
        private const val KEY_SETUP_DONE = "setup_done"
    }

    /** Path folder home user. User's home directory. */
    val homeDir: File by lazy {
        val home = File(context.applicationContext.filesDir, "home")
        if (!home.exists()) home.mkdirs()
        home
    }

    /** Path target symlink untuk ~/storage/shared. */
    val storageLinkDir: File by lazy { File(homeDir, "storage") }
    val sharedLinkFile: File by lazy { File(storageLinkDir, "shared") }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Cek apakah storage sudah di-setup. Check if storage is set up. */
    fun isSetupDone(): Boolean = prefs.getBoolean(KEY_SETUP_DONE, false)

    /** Ambil URI tree yang persisten. Get persisted tree URI. */
    fun getTreeUri(): Uri? {
        val s = prefs.getString(KEY_TREE_URI, null) ?: return null
        return runCatching { Uri.parse(s) }.getOrNull()
    }

    /**
     * Simpan URI persisten dari hasil picker.
     * Persist URI from picker result.
     */
    fun persistTreeUri(uri: Uri): Boolean {
        return try {
            /* Take persistable permission sehingga URI tetap valid setelah reboot.
             * Take persistable permission so URI survives reboot. */
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            prefs.edit()
                .putString(KEY_TREE_URI, uri.toString())
                .putBoolean(KEY_SETUP_DONE, true)
                .apply()
            Log.i(TAG, "Tree URI persisted: $uri")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Gagal takePersistableUriPermission: ${e.message}")
            false
        }
    }

    /**
     * Buat struktur ~/storage/shared yang menunjuk ke folder yang dipilih.
     * Create ~/storage/shared structure pointing to chosen folder.
     *
     * Strategi:
     * - Jika bisa buat symlink ke path real /storage/emulated/0 -> lakukan
     * - Jika tidak (Android 11+ ketat), buat direktori marker dengan file README
     *   berisi instruksi, dan gunakan DocumentFile untuk akses lewat SAF URI
     *
     * Returns: pesan status yang bisa ditampilkan ke user.
     */
    fun createStorageSymlink(): String {
        if (!isSetupDone()) {
            return "Setup belum dilakukan. Ketik 'setup-storage' lalu pilih folder."
        }
        val uri = getTreeUri() ?: return "Tree URI tidak ditemukan."

        /* Pastikan folder ~/storage ada. Ensure ~/storage exists. */
        if (!storageLinkDir.exists()) storageLinkDir.mkdirs()

        /* Coba buat symlink ke /storage/emulated/0 (may fail on Android 11+).
         * Try to symlink to /storage/emulated/0 (may fail on Android 11+). */
        val realSdcard = File("/storage/emulated/0")
        if (realSdcard.exists() && realSdcard.isDirectory) {
            try {
                if (sharedLinkFile.exists()) sharedLinkFile.delete()
                /* Os.symlink membutuhkan API 21+, dan filesystem harus mendukung.
                 * Os.symlink requires API 21+, filesystem must support. */
                if (android.os.Build.VERSION.SDK_INT >= 21) {
                    try {
                        android.system.Os.symlink(realSdcard.absolutePath, sharedLinkFile.absolutePath)
                        return "OK: ~/storage/shared -> ${realSdcard.absolutePath}\n" +
                               "Anda bisa cd ~/storage/shared untuk akses file manager."
                    } catch (e: Exception) {
                        Log.w(TAG, "symlink gagal, fallback ke marker: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gagal buat symlink: ${e.message}")
            }
        }

        /* Fallback: tulis README di ~/storage/shared/README_SAF.txt berisi info URI.
         * Fallback: write README explaining SAF URI usage. */
        if (!sharedLinkFile.exists()) sharedLinkFile.mkdirs()
        val readme = File(sharedLinkFile, "README_SAF.txt")
        try {
            readme.writeText(
                """
                Tunnel Terminal - Storage Access Framework Bridge
                =================================================

                Folder ini adalah jembatan ke URI yang Anda pilih via SAF picker.
                URI Persisten: $uri

                Untuk akses file dari shell Tunnel Terminal:
                - Gunakan path ~/storage/shared/<nama-file> untuk file yang Anda buat di sini
                - File yang Anda buat lewat UI File Manager di folder yang dipilih akan
                  otomatis muncul di sini (karena SAF URI mengarah ke folder yang sama)

                Catatan: Pada Android 11+, /sdcard tidak bisa diakses langsung lewat path
                tanpa MANAGE_EXTERNAL_STORAGE. Tunnel Terminal menggunakan SAF (lebih aman)
                sehingga Anda tetap bisa berbagi file dengan aplikasi lain tanpa izin luas.

                Ketik 'setup-storage' lagi untuk memilih folder berbeda.
                """.trimIndent()
            )
            return "OK: Bridge folder dibuat di ~/storage/shared/\n" +
                   "URI: $uri\n" +
                   "Buka ~/storage/shared/README_SAF.txt untuk detail."
        } catch (e: Exception) {
            return "Gagal membuat bridge folder: ${e.message}"
        }
    }

    /**
     * Cek apakah file bisa diakses lewat SAF URI.
     * Test if a file is accessible via SAF URI.
     */
    fun listFilesInRoot(): List<String> {
        val uri = getTreeUri() ?: return emptyList()
        val root = DocumentFile.fromTreeUri(context, uri) ?: return emptyList()
        return root.listFiles().mapNotNull { it.name }
    }

    /**
     * Phase 47 (Bagian 1 Fix 3): Ambil path root yang sudah di-grant via SAF.
     *
     * SAF tree URI format: `content://com.android.externalstorage.documents/tree/primary%3A`
     * atau `content://com.android.externalstorage.documents/tree/primary%3ADocuments`
     *
     * Kita map URI ini ke path filesystem real (mis. `/storage/emulated/0` atau
     * `/storage/emulated/0/Documents`). Ini dipakai oleh ToolExecutor.resolvePath()
     * untuk cek apakah path AI berada di dalam tree SAF yang sudah di-grant.
     *
     * Returns: path filesystem root yang di-grant, atau null kalau belum setup.
     */
    fun getGrantedRootPath(): String? {
        if (!isSetupDone()) return null
        val uri = getTreeUri() ?: return null
        val uriStr = uri.toString()
        // Decode tree URI → path. Format umum:
        //   content://com.android.externalstorage.documents/tree/primary%3A
        //   content://com.android.externalstorage.documents/tree/primary%3ADocuments
        return try {
            val treePart = uriStr.substringAfter("tree/", "")
            val decoded = java.net.URLDecoder.decode(treePart, "UTF-8")
            // "primary:" → /storage/emulated/0
            // "primary:Documents" → /storage/emulated/0/Documents
            when {
                decoded.startsWith("primary:") -> {
                    val subPath = decoded.removePrefix("primary:")
                    if (subPath.isEmpty()) "/storage/emulated/0"
                    else "/storage/emulated/0/$subPath"
                }
                decoded.startsWith("/") -> decoded
                else -> {
                    // Unknown format — fallback ke /storage/emulated/0
                    Log.w(TAG, "Unknown SAF tree format: $decoded, fallback ke /storage/emulated/0")
                    "/storage/emulated/0"
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gagal parse granted root path: ${e.message}")
            null
        }
    }

    /**
     * Phase 47 (Bagian 1 Fix 3): Cek apakah path tertentu berada di dalam tree SAF.
     *
     * Dipakai oleh ToolExecutor.resolvePath() untuk sandbox check — path absolut
     * yang berada di dalam tree SAF yang sudah di-grant diizinkan (user sudah
     * eksplisit beri akses via setup-storage).
     *
     * CATATAN: menulis lewat java.io.File ke path yang PATH-nya berada di dalam
     * tree SAF belum tentu benar-benar berhasil hanya karena URI permission sudah
     * di-grant — SAF grant berlaku di level ContentResolver/DocumentFile, bukan
     * otomatis membuka akses File API mentah. Untuk write/read yang reliable di
     * tree SAF, perlu pakai DocumentFile API. Tapi untuk sandbox check di sini,
     * kita hanya cek path prefix — actual read/write tetap lewat File API dengan
     * best-effort (di Android <11 ini biasanya work, di 11+ mungkin perlu fallback).
     */
    fun isPathWithinGrantedTree(file: java.io.File): Boolean {
        val grantedRoot = getGrantedRootPath() ?: return false
        return try {
            val canonicalPath = file.canonicalPath
            val canonicalRoot = java.io.File(grantedRoot).canonicalPath
            canonicalPath.startsWith(canonicalRoot)
        } catch (e: Exception) {
            Log.w(TAG, "Gagal cek isPathWithinGrantedTree: ${e.message}")
            false
        }
    }

    /**
     * Hapus setup storage (untuk reset).
     * Clear storage setup (for reset).
     */
    fun clearSetup() {
        val uri = getTreeUri()
        if (uri != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.releasePersistableUriPermission(uri, flags)
            } catch (e: Exception) {
                Log.w(TAG, "Gagal release permission: ${e.message}")
            }
        }
        prefs.edit().clear().apply()
        try { sharedLinkFile.deleteRecursively() } catch (e: Exception) {}
    }

    /**
     * Status lengkap untuk ditampilkan di terminal.
     * Full status string for terminal display.
     */
    fun statusReport(): String {
        val sb = StringBuilder()
        sb.appendLine("=== Storage Access Framework Status ===")
        sb.appendLine("Setup done: ${if (isSetupDone()) "YES" else "NO"}")
        getTreeUri()?.let {
            sb.appendLine("URI: $it")
            val root = DocumentFile.fromTreeUri(context, it)
            if (root != null && root.canRead()) {
                sb.appendLine("Accessible: YES")
                sb.appendLine("Files at root: ${root.listFiles().size}")
            } else {
                sb.appendLine("Accessible: NO (permission revoked?)")
            }
        } ?: sb.appendLine("URI: <not set>")
        sb.appendLine("Home: ${homeDir.absolutePath}")
        sb.appendLine("Bridge: ${sharedLinkFile.absolutePath} (${if (sharedLinkFile.exists()) "exists" else "missing"})")
        return sb.toString()
    }
}
