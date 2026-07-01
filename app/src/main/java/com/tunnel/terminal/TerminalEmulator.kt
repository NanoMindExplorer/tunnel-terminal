package com.tunnel.terminal

import androidx.compose.ui.graphics.Color

// Merepresentasikan satu sel karakter di layar
data class TerminalCell(
    var char: Char = ' ',
    var color: Color = Color(0xFF00FF00)
)

class TerminalEmulator(val rows: Int = 24, val cols: Int = 80) {
    val screen = Array(rows) { Array(cols) { TerminalCell() } }
    var cursorRow = 0
    var cursorCol = 0
    var currentColor = Color(0xFF00FF00)

    private val ansiRegex = Regex("\u001B\\[([;\\d]*)([A-Za-z])")

    // Fungsi utama untuk memproses output dari C++ PTY
    fun process(data: String) {
        var lastIndex = 0
        ansiRegex.findAll(data).forEach { match ->
            // Proses teks biasa sebelum kode ANSI
            val textBefore = data.substring(lastIndex, match.range.first)
            printText(textBefore)

            // Proses kode ANSI
            val params = match.groupValues[1]
            val command = match.groupValues[2]
            handleAnsiCommand(params, command)
            lastIndex = match.range.last + 1
        }
        // Proses sisa teks setelah ANSI terakhir
        val remainingText = data.substring(lastIndex)
        printText(remainingText)
    }

    private fun printText(text: String) {
        for (char in text) {
            when (char) {
                '\n' -> { cursorCol = 0; cursorRow++ }
                '\r' -> cursorCol = 0
                '\b' -> if (cursorCol > 0) cursorCol--
                '\t' -> cursorCol = ((cursorCol / 4) + 1) * 4
                else -> {
                    if (cursorRow < rows && cursorCol < cols) {
                        screen[cursorRow][cursorCol].char = char
                        screen[cursorRow][cursorCol].color = currentColor
                    }
                    cursorCol++
                    if (cursorCol >= cols) { cursorCol = 0; cursorRow++ }
                }
            }
            if (cursorRow >= rows) cursorRow = rows - 1 // Sederhanakan scroll down
        }
    }

    private fun handleAnsiCommand(params: String, command: Char) {
        val paramList = if (params.isEmpty()) listOf(0) else params.split(";").mapNotNull { it.toIntOrNull() ?: 0 }
        when (command) {
            'm' -> handleColor(paramList) // Warna
            'H', 'f' -> { // Pindah kursor (row;col)
                cursorRow = (paramList.getOrElse(0) { 1 } - 1).coerceIn(0, rows - 1)
                cursorCol = (paramList.getOrElse(1) { 1 } - 1).coerceIn(0, cols - 1)
            }
            'A' -> cursorRow = (cursorRow - paramList[0]).coerceAtLeast(0) // Atas
            'B' -> cursorRow = (cursorRow + paramList[0]).coerceAtMost(rows - 1) // Bawah
            'C' -> cursorCol = (cursorCol + paramList[0]).coerceAtMost(cols - 1) // Kanan
            'D' -> cursorCol = (cursorCol - paramList[0]).coerceAtLeast(0) // Kiri
            'J' -> { // Clear Screen
                if (paramList[0] == 2 || paramList[0] == 0) {
                    screen.forEach { row -> row.forEach { cell -> cell.char = ' ' } }
                    cursorRow = 0; cursorCol = 0
                }
            }
            'K' -> { // Clear Line
                if (cursorRow < rows) {
                    for (i in cursorCol until cols) screen[cursorRow][i].char = ' '
                }
            }
        }
    }

    private fun handleColor(params: List<Int>) {
        params.forEach { code ->
            when (code) {
                0 -> currentColor = Color(0xFF00FF00) // Reset
                30 -> currentColor = Color.Black
                31 -> currentColor = Color(0xFFFF5252)
                32 -> currentColor = Color(0xFF00FF00)
                33 -> currentColor = Color(0xFFFFEB3B)
                34 -> currentColor = Color(0xFF2196F3)
                35 -> currentColor = Color(0xFFE040FB)
                36 -> currentColor = Color(0xFF00BCD4)
                37 -> currentColor = Color.White
            }
        }
    }
}
