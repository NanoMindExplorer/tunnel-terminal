package com.tunnel.terminal

import org.junit.Assert.*
import org.junit.Test

/**
 * Wave-15: Unicode width + LazyColumn content-row mapping helpers.
 */
class UnicodeLazyTest {

    @Test
    fun `ascii width is 1`() {
        assertEquals(1, CharDisplayWidth.ofCodePoint('a'.code))
        assertEquals(1, CharDisplayWidth.of('Z'))
    }

    @Test
    fun `cjk width is 2`() {
        assertEquals(2, CharDisplayWidth.ofCodePoint('中'.code))
        assertEquals(2, CharDisplayWidth.ofCodePoint(0xAC00)) // Hangul
    }

    @Test
    fun `combining mark width is 0`() {
        assertEquals(0, CharDisplayWidth.ofCodePoint(0x0301)) // combining acute
        assertEquals(0, CharDisplayWidth.ofCodePoint(0x200D)) // ZWJ
        assertEquals(0, CharDisplayWidth.ofCodePoint(0xFE0F)) // VS16
    }

    @Test
    fun `non-bmp emoji treated as width 2`() {
        // U+1F600 😀
        assertEquals(2, CharDisplayWidth.ofCodePoint(0x1F600))
    }

    @Test
    fun `glyph cell stores multi-unit string`() {
        val cell = TerminalCell(glyph = "中")
        assertEquals("中", cell.displayText())
        cell.glyph += "\u0301"
        assertTrue(cell.displayText().length >= 2)
        assertEquals('中', cell.char)
    }

    @Test
    fun `wide continuation display empty`() {
        val cell = TerminalCell(glyph = " ", wideContinuation = true)
        assertEquals("", cell.displayText())
    }

    @Test
    fun `lazy content row from first visible`() {
        fun contentRow(firstIdx: Int, firstOff: Int, touchY: Float, charH: Float, padding: Float, max: Int): Int {
            val adjustedY = touchY - padding + firstOff
            return (firstIdx + (adjustedY / charH).toInt()).coerceIn(0, max)
        }
        assertEquals(10, contentRow(10, 0, 5f, 20f, 0f, 100))
        assertEquals(11, contentRow(10, 0, 25f, 20f, 0f, 100))
        assertEquals(11, contentRow(10, 15, 5f, 20f, 0f, 100))
    }
}
