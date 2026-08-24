package com.tunnel.terminal

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.Charset

/**
 * Wave-19: Real device storage access for Tunnel Terminal.
 *
 * Problems with the old bridge:
 * - setup-storage only persisted a SAF URI and wrote a README into app-private
 *   filesDir/home/storage/shared — shell/AI still used java.io.File and could NOT
 *   write into Download / Documents on Android 11+.
 *
 * This manager:
 * 1. setup-storage → OpenDocumentTree (prefers Downloads as initial location)
 * 2. SAF DocumentFile CRUD for relative paths under the granted tree
 * 3. MediaStore helper to save into public Downloads (API 29+)
 * 4. Optional MANAGE_EXTERNAL_STORAGE (all-files) for real /sdcard paths in shell
 */
class StorageManager(private val context: Context) {

    companion object {
        private const val TAG = "StorageManager"
        private const val PREFS_NAME = "TunnelStorage"
        private const val KEY_TREE_URI = "tree_uri"
        private const val KEY_SETUP_DONE = "setup_done"
        private const val KEY_DISPLAY_NAME = "display_name"
    }

    val homeDir: File by lazy {
        File(context.applicationContext.filesDir, "home").apply { mkdirs() }
    }
    val workspaceDir: File by lazy {
        File(context.applicationContext.filesDir, "workspace").apply { mkdirs() }
    }
    val storageLinkDir: File by lazy { File(homeDir, "storage") }
    val sharedLinkFile: File by lazy { File(storageLinkDir, "shared") }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val resolver get() = context.contentResolver

    fun isSetupDone(): Boolean = prefs.getBoolean(KEY_SETUP_DONE, false)

    fun getTreeUri(): Uri? {
        val s = prefs.getString(KEY_TREE_URI, null) ?: return null
        return runCatching { Uri.parse(s) }.getOrNull()
    }

    fun getDisplayName(): String =
        prefs.getString(KEY_DISPLAY_NAME, null)
            ?: getGrantedRootPath()
            ?: "(belum setup)"

