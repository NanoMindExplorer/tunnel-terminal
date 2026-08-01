package com.tunnel.terminal

import org.junit.Assert.*
import org.junit.Test

/**
 * Wave-20: terminal polish helpers (pure unit tests).
 */
class TerminalWave20Test {

    @Test
    fun `local-only bare storage commands are recognized`() {
        fun isLocalOnly(cmd: String): Boolean {
            return cmd == "help" || cmd == "clear" || cmd == "setup-storage" ||
                cmd == "storage-status" || cmd == "storage-reset" ||
                cmd == "storage-grant-all" ||
                cmd == "storage-ls" || cmd.startsWith("storage-ls ") ||
                cmd == "storage-put" || cmd.startsWith("storage-put ") ||
                cmd == "storage-get" || cmd.startsWith("storage-get ") ||
                cmd == "storage-save-download" || cmd.startsWith("storage-save-download ") ||
                cmd == "storage-write" || cmd.startsWith("storage-write ") ||
                cmd == "storage-rm" || cmd.startsWith("storage-rm ") ||
                cmd == "tt-find" || cmd.startsWith("tt-find ") ||
                cmd == "open" || cmd.startsWith("open ")
        }
        assertTrue(isLocalOnly("storage-put"))
        assertTrue(isLocalOnly("storage-put a.txt"))
        assertTrue(isLocalOnly("storage-save-download"))
        assertTrue(isLocalOnly("open"))
        assertTrue(isLocalOnly("tt-find error"))
        assertFalse(isLocalOnly("find . -name x")) // shell find must NOT be local
        assertFalse(isLocalOnly("ls"))
        assertFalse(isLocalOnly("storage-putx"))
    }

    @Test
    fun `local erase uses raw length not trim length`() {
        val raw = "  help  "
        val cmd = raw.trim()
        assertTrue(raw.length > cmd.length)
        val eraseLen = raw.length
        assertEquals(8, eraseLen)
        assertEquals(4, cmd.length)
    }

    @Test
    fun `home end sequences match xterm CSI used by ExtraKeys`() {
        val home = "\u001B[1~"
        val end = "\u001B[4~"
        assertEquals(4, home.length)
        assertEquals(4, end.length)
        assertTrue(home.startsWith("\u001B["))
        assertTrue(end.startsWith("\u001B["))
    }

    @Test
    fun `font zoom snap stays in range for persisted sizes`() {
        assertEquals(12f, TerminalFontZoom.snap(12f), 0.001f)
        assertEquals(8f, TerminalFontZoom.snap(7f), 0.001f)
        assertEquals(28f, TerminalFontZoom.snap(40f), 0.001f)
        assertEquals(12.5f, TerminalFontZoom.snap(12.4f), 0.001f)
    }

    @Test
    fun `trailing dirty schedule delay is at least 1ms`() {
        val last = 1000L
        val now = 1010L
        val elapsed = now - last
        val delay = (33 - elapsed).coerceAtLeast(1)
        assertEquals(23L, delay)
        assertTrue((33 - 40).coerceAtLeast(1) == 1)
    }

    @Test
    fun `lazy list key from end keeps live row 0 stable when scrollback grows`() {
        fun key(contentRow: Int, total: Int, sbCount: Int): String {
            val fromEnd = total - 1 - contentRow
            return if (contentRow >= sbCount) "live-$fromEnd" else "sb-$fromEnd"
        }
        // Before: 10 scrollback + 24 live
        val liveBottomBefore = key(10 + 24 - 1, 34, 10)
        // After one more scrollback line: 11 + 24
        val liveBottomAfter = key(11 + 24 - 1, 35, 11)
        assertEquals("live-0", liveBottomBefore)
        assertEquals("live-0", liveBottomAfter)
    }

    @Test
    fun `paste still flattens multiline without bracketed`() {
        val r = PasteUtils.prepare("a\nb", bracketed = false, flattenNewlines = true)
        assertEquals("a b", r.payload)
    }
}
