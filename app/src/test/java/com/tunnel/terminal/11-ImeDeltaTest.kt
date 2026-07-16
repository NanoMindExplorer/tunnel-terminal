package com.tunnel.terminal

import org.junit.Assert.*
import org.junit.Test

/**
 * Wave-11: Pure logic tests for IME delta handling (text-disappear fix).
 *
 * Mirrors applyImeValueChange branch selection without Android/Compose.
 */
class ImeDeltaTest {

    private data class Delta(
        val send: String,       // chars to send to PTY (+ for type, - for backspace count)
        val backspaces: Int,
        val syncTo: String?     // null means field cleared by Enter
    )

    private fun plan(last: String, newValue: String): Delta {
        val p = TerminalImeDelta.plan(last, newValue, commandBuffer = last, cursorLikelyAtEnd = true)
        if (p.ignored) {
            return Delta("", 0, p.syncTo)
        }
        if (p.fullRewrite) {
            return Delta(send = p.syncTo, backspaces = 0, syncTo = if (p.containsEnter) null else p.syncTo)
        }
        return Delta(
            send = p.typeChars,
            backspaces = p.backspaces,
            syncTo = if (p.containsEnter) null else p.syncTo
        )
    }

    @Test
    fun `append single char keeps field in sync`() {
        val d = plan("", "a")
        assertEquals("a", d.send)
        assertEquals(0, d.backspaces)
        assertEquals("a", d.syncTo)
    }

    @Test
    fun `append second char is delta only`() {
        val d = plan("a", "ab")
        assertEquals("b", d.send)
        assertEquals(0, d.backspaces)
        assertEquals("ab", d.syncTo)
    }

    @Test
    fun `empty after typed is ignored wipe not mass backspace`() {
        // Wave-11 path recompose forced value="" while last="hello" — must NOT backspace 5
        val d = plan("hello", "")
        assertEquals(0, d.backspaces)
        assertEquals("hello", d.syncTo)
    }

    @Test
    fun `backspace one char`() {
        val d = plan("ls", "l")
        assertEquals(1, d.backspaces)
        assertEquals("l", d.syncTo)
    }

    @Test
    fun `ime replace composition uses LCP`() {
        val d = plan("teh", "the")
        assertEquals(2, d.backspaces)
        assertEquals("he", d.send)
        assertEquals("the", d.syncTo)
    }

    @Test
    fun `enter clears sync target`() {
        val d = plan("ls", "ls\n")
        assertTrue(d.send.contains('\n'))
        assertNull(d.syncTo)
    }

    @Test
    fun `identical values are no-op`() {
        val d = plan("pwd", "pwd")
        assertEquals("", d.send)
        assertEquals(0, d.backspaces)
        assertEquals("pwd", d.syncTo)
    }
}
