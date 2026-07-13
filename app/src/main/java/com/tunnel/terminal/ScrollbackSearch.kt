package com.tunnel.terminal

/**
 * Wave-14: Pure helpers for searching plain scrollback / screen text.
 */
object ScrollbackSearch {
    data class Hit(val lineIndex: Int, val line: String, val column: Int)

    /**
     * @param lines oldest-first plain text lines
     * @return hits oldest-first
     */
    fun find(
        lines: List<String>,
        query: String,
        ignoreCase: Boolean = true,
        maxHits: Int = 50
    ): List<Hit> {
        if (query.isBlank() || lines.isEmpty()) return emptyList()
        val q = if (ignoreCase) query.lowercase() else query
        val hits = mutableListOf<Hit>()
        for ((i, line) in lines.withIndex()) {
            val hay = if (ignoreCase) line.lowercase() else line
            var from = 0
            while (from <= hay.length) {
                val idx = hay.indexOf(q, from)
                if (idx < 0) break
                hits.add(Hit(i, line, idx))
                if (hits.size >= maxHits) return hits
                from = idx + q.length.coerceAtLeast(1)
            }
        }
        return hits
    }

    fun formatHits(hits: List<Hit>, query: String): String {
        if (hits.isEmpty()) return "(no matches for \"$query\")"
        return buildString {
            appendLine("Found ${hits.size} match(es) for \"$query\":")
            hits.take(30).forEachIndexed { n, h ->
                val preview = h.line.trim().take(80)
                appendLine("  ${n + 1}. L${h.lineIndex + 1}: $preview")
            }
            if (hits.size > 30) appendLine("  … ${hits.size - 30} more")
        }.trimEnd()
    }
}
