package com.tunnel.terminal

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Wave-16: Font zoom clamp / snap / pinch / step.
 */
class FontZoomTest {

    @Test
    fun `clamp respects min max`() {
        assertEquals(TerminalFontZoom.MIN_SP, TerminalFontZoom.clamp(1f))
        assertEquals(TerminalFontZoom.MAX_SP, TerminalFontZoom.clamp(99f))
        assertEquals(12f, TerminalFontZoom.clamp(12f))
    }

    @Test
    fun `snap to half steps`() {
        assertEquals(12f, TerminalFontZoom.snap(12.1f))
        assertEquals(12.5f, TerminalFontZoom.snap(12.4f))
        assertEquals(13f, TerminalFontZoom.snap(12.8f))
    }

    @Test
    fun `pinch zoom in increases size`() {
        val next = TerminalFontZoom.applyPinch(12f, 1.06f)
        assertTrue(next >= 12f)
        assertTrue(next <= TerminalFontZoom.MAX_SP)
    }

    @Test
    fun `pinch zoom out decreases size`() {
        val next = TerminalFontZoom.applyPinch(14f, 0.94f)
        assertTrue(next <= 14f)
        assertTrue(next >= TerminalFontZoom.MIN_SP)
    }

    @Test
    fun `tiny pinch does not thrash`() {
        val cur = 12f
        val next = TerminalFontZoom.applyPinch(cur, 1.005f)
        // may stay same if below MIN_DELTA after snap
        assertTrue(abs(next - cur) < 1f)
    }

    @Test
    fun `step up and down`() {
        assertEquals(13f, TerminalFontZoom.step(12f, +1))
        assertEquals(11f, TerminalFontZoom.step(12f, -1))
        assertEquals(TerminalFontZoom.MIN_SP, TerminalFontZoom.step(TerminalFontZoom.MIN_SP, -1))
        assertEquals(TerminalFontZoom.MAX_SP, TerminalFontZoom.step(TerminalFontZoom.MAX_SP, +1))
    }

    @Test
    fun `format label`() {
        assertEquals("12sp", TerminalFontZoom.formatLabel(12f))
        assertEquals("12.5sp", TerminalFontZoom.formatLabel(12.5f))
    }

    @Test
    fun `continuous pinch accumulates with local size`() {
        var local = 12f
        // simulate 10 frames of zoom-in (1.1f exceeds damping threshold for visible growth)
        repeat(10) {
            local = TerminalFontZoom.applyPinch(local, 1.1f)
        }
        assertTrue("expected growth, got $local", local > 12f)
        assertTrue(local <= TerminalFontZoom.MAX_SP)
    }
}
