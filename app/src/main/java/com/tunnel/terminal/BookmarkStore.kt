package com.tunnel.terminal

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wave-10: Directory bookmarks for quick cd.
 */
object BookmarkStore {
    private const val PREFS = "TunnelBookmarks"
    private const val KEY = "bookmarks_json"
    private const val MAX = 30

    data class Bookmark(val name: String, val path: String)

    fun list(context: Context): List<Bookmark> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name", "").ifBlank { return@mapNotNull null }
                val path = o.optString("path", "").ifBlank { return@mapNotNull null }
                Bookmark(name, path)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, bookmarks: List<Bookmark>) {
        val arr = JSONArray()
        bookmarks.take(MAX).forEach { b ->
            arr.put(JSONObject().put("name", b.name).put("path", b.path))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun add(context: Context, name: String, path: String): Boolean {
        val list = list(context).toMutableList()
        list.removeAll { it.path == path || it.name == name }
        list.add(0, Bookmark(name.ifBlank { path.substringAfterLast('/') }, path))
        while (list.size > MAX) list.removeAt(list.lastIndex)
        save(context, list)
        return true
    }

    fun remove(context: Context, nameOrPath: String): Boolean {
        val list = list(context).toMutableList()
        val removed = list.removeAll { it.name == nameOrPath || it.path == nameOrPath }
        if (removed) save(context, list)
        return removed
    }

    fun formatList(context: Context): String {
        val list = list(context)
        if (list.isEmpty()) return "(no bookmarks — use: bookmark add <name>)"
        return list.mapIndexed { i, b -> "${i + 1}. ${b.name}  →  ${b.path}" }.joinToString("\n")
    }

    fun getByIndex(context: Context, index1Based: Int): Bookmark? {
        val list = list(context)
        if (index1Based < 1 || index1Based > list.size) return null
        return list[index1Based - 1]
    }
}
