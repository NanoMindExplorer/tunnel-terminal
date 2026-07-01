package com.tunnel.terminal

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Snippet(val title: String, val command: String)

class SnippetManager(context: Context) {
    private val prefs = context.getSharedPreferences("TunnelSnippets", Context.MODE_PRIVATE)
    private val _snippets = mutableListOf<Snippet>()
    val snippets: List<Snippet> get() = _snippets

    init {
        load()
    }

    private fun load() {
        val json = prefs.getString("snippets", "[]")
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            _snippets.add(Snippet(obj.getString("title"), obj.getString("command")))
        }
    }

    private fun save() {
        val arr = JSONArray()
        _snippets.forEach { s ->
            arr.put(JSONObject().put("title", s.title).put("command", s.command))
        }
        prefs.edit().putString("snippets", arr.toString()).apply()
    }

    fun add(title: String, command: String) {
        _snippets.add(Snippet(title, command))
        save()
    }

    fun remove(index: Int) {
        if (index in _snippets.indices) {
            _snippets.removeAt(index)
            save()
        }
    }
}
