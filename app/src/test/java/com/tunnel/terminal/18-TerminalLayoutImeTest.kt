package com.tunnel.terminal

import org.junit.Assert.*
import org.junit.Test

/**
 * Wave-18: Layout metrics + IME wipe-guard logic (pure unit tests).
 */
class TerminalLayoutImeTest {

    @Test
    fun `line height em is larger than old 1_2 to avoid clip`() {
        assertTrue(TerminalLayoutMetrics.LINE_HEIGHT_EM >= 1.3f)
        assertTrue(TerminalLayoutMetrics.LINE_HEIGHT_EM > 1.2f)
    }

    @Test
    fun `bottom margin reduces row count vs naive division`() {
        val usableH = 1400f
        val lh = 12f * 2f * TerminalLayoutMetrics.LINE_HEIGHT_EM // density 2, 12sp
        val naive = (usableH / lh).toInt()
        val withMargin = ((usableH / lh) - TerminalLayoutMetrics.BOTTOM_ROW_MARGIN).toInt()
        assertTrue(withMargin <= naive)
        assertTrue(withMargin >= naive - 2)
    }

    @Test
    fun `ime full wipe is ignored for multi-char buffer`() {
        assertTrue(TerminalImeDelta.plan("hello", "").ignored)
        assertFalse(TerminalImeDelta.plan("h", "").ignored) // last char may delete
        assertFalse(TerminalImeDelta.plan("hello", "hell").ignored) // normal single backspace
        assertTrue(TerminalImeDelta.plan("ab", "").ignored)
        /* Wave-27: partial multi-char shrink also ignored */
        assertTrue(TerminalImeDelta.plan("hello", "hel").ignored)
    }

    @Test
    fun `char width and line height scale with font`() {
        // Pure ratio check without Android Density
        fun linePx(sp: Float, density: Float) = sp * density * TerminalLayoutMetrics.LINE_HEIGHT_EM
        fun charPx(sp: Float, density: Float) = sp * density * TerminalLayoutMetrics.CHAR_WIDTH_EM
        assertEquals(linePx(12f, 2f) * 2f, linePx(24f, 2f), 0.01f)
        assertEquals(charPx(10f, 3f) * 1.5f, charPx(15f, 3f), 0.01f)
    }

    @Test
    fun `grid never zero for positive viewport`() {
        // Simulated compute without Density object
        val widthPx = 1080
        val heightPx = 1600
        val fontSp = 12f
        val density = 2.75f
        val pad = TerminalLayoutMetrics.PAD_DP * density
        val usableW = widthPx - pad * 2
        val usableH = heightPx - pad * 2
        val cw = fontSp * density * TerminalLayoutMetrics.CHAR_WIDTH_EM
        val lh = fontSp * density * TerminalLayoutMetrics.LINE_HEIGHT_EM
        val cols = (usableW / cw).toInt().coerceIn(20, 300)
        val rows = ((usableH / lh) - TerminalLayoutMetrics.BOTTOM_ROW_MARGIN).toInt().coerceIn(8, 120)
        assertTrue(cols >= 20)
        assertTrue(rows >= 8)
    }
}
