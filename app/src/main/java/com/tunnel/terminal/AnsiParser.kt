package com.tunnel.terminal

import androidx.compose.ui.graphics.Color

// Data class untuk satu segmen teks beserta warnanya
data class StyledLine(val text: String, val color: Color)

object AnsiParser {
    private val ansiRegex = Regex("\u001B\\[[;\\d]*m")

    // Map kode ANSI ke Color Jetpack Compose
    private val colorMap = mapOf(
        "30" to Color.Black, "31" to Color(0xFFFF5252), "32" to Color(0xFF00FF00),
        "33" to Color(0xFFFFEB3B), "34" to Color(0xFF2196F3), "35" to Color(0xFFE040FB),
        "36" to Color(0xFF00BCD4), "37" to Color.White,
        "90" to Color.Gray, "91" to Color(0xFFFF8A80), "92" to Color(0xFFB9F6CA),
        "93" to Color(0xFFFFF59D), "94" to Color(0xFF82B1FF), "95" to Color(0xFFEA80FC),
        "96" to Color(0xFF84FFFF), "97" to Color.White
    )

    fun parse(line: String): List<StyledLine> {
        val result = mutableListOf<StyledLine>()
        var currentColor = Color(0xFF00FF00) // Default hijau neon
        var lastIndex = 0

        ansiRegex.findAll(line).forEach { match ->
            // Tambahkan teks sebelum kode ANSI
            val textBefore = line.substring(lastIndex, match.range.first)
            if (textBefore.isNotEmpty()) {
                result.add(StyledLine(textBefore, currentColor))
            }

            // Proses kode ANSI
            val code = match.value.removePrefix("\u001B[").removeSuffix("m")
            if (code == "0" || code.isEmpty()) {
                currentColor = Color(0xFF00FF00) // Reset ke default
            } else {
                colorMap[code]?.let { currentColor = it }
            }
            lastIndex = match.range.last + 1
        }

        // Tambahkan sisa teks setelah kode ANSI terakhir
        val remainingText = line.substring(lastIndex)
        if (remainingText.isNotEmpty()) {
            result.add(StyledLine(remainingText, currentColor))
        }

        return if (result.isEmpty()) listOf(StyledLine(line, Color(0xFF00FF00))) else result
    }
}
