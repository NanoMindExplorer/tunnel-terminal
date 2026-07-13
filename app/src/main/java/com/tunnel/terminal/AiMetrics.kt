package com.tunnel.terminal

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Wave-5: Lightweight observability for AI requests (latency + size proxies for tokens).
 * Not a full token counter (provider-specific) — useful for debugging slow calls.
 */
object AiMetrics {
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

    fun record(stat: RequestStat) {
        last = stat
        history.add(0, stat)
        while (history.size > MAX_HISTORY) {
            history.removeAt(history.lastIndex)
        }
    }

    fun recent(limit: Int = 10): List<RequestStat> = history.take(limit)

    fun summaryLine(): String {
        val s = last ?: return "AI metrics: (no requests yet)"
        return "AI last: ${s.provider}/${s.model} ${s.latencyMs}ms " +
            "req=${s.requestChars}c resp=${s.responseChars}c " +
            "style=${s.apiStyle} ok=${s.success}" +
            (s.error?.let { " err=$it" } ?: "")
    }
}
