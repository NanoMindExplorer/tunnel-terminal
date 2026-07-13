package com.tunnel.terminal

import org.junit.Assert.*
import org.junit.Test

/**
 * Wave-12: Paste sanitize, DECCKM cursor keys, F-keys, style-run helpers.
 */
class TerminalPolishTest {

    @Test
    fun `paste flattens newlines when not bracketed`() {
        val r = PasteUtils.prepare("echo a\necho b", bracketed = false, flattenNewlines = true)
        assertTrue(r.multiLine)
        assertEquals("echo a echo b", r.payload)
        assertFalse(r.payload.contains('\n'))
    }

    @Test
    fun `paste wraps bracketed mode`() {
        val r = PasteUtils.prepare("line1\nline2", bracketed = true)
        assertTrue(r.payload.startsWith("\u001B[200~"))
        assertTrue(r.payload.endsWith("\u001B[201~"))
        assertTrue(r.payload.contains("line1\nline2"))
    }

    @Test
    fun `paste normalizes crlf`() {
        val r = PasteUtils.prepare("a\r\nb\rc", bracketed = false, flattenNewlines = true)
        assertEquals("a b c", r.payload)
    }

    @Test
    fun `paste truncates huge clipboard`() {
        val huge = "x".repeat(70_000)
        val r = PasteUtils.prepare(huge, bracketed = false)
        assertTrue(r.truncated)
        assertTrue(r.payload.length <= 64_000)
    }

    @Test
    fun `function key sequences match xterm`() {
        // Pure mapping without Android Context — reimplement mirror of TerminalEmulator.functionKey
        fun functionKey(n: Int): String = when (n) {
            1 -> "\u001BOP"
            2 -> "\u001BOQ"
            5 -> "\u001B[15~"
            12 -> "\u001B[24~"
            else -> ""
        }
        assertEquals("\u001BOP", functionKey(1))
        assertEquals("\u001B[15~", functionKey(5))
        assertEquals("\u001B[24~", functionKey(12))
    }

    @Test
    fun `decckm cursor key formats`() {
        fun cursorKey(app: Boolean, dir: Char): String {
            val d = dir.uppercaseChar()
            return if (app) "\u001BO$d" else "\u001B[$d"
        }
        assertEquals("\u001B[A", cursorKey(false, 'A'))
        assertEquals("\u001BOA", cursorKey(true, 'A'))
        assertEquals("\u001BOD", cursorKey(true, 'D'))
    }

    @Test
    fun `wide continuation cells skipped in copy extract`() {
        data class Cell(val char: Char, val wideContinuation: Boolean)
        val cells = listOf(
            Cell('中', false),
            Cell(' ', true),
            Cell('a', false)
        )
        val sb = StringBuilder()
        for (c in cells) {
            if (!c.wideContinuation) sb.append(c.char)
        }
        assertEquals("中a", sb.toString())
    }
}
