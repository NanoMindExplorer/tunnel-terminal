package com.tunnel.terminal

import androidx.compose.ui.graphics.Color

/**
 * Representasi satu sel terminal dengan karakter, warna foreground, dan style.
 * Terminal cell with char, fg color, bg color, and text style attributes.
 *
 * v9.0.0 fix (H7a): Extracted from TerminalEmulator.kt untuk modularitas.
 * Cell adalah pure data — tidak bergantung pada TerminalEmulator internals.
 */
data class TerminalCell(
    /**
     * Wave-15: Full glyph string (may be multi-code-point with combining marks,
     * or a single non-BMP emoji). Prefer [displayText] when rendering.
     */
    var glyph: String = " ",
    var fgColor: Color = Color(0xFF00FF00),
    var bgColor: Color = Color.Black,
    var bold: Boolean = false,
    var italic: Boolean = false,
    var underline: Boolean = false,
    var reverse: Boolean = false,
    /** Wave-5: true if this cell is the right half of a double-width glyph (CJK/emoji). */
    var wideContinuation: Boolean = false
) {
    /** Back-compat single-char access (first code unit). */
    var char: Char
        get() = glyph.firstOrNull() ?: ' '
        set(value) {
            glyph = value.toString()
        }

    fun displayText(): String = if (wideContinuation) "" else glyph
}
