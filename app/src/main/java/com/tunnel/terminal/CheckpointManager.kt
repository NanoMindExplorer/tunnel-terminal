package com.tunnel.terminal

import android.content.Context
import android.util.Log
import java.io.File

/**
 * CheckpointManager — Snapshot file sebelum AI menulis, untuk undo/revert.
 *
 * Phase 50 fix (B-4): Checkpointing/Undo untuk AI file edits.
 *
 * OLD GAP: Jika AI menulis file yang salah (write_file) dan user sudah accept diff,
 * tidak ada jalan mudah untuk mengembalikan ke versi sebelumnya selain mengingat isi
 * filenya atau pakai git manual.
 *
 * FIX: Sebelum setiap write_file, simpan snapshot file ke checkpoint directory.
 * User bisa "Rewind" ke checkpoint manapun via UI.
 *
 * Struktur:
 *   filesDir/checkpoints/<session_timestamp>/<turn_N>/
 *     manifest.json    — metadata (timestamp, file path, turn number)
 *     <sanitized_path> — isi file sebelum write
 *
 * Implementasi minimal ala Aider — cukup untuk mobile (no Ctrl+Z).
 */
class CheckpointManager(private val context: Context) {
    companion object {
        private const val TAG = "CheckpointManager"
        private const val MAX_CHECKPOINTS = 50  // batas supaya storage tidak membengkak
        private const val SESSION_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000  // 7 hari
    }

    private val checkpointRoot = File(context.filesDir, "checkpoints")
    private var currentTurn = 0

    init {
        /* v8.6.0 fix (M4): Garbage collect old session dirs on init.
         * Sebelumnya: session_* dirs accumulate forever across app restarts
         * karena in-memory list di-clear pada process death, tapi on-disk
         * dirs tidak pernah di-clean. Sekarang: prune dirs older than 7 days. */
        try {
            if (checkpointRoot.isDirectory) {
                val cutoff = System.currentTimeMillis() - SESSION_MAX_AGE_MS
                checkpointRoot.listFiles()?.forEach { sessionDir ->
                    try {
                        if (sessionDir.isDirectory && sessionDir.lastModified() < cutoff) {
                            sessionDir.deleteRecursively()
                            Log.i(TAG, "GC: old checkpoint session dir deleted: ${sessionDir.name}")
                        }
                    } catch (e: Exception) {
                        /* v9.3.0 fix (H-15): Log GC failures instead of silent swallow. */
                        Log.w(TAG, "GC: failed to delete session dir ${sessionDir.name}: ${e.message}")
                    }
                }
            }
        } catch (_: Exception) {}
    }

    data class Checkpoint(
        val turn: Int,
        val timestamp: Long,
        val filePath: String,
        val checkpointDir: File
    )

    private val checkpoints = mutableListOf<Checkpoint>()

    /**
     * Simpan snapshot file SEBELUM AI menulis (write_file).
     * Dipanggil oleh ToolExecutor sebelum file.writeText().
     *
     * @param filePath Path file yang akan ditulis (sudah di-resolve ke absolute path)
     * @return Checkpoint yang baru dibuat, atau null kalau file belum ada (baru dibuat)
     */
    fun saveCheckpointBeforeWrite(filePath: String): Checkpoint? {
        val file = File(filePath)
        if (!file.exists()) {
            // File baru — tidak ada versi sebelumnya untuk disimpan
            Log.i(TAG, "saveCheckpoint: file baru ($filePath) — no previous version to save")
            return null
        }

        currentTurn++
        val timestamp = System.currentTimeMillis()
        val sessionDir = File(checkpointRoot, "session_${timestamp}")
        val turnDir = File(sessionDir, "turn_${currentTurn}")
        turnDir.mkdirs()

        // v9.1.0 fix (C-2): Tambah hash prefix ke sanitized filename untuk mencegah
        // collision antara paths yang share last 100 chars. Hash dari full path
        // menjamin uniqueness; sanitized name tetap untuk readability.
        val snapshotFile = File(turnDir, snapshotFileName(filePath))
        try {
            file.copyTo(snapshotFile, overwrite = true)
        } catch (e: Exception) {
            Log.w(TAG, "Gagal copy file ke checkpoint: ${e.message}")
            return null
        }

        // Wave-4: Safe JSON manifest (path may contain quotes)
        val manifest = File(turnDir, "manifest.json")
        try {
            val json = org.json.JSONObject()
                .put("turn", currentTurn)
                .put("timestamp", timestamp)
                .put("file", filePath)
            manifest.writeText(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Gagal tulis manifest: ${e.message}")
        }

        val checkpoint = Checkpoint(currentTurn, timestamp, filePath, turnDir)
        checkpoints.add(checkpoint)

        // Trim old checkpoints
        while (checkpoints.size > MAX_CHECKPOINTS) {
            val oldest = checkpoints.removeAt(0)
            try { oldest.checkpointDir.deleteRecursively() } catch (_: Exception) {}
        }

        Log.i(TAG, "Checkpoint saved: turn $currentTurn, file $filePath")
        return checkpoint
    }

    /** List semua checkpoint untuk file tertentu. */
    fun getCheckpointsForFile(filePath: String): List<Checkpoint> {
        return checkpoints.filter { it.filePath == filePath }.reversed()  // terbaru dulu
    }

    /** List semua checkpoint (semua file). */
    fun getAllCheckpoints(): List<Checkpoint> = checkpoints.reversed()

    /**
     * Restore file ke checkpoint tertentu.
     * @return true kalau berhasil, false kalau gagal
     */
    fun restore(checkpoint: Checkpoint): Boolean {
        val file = File(checkpoint.filePath)
        val snapshotFile = snapshotFileFor(checkpoint.filePath, checkpoint.checkpointDir)

        if (snapshotFile == null || !snapshotFile.exists()) {
            Log.w(TAG, "Snapshot file tidak ditemukan di ${checkpoint.checkpointDir.absolutePath}")
            return false
        }

        try {
            // Save current version first (supaya user bisa redo kalau perlu)
            if (file.exists()) {
                saveCheckpointBeforeWrite(checkpoint.filePath)
            }
            snapshotFile.copyTo(file, overwrite = true)
            Log.i(TAG, "Restored ${checkpoint.filePath} to turn ${checkpoint.turn}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Gagal restore checkpoint: ${e.message}")
            return false
        }
    }

    private fun snapshotFileName(filePath: String): String {
        val sanitizedPath = filePath.replace("/", "_").replace("\\", "_").takeLast(80)
        val pathHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(filePath.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
        return "${pathHash}_${sanitizedPath}"
    }

    private fun snapshotFileFor(filePath: String, dir: File): File? {
        val expected = File(dir, snapshotFileName(filePath))
        if (expected.isFile) return expected
        return dir.listFiles()?.firstOrNull { it.isFile && it.name != "manifest.json" }
    }

    /** Clear semua checkpoint (untuk cleanup). */
    fun clearAll() {
        checkpoints.clear()
        currentTurn = 0
        try { checkpointRoot.deleteRecursively() } catch (_: Exception) {}
    }
}
