package com.tunnel.terminal

/**
 * Wave-5 + Wave-15: Approximate terminal column width (wcwidth-lite).
 * 0 = combining / zero-width, 1 = normal, 2 = CJK / many emoji.
 *
 * v9.0.0 fix (H7b): Extracted from TerminalEmulator.kt untuk modularitas.
 * Pure utility — tidak bergantung pada TerminalEmulator internals.
 */
object CharDisplayWidth {
    fun of(ch: Char): Int = ofCodePoint(ch.code)

    /** Wave-15: Width by Unicode code point (handles astral emoji as 2). */
    fun ofCodePoint(cp: Int): Int {
        /* Zero-width joiners / variation selectors / ZWSP. */
        if (cp == 0x200D || cp == 0x200B || cp in 0xFE00..0xFE0F) return 0
        val type = Character.getType(cp)
        if (type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() ||
            type == Character.FORMAT.toInt()
        ) {
            return 0
        }
        /* Common East Asian / fullwidth ranges. */
        if (cp in 0x1100..0x115F ||
            cp in 0x2E80..0xA4CF ||
            cp in 0xAC00..0xD7A3 ||
            cp in 0xF900..0xFAFF ||
            cp in 0xFE10..0xFE19 ||
            cp in 0xFE30..0xFE6F ||
            cp in 0xFF00..0xFF60 ||
            cp in 0xFFE0..0xFFE6
        ) {
            return 2
        }
        /* Most non-BMP (emoji, etc.) occupy 2 columns in modern terminals. */
        if (cp > 0xFFFF) return 2
        return 1
    }
}
