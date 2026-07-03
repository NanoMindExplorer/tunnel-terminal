package com.tunnel.terminal

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Snippet / Workflow - perintah tersimpan yang bisa dijalankan dengan satu klik.
 * Snippet / Workflow - saved command runnable with one tap.
 */
data class Snippet(val id: Long, val title: String, val command: String)

/**
 * SnippetManager - Persistensi workflow snippets ke SharedPreferences.
 *
 * Phase 17: Tambah method update(), batasi max 100 snippets (anti-bloat),
 * gunakan ID stabil (bukan index) untuk identitas (anti bug hapus item salah
 * jika list berubah urutan).
 *
 * Persisted workflow snippets. Phase 17 adds update(), max 100 cap, stable IDs.
 */
class SnippetManager(context: Context) {
    private val prefs = context.getSharedPreferences("TunnelSnippets", Context.MODE_PRIVATE)
    private val _snippets = mutableListOf<Snippet>()
    val snippets: List<Snippet> get() = _snippets.toList()

    companion object {
        private const val MAX_SNIPPETS = 100
    }

    /* BUG-31 fix: Pindahkan counter ke instance field (bukan companion object). */
    private var nextIdCounter = 1L

    init {
        load()
    }

    private fun load() {
        val json = prefs.getString("snippets", "[]") ?: "[]"
        try {
            val arr = JSONArray(json)
            var maxId = 0L
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optLong("id", 0L).let { if (it == 0L) System.currentTimeMillis() + i else it }
                _snippets.add(Snippet(id, obj.getString("title"), obj.getString("command")))
                if (id > maxId) maxId = id
            }
            nextIdCounter = maxId + 1
        } catch (e: Exception) {
            /* JSON corrupt - start fresh */
            _snippets.clear()
        }
    }

    private fun save() {
        val arr = JSONArray()
        _snippets.forEach { s ->
            arr.put(JSONObject().put("id", s.id).put("title", s.title).put("command", s.command))
        }
        prefs.edit().putString("snippets", arr.toString()).apply()
    }

    /** Tambah snippet baru. Returns false jika kapasitas penuh. */
    fun add(title: String, command: String): Boolean {
        if (_snippets.size >= MAX_SNIPPETS) return false
        val id = nextIdCounter++
        _snippets.add(Snippet(id, title, command))
        save()
        return true
    }

    /** Update snippet existing by ID. Returns false jika tidak ketemu. */
    fun update(id: Long, title: String, command: String): Boolean {
        val idx = _snippets.indexOfFirst { it.id == id }
        if (idx < 0) return false
        _snippets[idx] = Snippet(id, title, command)
        save()
        return true
    }

    /** Hapus snippet by ID (bukan index - anti bug urutan). */
    fun remove(id: Long): Boolean {
        val removed = _snippets.removeAll { it.id == id }
        if (removed) save()
        return removed
    }

    /** Cari snippet by ID. */
    fun get(id: Long): Snippet? = _snippets.firstOrNull { it.id == id }
}
