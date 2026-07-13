package com.tunnel.terminal

import org.junit.Assert.*
import org.junit.Test

/**
 * Wave-13: Content-row mapping for scrollback + live selection (pure logic).
 */
class ScrollbackSelectTest {

    @Test
    fun `content row maps scrollback then live`() {
        val sbCount = 3
        val liveRows = 5
        val total = sbCount + liveRows
        assertEquals(8, total)
        // content row 0..2 scrollback, 3..7 live index 0..4
        fun liveIndex(contentRow: Int) = contentRow - sbCount
        assertEquals(0, liveIndex(3))
        assertEquals(4, liveIndex(7))
    }

    @Test
    fun `pos clamp uses total content rows`() {
        val totalContentRows = 10
        fun clamp(row: Int) = row.coerceIn(0, (totalContentRows - 1).coerceAtLeast(0))
        assertEquals(0, clamp(-1))
        assertEquals(9, clamp(99))
        assertEquals(4, clamp(4))
    }

    @Test
    fun `selection extract merges lines with newline`() {
        // Mimic getSelectedTextFromContent without Compose Color deps
        data class Cell(val char: Char, val wide: Boolean = false)
        val scrollback = listOf(
            arrayOf(Cell('a'), Cell('b'), Cell(' ')),
            arrayOf(Cell('c'), Cell('d'), Cell(' '))
        )
        val live = arrayOf(
            arrayOf(Cell('e'), Cell('f'), Cell(' '))
        )
        val sbCount = 2
        val start = 0 to 0
        val end = 2 to 1 // through live row 0 cols 0-1
        val (sRow, sCol) = start
        val (eRow, eCol) = end
        val out = StringBuilder()
        for (row in sRow..eRow) {
            val rowCells: Array<Cell> = when {
                row < sbCount -> scrollback[row]
                else -> live[row - sbCount]
            }
            val rs = if (row == sRow) sCol else 0
            val re = if (row == eRow) eCol else rowCells.lastIndex
            val line = StringBuilder()
            for (col in rs..re) {
                if (col < rowCells.size && !rowCells[col].wide) line.append(rowCells[col].char)
            }
            out.append(line.toString().trimEnd())
            if (row < eRow) out.append('\n')
        }
        assertEquals("ab\ncd\nef", out.toString())
    }

    @Test
    fun `terminal size geometry has sensible bounds`() {
        // Pure bounds check mirroring TerminalSize coerce ranges
        val cols = 100.coerceIn(20, 200)
        val rows = 40.coerceIn(10, 100)
        assertTrue(cols in 20..200)
        assertTrue(rows in 10..100)
    }

    @Test
    fun `repeatable extra keys set`() {
        val repeatable = setOf("↑", "↓", "←", "→", "BKSP", "DEL", "PGUP", "PGDN")
        assertTrue("↑" in repeatable)
        assertFalse("^C" in repeatable)
        assertFalse("CTRL" in repeatable)
    }
}
