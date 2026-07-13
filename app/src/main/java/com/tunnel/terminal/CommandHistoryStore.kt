package com.tunnel.terminal

import android.content.Context
import org.json.JSONArray

/**
 * Wave-8: Persist command history across app restarts (shared for autocomplete).
 */
object CommandHistoryStore {
    private const val PREFS = "TunnelCommandHistory"
    private const val KEY = "history_json"
    private const val MAX = 500

    fun load(context: Context): MutableList<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { i -> arr.getString(i) }
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun save(context: Context, history: List<String>) {
        val trimmed = history.takeLast(MAX)
        val arr = JSONArray()
        trimmed.forEach { arr.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, arr.toString())
            .apply()
    }

    fun append(context: Context, command: String) {
        if (command.isBlank()) return
        val list = load(context)
        if (list.isEmpty() || list.last() != command) {
            list.add(command)
        }
        while (list.size > MAX) list.removeAt(0)
        save(context, list)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY)
            .apply()
    }

    fun seedInto(session: TerminalSession, context: Context) {
        val loaded = load(context)
        if (loaded.isEmpty()) return
        session.commandHistory.clear()
        session.commandHistory.addAll(loaded)
    }
}