    /**
     * Intent for OpenDocumentTree with Downloads as the suggested start folder.
     */
    fun createOpenTreeIntent(): Intent {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            /* Prefer Downloads as initial location when available. */
            val initial = runCatching {
                DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:Download"
                )
            }.getOrNull()
            if (initial != null) {
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
            }
        }
        return intent
    }

    fun persistTreeUri(uri: Uri): Boolean {
        return try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            resolver.takePersistableUriPermission(uri, flags)
            val display = DocumentFile.fromTreeUri(context, uri)?.name
                ?: getGrantedRootPathFromUri(uri)
                ?: uri.toString()
            prefs.edit()
                .putString(KEY_TREE_URI, uri.toString())
                .putBoolean(KEY_SETUP_DONE, true)
                .putString(KEY_DISPLAY_NAME, display)
                .apply()
            Log.i(TAG, "Tree URI persisted: $uri ($display)")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "takePersistableUriPermission failed: ${e.message}")
            false
        }
    }

    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true /* legacy: WRITE_EXTERNAL_STORAGE handled at runtime */
        }
    }

    fun createManageAllFilesIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }

    /**
     * After SAF grant (and optionally all-files access), prepare ~/storage bridge
     * and return a user-facing status message.
     */
    fun createStorageSymlink(): String {
        if (!isSetupDone()) {
            return "Setup belum dilakukan. Ketik 'setup-storage' lalu pilih folder (disarankan Download)."
        }
        val uri = getTreeUri() ?: return "Tree URI tidak ditemukan."
        if (!storageLinkDir.exists()) storageLinkDir.mkdirs()

        val grantedPath = getGrantedRootPath()
        val sb = StringBuilder()
        sb.appendLine("✓ Folder perangkat terhubung: ${getDisplayName()}")
        if (grantedPath != null) sb.appendLine("  Path: $grantedPath")

        /* Real filesystem symlink when all-files (or legacy) access is available. */
        if (hasAllFilesAccess() && grantedPath != null) {
            val target = File(grantedPath)
            if (target.exists() && target.isDirectory) {
                try {
                    if (sharedLinkFile.exists() || sharedLinkFile.isFile) {
                        sharedLinkFile.deleteRecursively()
                    }
                    android.system.Os.symlink(target.absolutePath, sharedLinkFile.absolutePath)
                    sb.appendLine("✓ Symlink: ~/storage/shared → $grantedPath")
                    sb.appendLine("  Shell: ls ~/storage/shared  |  cat > ~/storage/shared/catatan.txt")
                    return sb.toString().trimEnd()
                } catch (e: Exception) {
                    Log.w(TAG, "symlink failed: ${e.message}")
                    sb.appendLine("⚠ Symlink gagal (${e.message}) — gunakan perintah storage-* (SAF).")
                }
            }
        } else {
            sb.appendLine("⚠ Akses path shell penuh belum aktif (Android Scoped Storage).")
            sb.appendLine("  Opsi A (disarankan): gunakan perintah storage-* di bawah.")
            sb.appendLine("  Opsi B: storage-grant-all → izinkan \"Akses semua file\" lalu setup-storage lagi.")
        }

        /* Marker folder for navigation hints (not a real bind mount). */
        if (!sharedLinkFile.exists()) sharedLinkFile.mkdirs()
        val readme = File(sharedLinkFile, "CARA_PAKAI.txt")
        try {
            readme.writeText(
                """
                Tunnel Terminal — Bridge ke folder perangkat
                ============================================
                Folder SAF: ${getDisplayName()}
                URI: $uri
                Path map: ${grantedPath ?: "?"}

                Perintah di terminal:
                  storage-status
                  storage-ls
                  storage-ls Download          (subfolder relatif)
                  storage-put catatan.txt     (workspace → folder SAF)
                  storage-get foto.jpg        (folder SAF → workspace)
                  storage-save-download a.txt (langsung ke Download publik)

                Jangan mengandalkan 'cat > /sdcard/...' tanpa storage-grant-all.
                """.trimIndent()
            )
        } catch (_: Exception) { /* ignore */ }

        sb.appendLine()
        sb.appendLine("Perintah cepat:")
        sb.appendLine("  storage-ls")
        sb.appendLine("  storage-put <file-di-workspace> [nama-tujuan]")
        sb.appendLine("  storage-get <file-di-folder-SAF> [nama-lokal]")
        sb.appendLine("  storage-save-download <file>   → Downloads publik")
        return sb.toString().trimEnd()
    }

    fun getGrantedRootPath(): String? {
        if (!isSetupDone()) return null
        return getTreeUri()?.let { getGrantedRootPathFromUri(it) }
    }

    private fun getGrantedRootPathFromUri(uri: Uri): String? {
        val uriStr = uri.toString()
        return try {
            val treePart = uriStr.substringAfter("tree/", "")
            val decoded = java.net.URLDecoder.decode(treePart, "UTF-8")
            when {
                decoded.startsWith("primary:") -> {
                    val subPath = decoded.removePrefix("primary:")
                    if (subPath.isEmpty()) "/storage/emulated/0"
                    else "/storage/emulated/0/$subPath"
                }
                decoded.contains(":") -> {
                    /* secondary storage volume — best-effort */
                    val vol = decoded.substringBefore(":")
                    val sub = decoded.substringAfter(":", "")
                    if (sub.isEmpty()) "/storage/$vol"
                    else "/storage/$vol/$sub"
                }
                decoded.startsWith("/") -> decoded
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "parse granted path: ${e.message}")
            null
        }
    }

    fun isPathWithinGrantedTree(file: File): Boolean {
        val grantedRoot = getGrantedRootPath() ?: return false
        return try {
            val canonicalPath = file.canonicalPath
            val canonicalRoot = File(grantedRoot).canonicalPath
            SessionTargetResolver.isPathInside(canonicalPath, canonicalRoot)
        } catch (e: Exception) {
            false
        }
    }

    /* ─── SAF DocumentFile bridge ─── */

    private fun treeRoot(): DocumentFile? {
        val uri = getTreeUri() ?: return null
        return DocumentFile.fromTreeUri(context, uri)
    }

    /**
     * Resolve a relative path (e.g. "notes/a.txt" or "a.txt") under the granted tree.
     * Creates intermediate directories when [createDirs] is true (for write).
     */
    fun resolveDocument(relativePath: String, createDirs: Boolean = false): DocumentFile? {
        val root = treeRoot() ?: return null
        val parts = relativePath.trim('/').split('/').filter { it.isNotBlank() && it != "." }
        if (parts.isEmpty()) return root
        var current: DocumentFile = root
        for ((i, part) in parts.withIndex()) {
            val isLast = i == parts.lastIndex
            val next = current.findFile(part)
            if (next != null) {
                current = next
                continue
            }
            if (!createDirs) return null
            current = if (isLast) {
                /* Leaf: caller creates file with mime — return parent for createFile */
                return current /* signal: need create under parent — use write helpers instead */
            } else {
                current.createDirectory(part) ?: return null
            }
        }
        return current
    }

    fun listRelative(relativePath: String = ""): Result<List<String>> {
        return try {
            val root = treeRoot() ?: return Result.failure(IllegalStateException("setup-storage belum dijalankan"))
            val dir = if (relativePath.isBlank() || relativePath == ".") root
            else resolveDocument(relativePath, createDirs = false)
                ?: return Result.failure(IllegalArgumentException("Tidak ditemukan: $relativePath"))
            if (!dir.isDirectory) return Result.failure(IllegalArgumentException("Bukan folder: $relativePath"))
            val rows = dir.listFiles().map { f ->
                val mark = if (f.isDirectory) "d" else "-"
                val size = if (f.isFile) "  ${f.length()}B" else ""
                "$mark ${f.name}$size"
            }.sorted()
            Result.success(rows)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun readTextRelative(relativePath: String, maxChars: Int = 200_000): Result<String> {
        return try {
            val doc = resolveDocument(relativePath)
                ?: return Result.failure(IllegalArgumentException("File tidak ditemukan: $relativePath"))
            if (!doc.isFile) return Result.failure(IllegalArgumentException("Bukan file: $relativePath"))
            resolver.openInputStream(doc.uri)?.use { input ->
                val text = input.bufferedReader(Charset.forName("UTF-8")).readText()
                Result.success(if (text.length > maxChars) text.take(maxChars) + "\n…(truncated)" else text)
            } ?: Result.failure(IllegalStateException("Tidak bisa buka stream: $relativePath"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun writeTextRelative(relativePath: String, content: String, mime: String = "text/plain"): Result<String> {
        return try {
            val root = treeRoot()
                ?: return Result.failure(IllegalStateException("setup-storage belum dijalankan"))
            val parts = relativePath.trim('/').split('/').filter { it.isNotBlank() && it != "." }
            if (parts.isEmpty()) return Result.failure(IllegalArgumentException("Path kosong"))
            var parent = root
            for (i in 0 until parts.lastIndex) {
                val name = parts[i]
                val next = parent.findFile(name)
                parent = when {
                    next != null && next.isDirectory -> next
                    next != null -> return Result.failure(IllegalStateException("Bukan folder: $name"))
                    else -> parent.createDirectory(name)
                        ?: return Result.failure(IllegalStateException("Gagal buat folder: $name"))
                }
            }
            val fileName = parts.last()
            var target = parent.findFile(fileName)
            if (target == null) {
                target = parent.createFile(mime, fileName)
                    ?: return Result.failure(IllegalStateException("Gagal buat file: $fileName"))
            } else if (target.isDirectory) {
                return Result.failure(IllegalStateException("Target adalah folder: $fileName"))
            }
            resolver.openOutputStream(target.uri, "wt")?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: return Result.failure(IllegalStateException("Tidak bisa buka output stream"))
            Result.success("OK: ditulis ke ${getDisplayName()}/$relativePath (${content.length} chars)")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun writeBytesRelative(relativePath: String, bytes: ByteArray, mime: String = "application/octet-stream"): Result<String> {
        return try {
            val root = treeRoot()
                ?: return Result.failure(IllegalStateException("setup-storage belum dijalankan"))
            val parts = relativePath.trim('/').split('/').filter { it.isNotBlank() && it != "." }
            if (parts.isEmpty()) return Result.failure(IllegalArgumentException("Path kosong"))
            var parent = root
            for (i in 0 until parts.lastIndex) {
                val name = parts[i]
                val next = parent.findFile(name)
                parent = when {
                    next != null && next.isDirectory -> next
                    next != null -> return Result.failure(IllegalStateException("Bukan folder: $name"))
                    else -> parent.createDirectory(name)
                        ?: return Result.failure(IllegalStateException("Gagal buat folder: $name"))
                }
            }
            val fileName = parts.last()
            var target = parent.findFile(fileName)
            if (target == null) {
                target = parent.createFile(mime, fileName)
                    ?: return Result.failure(IllegalStateException("Gagal buat file: $fileName"))
            }
            resolver.openOutputStream(target.uri, "w")?.use { out ->
                out.write(bytes)
                out.flush()
            } ?: return Result.failure(IllegalStateException("Tidak bisa buka output stream"))
            Result.success("OK: ditulis ${bytes.size} bytes → ${getDisplayName()}/$relativePath")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Copy local app file into SAF tree. */
    fun putLocalFile(local: File, destRelative: String? = null): Result<String> {
        if (!local.exists() || !local.isFile) {
            return Result.failure(IllegalArgumentException("File lokal tidak ada: ${local.absolutePath}"))
        }
        val dest = destRelative?.trim('/').orEmpty().ifBlank { local.name }
        val mime = guessMime(local.name)
        return try {
            val bytes = local.readBytes()
            writeBytesRelative(dest, bytes, mime)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Copy from SAF tree into local app file. */
    fun getToLocalFile(relativePath: String, local: File): Result<String> {
        return try {
            val doc = resolveDocument(relativePath)
                ?: return Result.failure(IllegalArgumentException("Tidak ditemukan di storage: $relativePath"))
            if (!doc.isFile) return Result.failure(IllegalArgumentException("Bukan file: $relativePath"))
            local.parentFile?.mkdirs()
            resolver.openInputStream(doc.uri)?.use { input ->
                FileOutputStream(local).use { output -> input.copyTo(output) }
            } ?: return Result.failure(IllegalStateException("Gagal buka stream"))
            Result.success("OK: ${getDisplayName()}/$relativePath → ${local.absolutePath} (${local.length()}B)")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun deleteRelative(relativePath: String): Result<String> {
        return try {
            val doc = resolveDocument(relativePath)
                ?: return Result.failure(IllegalArgumentException("Tidak ditemukan: $relativePath"))
            if (doc.delete()) Result.success("OK: dihapus $relativePath")
            else Result.failure(IllegalStateException("Gagal hapus $relativePath"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Save into the public Downloads collection via MediaStore (API 29+).
     * Does not require setup-storage; creates a file visible in the Downloads app.
     */
    fun saveToPublicDownloads(displayName: String, content: ByteArray, mime: String = "text/plain"): Result<String> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val item = resolver.insert(collection, values)
                    ?: return Result.failure(IllegalStateException("MediaStore insert gagal"))
                resolver.openOutputStream(item)?.use { it.write(content); it.flush() }
                    ?: return Result.failure(IllegalStateException("openOutputStream gagal"))
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(item, values, null, null)
                Result.success("OK: disimpan ke Download publik → $displayName\nURI: $item")
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val out = File(dir, displayName)
                out.writeBytes(content)
                Result.success("OK: disimpan ke ${out.absolutePath}")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun saveLocalFileToPublicDownloads(local: File, displayName: String? = null): Result<String> {
        if (!local.exists()) return Result.failure(IllegalArgumentException("File tidak ada: ${local.path}"))
        val name = displayName ?: local.name
        return try {
            saveToPublicDownloads(name, local.readBytes(), guessMime(name))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * When path is under granted root and SAF is set up, prefer DocumentFile I/O
     * over raw File API (which fails on Android 11+ even if path "looks" allowed).
     */
    fun shouldUseSafForAbsolutePath(absolutePath: String): Boolean {
        if (!isSetupDone()) return false
        val root = getGrantedRootPath() ?: return false
        return try {
            SessionTargetResolver.isPathInside(
                File(absolutePath).canonicalPath,
                File(root).canonicalPath
            )
        } catch (_: Exception) {
            absolutePath.startsWith(root)
        }
    }

    fun relativePathUnderGrant(absolutePath: String): String? {
        val root = getGrantedRootPath() ?: return null
        val canon = try { File(absolutePath).canonicalPath } catch (_: Exception) { absolutePath }
        val rootCanon = try { File(root).canonicalPath } catch (_: Exception) { root }
        if (!SessionTargetResolver.isPathInside(canon, rootCanon)) return null
        val rel = canon.removePrefix(rootCanon).trimStart('/')
        return rel
    }

    fun clearSetup() {
        val uri = getTreeUri()
        if (uri != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                resolver.releasePersistableUriPermission(uri, flags)
            } catch (e: Exception) {
                Log.w(TAG, "release permission: ${e.message}")
            }
        }
        prefs.edit().clear().apply()
        try { sharedLinkFile.deleteRecursively() } catch (_: Exception) {}
    }

    fun statusReport(): String {
        val sb = StringBuilder()
        sb.appendLine("=== Storage Status (Wave-19) ===")
        sb.appendLine("SAF setup: ${if (isSetupDone()) "YES" else "NO"}")
        sb.appendLine("Folder: ${getDisplayName()}")
        getTreeUri()?.let { uri ->
            sb.appendLine("URI: $uri")
            val root = DocumentFile.fromTreeUri(context, uri)
            if (root != null && root.canRead()) {
                sb.appendLine("SAF readable: YES  writable: ${root.canWrite()}")
                sb.appendLine("Entries: ${root.listFiles().size}")
            } else {
                sb.appendLine("SAF accessible: NO (izin dicabut? jalankan setup-storage lagi)")
            }
        }
        getGrantedRootPath()?.let { sb.appendLine("Mapped path: $it") }
        sb.appendLine("All-files access: ${if (hasAllFilesAccess()) "YES" else "NO"}")
        sb.appendLine("App home: ${homeDir.absolutePath}")
        sb.appendLine("Workspace: ${workspaceDir.absolutePath}")
        sb.appendLine("Bridge: ${sharedLinkFile.absolutePath} (${if (sharedLinkFile.exists()) "exists" else "missing"})")
        sb.appendLine()
        sb.appendLine("Perintah:")
        sb.appendLine("  setup-storage              pilih folder (Download disarankan)")
        sb.appendLine("  storage-grant-all          izinkan akses semua file (opsional)")
        sb.appendLine("  storage-ls [subfolder]")
        sb.appendLine("  storage-put <file> [dest]  salin workspace → folder SAF")
        sb.appendLine("  storage-get <file> [dest]  salin folder SAF → workspace")
        sb.appendLine("  storage-save-download <f>  simpan ke Download publik")
        sb.appendLine("  storage-write <path> <text> tulis teks ke folder SAF")
        return sb.toString().trimEnd()
    }

    fun listFilesInRoot(): List<String> {
        val uri = getTreeUri() ?: return emptyList()
        val root = DocumentFile.fromTreeUri(context, uri) ?: return emptyList()
        return root.listFiles().mapNotNull { it.name }
    }

    private fun guessMime(name: String): String {
        val n = name.lowercase()
        return when {
            n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".log") -> "text/plain"
            n.endsWith(".json") -> "application/json"
            n.endsWith(".html") || n.endsWith(".htm") -> "text/html"
            n.endsWith(".csv") -> "text/csv"
            n.endsWith(".png") -> "image/png"
            n.endsWith(".jpg") || n.endsWith(".jpeg") -> "image/jpeg"
            n.endsWith(".pdf") -> "application/pdf"
            n.endsWith(".zip") -> "application/zip"
            n.endsWith(".kt") || n.endsWith(".java") || n.endsWith(".py") ||
                n.endsWith(".js") || n.endsWith(".ts") || n.endsWith(".sh") ||
                n.endsWith(".c") || n.endsWith(".cpp") || n.endsWith(".h") ||
                n.endsWith(".xml") || n.endsWith(".yml") || n.endsWith(".yaml") -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}
