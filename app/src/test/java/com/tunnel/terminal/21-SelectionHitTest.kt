package com.tunnel.terminal

import org.junit.Assert.*
import org.junit.Test

/**
 * Wave-20b: Selection hit-test must map touch Y to the *visible* LazyList item,
 * not a drifted firstIdx + y/charH estimate (that selected the line above).
 */
class SelectionHitTest {

    private fun items(vararg triples: Triple<Int, Int, Int>) =
        triples.map { TerminalSelectionHitTest.VisibleItem(it.first, it.second, it.third) }

    @Test
    fun `row maps to item containing localY`() {
        // Three rows of height 40: indices 10,11,12 at offsets 0,40,80
        val visible = items(
            Triple(10, 0, 40),
            Triple(11, 40, 40),
            Triple(12, 80, 40)
        )
        assertEquals(10, TerminalSelectionHitTest.resolveRow(10f, visible, 100, 40f, 10, 0))
        assertEquals(11, TerminalSelectionHitTest.resolveRow(45f, visible, 100, 40f, 10, 0))
        assertEquals(12, TerminalSelectionHitTest.resolveRow(90f, visible, 100, 40f, 10, 0))
    }

    @Test
    fun `partially scrolled first item still maps correctly`() {
        // first item index 5 starts at offset -15 (15px scrolled off)
        val visible = items(
            Triple(5, -15, 40),
            Triple(6, 25, 40),
            Triple(7, 65, 40)
        )
        // y=0 is still inside item 5 (from -15 to 25)
        assertEquals(5, TerminalSelectionHitTest.resolveRow(0f, visible, 100, 40f, 5, 15))
        // y=30 is item 6
        assertEquals(6, TerminalSelectionHitTest.resolveRow(30f, visible, 100, 40f, 5, 15))
    }

    @Test
    fun `touch near bottom does not resolve to line above`() {
        // Classic bug: assumed charH too large → row index too small (line above).
        val visible = items(
            Triple(20, 0, 28),
            Triple(21, 28, 28),
            Triple(22, 56, 28),
            Triple(23, 84, 28)
        )
        // Finger at y=70 should be row 22 (56..84), not 21
        assertEquals(22, TerminalSelectionHitTest.resolveRow(70f, visible, 50, 40f, 20, 0))
        // Old formula with wrong charH=40: firstIdx + (70/40)=20+1=21 ← the bug
        val oldBuggy = 20 + (70f / 40f).toInt()
        assertEquals(21, oldBuggy)
        assertNotEquals(oldBuggy, TerminalSelectionHitTest.resolveRow(70f, visible, 50, 40f, 20, 0))
    }

    @Test
    fun `cell width prefers viewport over em estimate`() {
        val w = TerminalSelectionHitTest.cellWidthPx(viewportWidthPx = 800, cols = 80, fallbackCharW = 5f)
        assertEquals(10f, w, 0.01f)
    }

    @Test
    fun `posToCell integrates row and col`() {
        val visible = items(
            Triple(0, 0, 30),
            Triple(1, 30, 30)
        )
        val (row, col) = TerminalSelectionHitTest.posToCell(
            localX = 25f,
            localY = 35f,
            visibleItems = visible,
            viewportWidthPx = 100,
            cols = 10,
            totalRows = 5,
            fallbackCharW = 8f,
            fallbackCharH = 30f
        )
        assertEquals(1, row)
        assertEquals(2, col) // 25 / (100/10) = 2.5 → 2
    }

    @Test
    fun `row top bottom from visible geometry`() {
        val visible = items(Triple(3, 10, 40))
        assertEquals(10f, TerminalSelectionHitTest.rowTopInViewport(3, visible, 40f)!!, 0.01f)
        assertEquals(50f, TerminalSelectionHitTest.rowBottomInViewport(3, visible, 40f)!!, 0.01f)
    }
}
