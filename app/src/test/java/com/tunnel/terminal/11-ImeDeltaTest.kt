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
        if (newValue == last) return Delta("", 0, last)
        return when {
            newValue.startsWith(last) -> {
                val added = newValue.substring(last.length)
                if (added.contains('\n') || added.contains('\r')) {
                    Delta(added, 0, null)
                } else {
                    Delta(added, 0, newValue)
                }
            }
            last.startsWith(newValue) -> {
                Delta("", last.length - newValue.length, newValue)
            }
            else -> {
                // replace: backspace all previous, type new
                val sawEnter = newValue.any { it == '\n' || it == '\r' }
                Delta(newValue, last.length, if (sawEnter) null else newValue)
            }
        }
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
    fun `empty after typed is delete-all not no-op`() {
        // This is the Wave-11 bug path: recompose forced value="" while last="hello"
        val d = plan("hello", "")
        assertEquals(5, d.backspaces)
        assertEquals("", d.syncTo)
    }

    @Test
    fun `backspace one char`() {
        val d = plan("ls", "l")
        assertEquals(1, d.backspaces)
        assertEquals("l", d.syncTo)
    }

    @Test
    fun `ime replace composition clears old then types new`() {
        val d = plan("teh", "the")
        assertEquals(3, d.backspaces)
        assertEquals("the", d.send)
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
