package com.tunnel.terminal

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Wave-13: Shared initial PTY geometry for local / proot / SSH sessions.
 * Avoids 24×80 first paint then jump when Compose resizes.
 *
 * Wave-20: [readPersistedFontSp] so initial rows×cols match zoomed font, not hard-coded 12sp.
 */
object TerminalSize {
    data class Geometry(val rows: Int, val cols: Int)

    /** Same prefs key as MainActivity / TerminalFontZoom. */
    fun readPersistedFontSp(context: Context?): Float {
        if (context == null) return TerminalFontZoom.DEFAULT_SP
        return try {
            TerminalFontZoom.snap(
                context.getSharedPreferences("TunnelUI", Context.MODE_PRIVATE)
                    .getFloat("fontSize", TerminalFontZoom.DEFAULT_SP)
            )
        } catch (_: Exception) {
            TerminalFontZoom.DEFAULT_SP
        }
    }

    fun fromDisplay(
        context: Context?,
        fontSizeSp: Float = 12f,
        minRows: Int = 10,
        maxRows: Int = 100,
        minCols: Int = 20,
        maxCols: Int = 200
    ): Geometry {
        val dm = DisplayMetrics()
        try {
            @Suppress("DEPRECATION")
            (context?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                ?.defaultDisplay?.getMetrics(dm)
        } catch (_: Exception) {
            /* fall through to defaults */
        }
        val density = dm.density.takeIf { it > 0f } ?: 2f
        val widthPx = dm.widthPixels.takeIf { it > 0 } ?: (1080 * density).toInt()
        val heightPx = dm.heightPixels.takeIf { it > 0 } ?: (1920 * density).toInt()
        /* Approximate: ~55% of screen height usable for terminal (bars + extra keys). */
        val usableH = (heightPx * 0.55f).toInt().coerceAtLeast(200)
        val usableW = widthPx
        /* Wave-18: Match TerminalLayoutMetrics em ratios. */
        val charW = (fontSizeSp * density * TerminalLayoutMetrics.CHAR_WIDTH_EM).coerceAtLeast(1f)
        val charH = (fontSizeSp * density * TerminalLayoutMetrics.LINE_HEIGHT_EM).coerceAtLeast(1f)
        val cols = (usableW / charW).toInt().coerceIn(minCols, maxCols)
        val rows = ((usableH / charH) - TerminalLayoutMetrics.BOTTOM_ROW_MARGIN)
            .toInt().coerceIn(minRows, maxRows)
        return Geometry(rows = rows, cols = cols)
    }
}
