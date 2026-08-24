package com.tunnel.terminal

/**
 * v9.4.1: Shared success heuristics for AI tool / MCP results.
 */
object AiToolResult {

    private val failPrefixes = listOf(
        "Error:", "Error ", "ERROR:", "ERROR ",
        "Ditolak", "DITOLAK",
        "MCP error", "MCP invoke error",
        "SecurityException"
    )

    fun looksSuccessful(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (failPrefixes.any { t.startsWith(it) }) return false
        if (t.startsWith("OK:") || t.startsWith("OK ") || t.startsWith("✓")) return true
        return !t.startsWith("Error:")
    }
}
