package com.tunnel.terminal

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Wave-18: Single source of truth for terminal cell geometry.
 *
 * BUG (user report): zoom-in/out clipped the bottom of glyphs / last row because
 * row height used 1.2×sp while Android Text still applied includeFontPadding, and
 * rows/cols were computed from the full Box size without subtracting LazyColumn padding.
 *
 * Keep CHAR_WIDTH_EM / LINE_HEIGHT_EM in sync between:
 * - onSizeChanged / LaunchedEffect resize → PTY rows×cols
 * - LazyColumn item height + Text lineHeight
 * - Selection hit-testing
 */
object TerminalLayoutMetrics {
    /** Monospace cell width as fraction of font size (em). */
    /**
     * Conservative cell width. 0.62em under-counted real Droid/Noto monospace
     * (~0.65–0.72em), so PTY cols overflowed the LazyColumn Text and the
     * command line was Clip-truncated on the right.
     */
    const val CHAR_WIDTH_EM = 0.72f
    /**
     * Line box height as fraction of font size.
     * Must be ≥ real glyph box without font padding (~1.0–1.2) plus a little slack.
     */
    const val LINE_HEIGHT_EM = 1.4f
    /** Matches LazyColumn content padding (4.dp each side). */
    const val PAD_DP = 4f
    /**
     * Leave this many rows of free space so the last line (prompt + cursor)
     * is never flush against the ExtraKeys bar / clipped by the viewport.
     */
    const val BOTTOM_ROW_MARGIN = 1.15f

    fun charWidthPx(fontSp: Float, density: Density): Float =
        with(density) { fontSp.sp.toPx() * CHAR_WIDTH_EM }

    fun lineHeightPx(fontSp: Float, density: Density): Float =
        with(density) { fontSp.sp.toPx() * LINE_HEIGHT_EM }

    fun padPx(density: Density): Float =
        with(density) { PAD_DP.dp.toPx() }

    data class Grid(val rows: Int, val cols: Int)

    /**
     * Compute PTY grid from the terminal viewport size in pixels.
     * [widthPx]/[heightPx] are the TerminalScreenView Box size (before content padding).
     */
    fun computeGrid(
        widthPx: Int,
        heightPx: Int,
        fontSp: Float,
        density: Density,
        minRows: Int = 8,
        maxRows: Int = 120,
        minCols: Int = 20,
        maxCols: Int = 300
    ): Grid {
        if (widthPx <= 0 || heightPx <= 0 || fontSp <= 0f) {
            return Grid(rows = 24, cols = 80)
        }
        val pad = padPx(density)
        val usableW = (widthPx - pad * 2f).coerceAtLeast(1f)
        val usableH = (heightPx - pad * 2f).coerceAtLeast(1f)
        val cw = charWidthPx(fontSp, density).coerceAtLeast(1f)
        val lh = lineHeightPx(fontSp, density).coerceAtLeast(1f)
        val cols = (usableW / cw).toInt().coerceIn(minCols, maxCols)
        val rows = ((usableH / lh) - BOTTOM_ROW_MARGIN).toInt().coerceIn(minRows, maxRows)
        return Grid(rows = rows, cols = cols)
    }
}
