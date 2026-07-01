package com.tunnel.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

data class TerminalCell(
    var char: Char = ' ',
    var color: Color = Color(0xFF00FF00)
)

class TerminalEmulator {
    var rows: Int = 24
        private set
    var cols: Int = 80
        private set
    var fontSize: TextUnit = 12.sp
        private set

    private var screen = Array(rows) { Array(cols) { TerminalCell() } }
    var cursorRow = 0
    var cursorCol = 0
    var currentColor = Color(0xFF00FF00)

    private val ansiRegex = Regex("\u001B\\[([;\\d]*)([A-Za-z])")

    fun resize(newRows: Int, newCols: Int, newFontSize: TextUnit) {
        if (newRows <= 0 || newCols <= 0) return
        if (newRows == rows && newCols == cols && newFontSize == fontSize) return
        
        val newScreen = Array(newRows) { Array(newCols) { TerminalCell() } }
        for (r in 0 until minOf(rows, newRows)) {
            for (c in 0 until minOf(cols, newCols)) {
                newScreen[r][c] = screen[r][c]
            }
        }
        
        screen = newScreen
        rows = newRows
        cols = newCols
        fontSize = newFontSize
        
        if (cursorRow >= rows) cursorRow = rows - 1
        if (cursorCol >= cols) cursorCol = cols - 1
    }

    fun getScreen(): Array<Array<TerminalCell>> = screen

    fun process(data: String) {
        var lastIndex = 0
        ansiRegex.findAll(data).forEach { match ->
            val textBefore = data.substring(lastIndex, match.range.first)
            printText(textBefore)
            val params = match.groupValues[1]
            val command = match.groupValues[2]
            handleAnsiCommand(params, command)
            lastIndex = match.range.last + 1
        }
        val remainingText = data.substring(lastIndex)
        printText(remainingText)
    }

    private fun printText(text: String) {
        for (c in text) { // Ubah char menjadi c
            when (c) {
                '\n' -> { cursorCol = 0; cursorRow++ }
                '\r' -> cursorCol = 0
                '\b' -> if (cursorCol > 0) cursorCol--
                '\t' -> cursorCol = ((cursorCol / 4) + 1) * 4
                else -> {
                    if (cursorRow < rows && cursorCol < cols) {
                        screen[cursorRow][cursorCol].char = c
                        screen[cursorRow][cursorCol].color = currentColor
                    }
                    cursorCol++
                    if (cursorCol >= cols) { cursorCol = 0; cursorRow++ }
                }
            }
            if (cursorRow >= rows) {
                for (i in 0 until rows - 1) { screen[i] = screen[i + 1].copyOf() }
                screen[rows - 1] = Array(cols) { TerminalCell() }
                cursorRow = rows - 1
            }
        }
    }

    private fun handleAnsiCommand(params: String, command: Char) {
        val paramList = if (params.isEmpty()) listOf(0) else params.split(";").mapNotNull { it.toIntOrNull() ?: 0 }
        when (command) {
            'm' -> handleColor(paramList)
            'H', 'f' -> {
                cursorRow = (paramList.getOrElse(0) { 1 } - 1).coerceIn(0, rows - 1)
                cursorCol = (paramList.getOrElse(1) { 1 } - 1).coerceIn(0, cols - 1)
            }
            'A' -> cursorRow = (cursorRow - paramList[0]).coerceAtLeast(0)
            'B' -> cursorRow = (cursorRow + paramList[0]).coerceAtMost(rows - 1)
            'C' -> cursorCol = (cursorCol + paramList[0]).coerceAtMost(cols - 1)
            'D' -> cursorCol = (cursorCol - paramList[0]).coerceAtLeast(0)
            'J' -> {
                if (paramList[0] == 2 || paramList[0] == 0) {
                    screen.forEach { row -> row.forEach { cell -> cell.char = ' ' } }
                    cursorRow = 0; cursorCol = 0
                }
            }
            'K' -> { if (cursorRow < rows) { for (i in cursorCol until cols) screen[cursorRow][i].char = ' ' } }
        }
    }

    private fun handleColor(params: List<Int>) {
        params.forEach { code ->
            when (code) {
                0 -> currentColor = Color(0xFF00FF00)
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
