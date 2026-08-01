package com.tunnel.terminal

import android.content.Context
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Wave-5: Lightweight observability for AI requests (latency + size proxies for tokens).
 * Not a full token counter (provider-specific) — useful for debugging slow calls.
 *
 * v9.0.0 fix (M3): Persist last 30 stats ke SharedPreferences supaya survive app restart.
 * Sebelumnya: history di-memory only, wiped on process death. User tidak bisa lihat
 * stats kemarin. Sekarang: load on init, save on record().
 */
object AiMetrics {
    private const val TAG = "AiMetrics"
    private const val PREFS_NAME = "TunnelAiMetrics"
    private const val KEY_HISTORY = "history_json"
    private const val KEY_LAST = "last_json"

    data class RequestStat(
        val timestampMs: Long,
        val provider: String,
        val model: String,
        val latencyMs: Long,
        val requestChars: Int,
        val responseChars: Int,
        val apiStyle: String,
        val success: Boolean,
        val error: String? = null
    )

    private const val MAX_HISTORY = 30
    private val history = CopyOnWriteArrayList<RequestStat>()

    @Volatile
    var last: RequestStat? = null
        private set

    @Volatile
    private var appContext: Context? = null

    /**
     * v9.0.0 fix (M3): Initialize dengan Application context untuk persistence.
     * Dipanggil sekali dari MainActivity.onCreate() atau TunnelApp.
     * Load history + last dari SharedPreferences.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastJson = prefs.getString(KEY_LAST, null)
            if (lastJson != null) {
                last = parseStat(org.json.JSONObject(lastJson))
            }
            val historyJson = prefs.getString(KEY_HISTORY, null)
            if (historyJson != null) {
                val arr = org.json.JSONArray(historyJson)
                for (i in 0 until arr.length()) {
                    val stat = parseStat(arr.getJSONObject(i))
                    if (stat != null) history.add(stat)
                }
                Log.i(TAG, "Loaded ${history.size} stats from prefs")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load AiMetrics from prefs: ${e.message}")
        }
    }

    fun record(stat: RequestStat) {
        last = stat
        history.add(0, stat)
        while (history.size > MAX_HISTORY) {
            history.removeAt(history.lastIndex)
        }
        /* v9.0.0 fix (M3): Persist to SharedPreferences. */
        persist()
    }

    fun recent(limit: Int = 10): List<RequestStat> = history.take(limit)

    fun summaryLine(): String {
        val s = last ?: return "AI metrics: (no requests yet)"
        return "AI last: ${s.provider}/${s.model} ${s.latencyMs}ms " +
            "req=${s.requestChars}c resp=${s.responseChars}c " +
            "style=${s.apiStyle} ok=${s.success}" +
            (s.error?.let { " err=$it" } ?: "")
    }

    /* === Persistence helpers === */

    private fun persist() {
        val ctx = appContext ?: return
        try {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val arr = org.json.JSONArray()
            history.take(MAX_HISTORY).forEach { stat ->
                arr.put(statToJson(stat))
            }
            prefs.edit()
                .putString(KEY_HISTORY, arr.toString())
                .putString(KEY_LAST, last?.let { statToJson(it).toString() })
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist AiMetrics: ${e.message}")
        }
    }

    private fun statToJson(stat: RequestStat): org.json.JSONObject {
        return org.json.JSONObject()
            .put("timestampMs", stat.timestampMs)
            .put("provider", stat.provider)
            .put("model", stat.model)
            .put("latencyMs", stat.latencyMs)
            .put("requestChars", stat.requestChars)
            .put("responseChars", stat.responseChars)
            .put("apiStyle", stat.apiStyle)
            .put("success", stat.success)
            .apply { stat.error?.let { put("error", it) } }
    }

    private fun parseStat(json: org.json.JSONObject): RequestStat? {
        return try {
            RequestStat(
                timestampMs = json.getLong("timestampMs"),
                provider = json.getString("provider"),
                model = json.getString("model"),
                latencyMs = json.getLong("latencyMs"),
                requestChars = json.getInt("requestChars"),
                responseChars = json.getInt("responseChars"),
                apiStyle = json.getString("apiStyle"),
                success = json.getBoolean("success"),
                error = json.optString("error", null)
            )
        } catch (e: Exception) {
            null
        }
    }
}
