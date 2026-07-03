package com.tunnel.terminal

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * WorkspaceSession - snapshot dari tab-terminal state.
 * Snapshot of terminal tab state for save/restore.
 *
 * Phase 19: Save/restore tab sets agar user bisa resume proyek berbeda.
 * Note: working dir diambil dari prompt shell saat save, jadi tidak exact
 * (shell state seperti env vars tidak disimpan). Cukup untuk UX switch proyek.
 */
data class WorkspaceSession(
    val name: String,
    val createdAt: Long,
    val tabCount: Int,
    /** Working directory per tab (best-effort, di-parse dari prompt). */
    val workingDirs: List<String>
)

/**
 * WorkspaceManager - Persistensi workspace sessions ke SharedPreferences.
 *
 * Phase 19: Save/restore tab sets agar user bisa switch antar proyek.
 * Save: simpan jumlah tab + working dir per tab.
 * Restore: buat tab sebanyak itu, kirim `cd <dir>` ke masing-masing.
 *
 * Limitations:
 * - Tidak save shell env vars, aliases, atau command history (per-tab history
 *   milik ShellExecutor, tidak dipersist)
 * - Working dir didapat dari prompt string parsing (best-effort)
 * - Untuk simplicity, hanya simpan nama session + tab count + working dirs
 */
class WorkspaceManager(context: Context) {
    private val prefs = context.getSharedPreferences("TunnelWorkspaces", Context.MODE_PRIVATE)
    private val _sessions = mutableListOf<WorkspaceSession>()
    val sessions: List<WorkspaceSession> get() = _sessions.toList()

    companion object {
        private const val MAX_SESSIONS = 20
    }

    init {
        load()
    }

    private fun load() {
        val json = prefs.getString("sessions", "[]") ?: "[]"
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val dirsArr = obj.optJSONArray("workingDirs") ?: JSONArray()
                val dirs = mutableListOf<String>()
                for (j in 0 until dirsArr.length()) {
                    dirs.add(dirsArr.getString(j))
                }
                _sessions.add(WorkspaceSession(
                    name = obj.getString("name"),
                    createdAt = obj.getLong("createdAt"),
                    tabCount = obj.getInt("tabCount"),
                    workingDirs = dirs
                ))
            }
            /* Sort by createdAt descending (terbaru di atas). */
            _sessions.sortByDescending { it.createdAt }
        } catch (e: Exception) {
            _sessions.clear()
        }
    }

    private fun save() {
        val arr = JSONArray()
        _sessions.forEach { s ->
            val dirsArr = JSONArray()
            s.workingDirs.forEach { dirsArr.put(it) }
            arr.put(JSONObject()
                .put("name", s.name)
                .put("createdAt", s.createdAt)
                .put("tabCount", s.tabCount)
                .put("workingDirs", dirsArr)
            )
        }
        prefs.edit().putString("sessions", arr.toString()).apply()
    }

    /**
     * Simpan snapshot session baru.
     * Save new session snapshot.
     *
     * @param name nama session (user-given)
     * @param tabCount jumlah tab saat ini
     * @param workingDirs working directory per tab
     * @return true jika sukses, false jika max sessions tercapai atau nama duplikat
     */
    fun saveSession(name: String, tabCount: Int, workingDirs: List<String>): Boolean {
        if (name.isBlank()) return false
        if (_sessions.size >= MAX_SESSIONS) return false
        if (_sessions.any { it.name == name }) return false
        /* BUG-16b fix: Jangan filter workingDirs — biarkan string kosong menempati slotnya
         * untuk menjaga korespondensi index-ke-tab. Old code: filter { it.isNotBlank() }
         * menyebabkan index geser jika ada tab yang working dir-nya blank. */
        val session = WorkspaceSession(
            name = name.trim(),
            createdAt = System.currentTimeMillis(),
            tabCount = tabCount,
            workingDirs = workingDirs
        )
        _sessions.add(session)
        _sessions.sortByDescending { it.createdAt }
        save()
        return true
    }

    /** Hapus session by name. */
    fun deleteSession(name: String): Boolean {
        val removed = _sessions.removeAll { it.name == name }
        if (removed) save()
        return removed
    }

    /** Ambil session by name. */
    fun getSession(name: String): WorkspaceSession? = _sessions.firstOrNull { it.name == name }

    /** Update existing session (overwrite). Returns false jika tidak ketemu. */
    fun updateSession(name: String, tabCount: Int, workingDirs: List<String>): Boolean {
        val idx = _sessions.indexOfFirst { it.name == name }
        if (idx < 0) return false
        _sessions[idx] = _sessions[idx].copy(
            tabCount = tabCount,
            workingDirs = workingDirs,
            createdAt = System.currentTimeMillis()
        )
        _sessions.sortByDescending { it.createdAt }
        save()
        return true
    }
}
