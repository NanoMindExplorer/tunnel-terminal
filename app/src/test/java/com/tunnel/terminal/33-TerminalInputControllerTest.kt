package com.tunnel.terminal

import org.junit.Assert.*
import org.junit.Test

class TerminalInputControllerTest {

    private class Harness(session: String = "local") {
        var buffer = ""
        var written = StringBuilder()
        var imeLast = ""
        var pinned = ""
        var cursorAtEnd = true
        var enterHandled = false
        var entered: String? = null
        val sessionType = session

        val controller = TerminalInputController(
            sessionType = { sessionType },
            getBuffer = { buffer },
            setBuffer = { buffer = it },
            writeRaw = { written.append(it) },
            isCursorAtEnd = { cursorAtEnd },
            setCursorAtEnd = { cursorAtEnd = it },
            getImeLast = { imeLast },
            setImeLast = { imeLast = it },
            pinIme = { pinned = it; imeLast = it },
            onEnterLine = { entered = it },
            isEnterHandledByKey = { enterHandled },
            clearEnterHandled = { enterHandled = false }
        )
    }

    @Test
    fun `typing ls appends only printable chars without Ctrl-E on local`() {
        val h = Harness("local")
        h.controller.onImeTextChange("l")
        h.controller.onImeTextChange("ls")
        assertEquals("ls", h.buffer)
        assertFalse(h.written.toString().contains(5.toChar()))
        assertEquals("ls", h.written.toString())
    }

    @Test
    fun `spurious wipe does not backspace pty`() {
        val h = Harness()
        h.controller.onImeTextChange("hello")
        h.written.clear()
        h.controller.onImeTextChange("")
        assertEquals("hello", h.buffer)
        assertEquals("", h.written.toString())
        assertEquals("hello", h.pinned)
    }

    @Test
    fun `enter invokes process path and clears`() {
        val h = Harness()
        h.controller.onImeTextChange("pwd")
        h.controller.onImeTextChange("pwd\n")
        assertEquals("pwd\n", h.entered)
        assertEquals("", h.buffer)
    }

    @Test
    fun `ime restart of last glyph does not erase the line`() {
        val h = Harness()
        h.controller.onImeTextChange("l")
        h.controller.onImeTextChange("ls")
        h.written.clear()
        h.controller.onImeTextChange("s")
        assertEquals("ls", h.buffer)
        assertEquals("", h.written.toString())
        assertEquals("ls", h.pinned)
    }

    @Test
    fun `enter after extra suffix writes missing chars to pty`() {
        val h = Harness()
        h.controller.onImeTextChange("l")
        h.written.clear()
        h.controller.onImeTextChange("ls\n")
        assertEquals("ls\n", h.entered)
        assertTrue("missing suffix must reach PTY", h.written.toString().contains("s"))
        assertEquals("", h.buffer)
    }
}
