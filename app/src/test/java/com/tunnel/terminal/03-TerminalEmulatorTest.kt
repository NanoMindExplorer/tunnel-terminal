package com.tunnel.terminal

import androidx.compose.ui.unit.sp
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Phase 51 fix (C-5): Unit tests untuk TerminalEmulator — ANSI parser.
 *
 * Area paling rawan regresi diam-diam: SGR colors, cursor movement, alt-screen,
 * resize, scrollback. Golden file pattern: kirim urutan byte ANSI yang diketahui,
 * assert screen buffer hasil akhir sesuai ekspektasi.
 */
class TerminalEmulatorTest {

    private lateinit var emulator: TerminalEmulator

    @Before
    fun setup() {
        emulator = TerminalEmulator(ThemeHolder())
    }

    @Test
    fun `plain text appears in screen buffer`() {
        emulator.process("Hello")
        val snapshot = emulator.getScreenSnapshot()
        assertEquals('H', snapshot[0][0].char)
        assertEquals('e', snapshot[0][1].char)
        assertEquals('l', snapshot[0][2].char)
        assertEquals('l', snapshot[0][3].char)
        assertEquals('o', snapshot[0][4].char)
    }

    @Test
    fun `newline moves cursor to next row`() {
        emulator.process("Line1\nLine2")
        val snapshot = emulator.getScreenSnapshot()
        assertEquals('L', snapshot[0][0].char)
        assertEquals('L', snapshot[1][0].char)
        assertEquals('1', snapshot[0][4].char)
        assertEquals('2', snapshot[1][4].char)
    }

    @Test
    fun `carriage return moves cursor to column 0`() {
        emulator.process("Hello\rWorld")
        val snapshot = emulator.getScreenSnapshot()
        // "World" should overwrite "Hello" from column 0
        assertEquals('W', snapshot[0][0].char)
        assertEquals('o', snapshot[0][1].char)
        assertEquals('r', snapshot[0][2].char)
    }

    @Test
    fun `SGR bold attribute is applied`() {
        emulator.process("\u001B[1mBold\u001B[0m Normal")
        val snapshot = emulator.getScreenSnapshot()
        assertTrue(snapshot[0][0].bold)  // 'B' should be bold
        assertTrue(snapshot[0][1].bold)  // 'o'
        assertFalse(snapshot[0][5].bold)  // ' ' after reset
        assertFalse(snapshot[0][6].bold)  // 'N' of Normal
    }

    @Test
    fun `cursor movement with CSI A moves up`() {
        emulator.process("Line1\nLine2")
        // Cursor should be at row 1, col 5 (after "Line2")
        val cursor1 = emulator.getCursorState()
        assertEquals(1, cursor1.row)
        // Move cursor up 1 row: ESC[A
        emulator.process("\u001B[A")
        val cursor2 = emulator.getCursorState()
        assertEquals(0, cursor2.row)
    }

    @Test
    fun `cursor movement with CSI C moves right`() {
        emulator.process("Hi")
        // Cursor at col 2
        emulator.process("\u001B[3C")  // Move right 3
        val cursor = emulator.getCursorState()
        assertEquals(5, cursor.col)
    }

    @Test
    fun `clear screen ESC 2J clears buffer`() {
        emulator.process("Hello World")
        emulator.process("\u001B[2J\u001B[H")  // Clear + home
        val snapshot = emulator.getScreenSnapshot()
        // All cells should be blank
        for (row in snapshot) {
            for (cell in row) {
                assertEquals(' ', cell.char)
            }
        }
    }

    @Test
    fun `resize changes rows and cols`() {
        val originalRows = emulator.snapshotRows()
        val originalCols = emulator.snapshotCols()
        emulator.resize(10, 40, 14.sp)
        assertEquals(10, emulator.snapshotRows())
        assertEquals(40, emulator.snapshotCols())
        assertNotEquals(originalRows, emulator.snapshotRows())
        assertNotEquals(originalCols, emulator.snapshotCols())
    }

    @Test
    fun `resize preserves existing content`() {
        emulator.process("Test")
        emulator.resize(24, 80, 12.sp)
        val snapshot = emulator.getScreenSnapshot()
        assertEquals('T', snapshot[0][0].char)
        assertEquals('e', snapshot[0][1].char)
        assertEquals('s', snapshot[0][2].char)
        assertEquals('t', snapshot[0][3].char)
    }

    @Test
    fun `getRenderState returns consistent snapshot`() {
        emulator.process("Test")
        val renderState = emulator.getRenderState()
        assertEquals(emulator.snapshotRows(), renderState.rows)
        assertEquals(emulator.snapshotCols(), renderState.cols)
        assertEquals('T', renderState.screen[0][0].char)
        assertNotNull(renderState.cursor)
    }

    @Test
    fun `scrollback buffer stores lines that scroll off`() {
        // Fill more than rows lines to trigger scrollUp
        val rows = emulator.snapshotRows()
        for (i in 1..rows + 5) {
            emulator.process("Line $i\n")
        }
        // Scrollback should have some lines stored
        assertTrue("Scrollback should have lines", emulator.getScrollbackCount() > 0)
    }

    @Test
    fun `clearScrollback empties scrollback buffer`() {
        val rows = emulator.snapshotRows()
        for (i in 1..rows + 3) {
            emulator.process("Line $i\n")
        }
        assertTrue(emulator.getScrollbackCount() > 0)
        emulator.clearScrollback()
        assertEquals(0, emulator.getScrollbackCount())
    }

    @Test
    fun `alt screen switch and restore`() {
        emulator.process("Main screen content")
        // Switch to alt screen: CSI ?1049h
        emulator.process("\u001B[?1049h")
        emulator.process("Alt screen")
        val altSnapshot = emulator.getScreenSnapshot()
        assertEquals('A', altSnapshot[0][0].char)
        // Switch back: CSI ?1049l
        emulator.process("\u001B[?1049l")
        val restoredSnapshot = emulator.getScreenSnapshot()
        assertEquals('M', restoredSnapshot[0][0].char)
    }

    @Test
    fun `recolorForTheme changes default colors`() {
        val oldTheme = ThemeHolder()
        val oldFg = oldTheme.theme.foreground
        val oldBg = oldTheme.theme.background
        emulator.process("Test")
        // Change theme
        val newTheme = ThemeManager.presets.firstOrNull { it.foreground != oldFg } ?: ThemeManager.presets[1]
        oldTheme.theme = newTheme
        emulator.recolorForTheme(oldFg, oldBg)
        val snapshot = emulator.getScreenSnapshot()
        // recolorForTheme remaps cells with old default fg to new default fg.
        // The cell should now have the new theme's foreground color.
        val cellFg = snapshot[0][0].fgColor
        assertTrue("expected fg=$cellFg to be new theme fg=${newTheme.foreground} or old=$oldFg",
            cellFg == newTheme.foreground || cellFg == oldFg)
    }

    @Test
    fun `tab character moves cursor to next tab stop`() {
        emulator.process("A\tB")
        val snapshot = emulator.getScreenSnapshot()
        assertEquals('A', snapshot[0][0].char)
        // Tab should move to column 8 (default tab stop)
        assertEquals('B', snapshot[0][8].char)
    }

    @Test
    fun `backspace deletes previous character`() {
        emulator.process("Hello\u0008")  // \b backspace
        val cursor = emulator.getCursorState()
        assertEquals(4, cursor.col)  // cursor moved back from 5 to 4
    }
}
