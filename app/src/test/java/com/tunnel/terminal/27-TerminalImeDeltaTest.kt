package com.tunnel.terminal

import org.junit.Assert.*
import org.junit.Test

/**
 * Wave-27: IME delta planner — typed text must not partially vanish.
 */
class TerminalImeDeltaTest {

    @Test
    fun `append single and second char`() {
        val a = TerminalImeDelta.plan("", "a")
        assertEquals(0, a.backspaces)
        assertEquals("a", a.typeChars)
        assertEquals("a", a.syncTo)
        assertFalse(a.ignored)

        val b = TerminalImeDelta.plan("a", "ab")
        assertEquals(0, b.backspaces)
        assertEquals("b", b.typeChars)
        assertEquals("ab", b.syncTo)
    }

    @Test
    fun `single backspace allowed`() {
        val p = TerminalImeDelta.plan("ls", "l")
        assertEquals(1, p.backspaces)
        assertEquals("", p.typeChars)
        assertEquals("l", p.syncTo)
        assertFalse(p.ignored)
    }

    @Test
    fun `full wipe multi-char is ignored`() {
        val p = TerminalImeDelta.plan("hello", "")
        assertTrue(p.ignored)
        assertEquals(0, p.backspaces)
        assertEquals("hello", p.syncTo)
    }

    @Test
    fun `partial multi-char shrink is ignored — root partial vanish bug`() {
        /* Gboard/recompose often fires "hello" → "hel" in one callback. */
        val p = TerminalImeDelta.plan("hello", "hel")
        assertTrue(p.ignored)
        assertEquals(0, p.backspaces)
        assertEquals("hello", p.syncTo)
    }

    @Test
    fun `last single char may be deleted`() {
        val p = TerminalImeDelta.plan("h", "")
        assertFalse(p.ignored)
        assertEquals(1, p.backspaces)
        assertEquals("", p.syncTo)
    }

    @Test
    fun `autocorrect replace uses LCP not full wipe`() {
        /* "teh" → "the": keep 't', delete "eh", type "he" */
        val p = TerminalImeDelta.plan("teh", "the")
        assertFalse(p.ignored)
        assertEquals(2, p.backspaces)
        assertEquals("he", p.typeChars)
        assertEquals("the", p.syncTo)
    }

    @Test
    fun `suffix replace one char uses LCP`() {
        val p = TerminalImeDelta.plan("hello", "hellp")
        assertFalse(p.ignored)
        assertEquals(1, p.backspaces)
        assertEquals("p", p.typeChars)
        assertEquals("hellp", p.syncTo)
    }

    @Test
    fun `enter clears sync target`() {
        val p = TerminalImeDelta.plan("ls", "ls\n")
        assertTrue(p.containsEnter)
        assertEquals("", p.syncTo)
        assertTrue(p.typeChars.contains('\n'))
    }

    @Test
    fun `identical is no-op`() {
        val p = TerminalImeDelta.plan("pwd", "pwd")
        assertEquals(0, p.backspaces)
        assertEquals("", p.typeChars)
        assertEquals("pwd", p.syncTo)
    }

    @Test
    fun `two char full wipe ignored`() {
        val p = TerminalImeDelta.plan("ab", "")
        assertTrue(p.ignored)
        assertEquals("ab", p.syncTo)
    }

    @Test
    fun `lcp helper`() {
        assertEquals(0, TerminalImeDelta.longestCommonPrefixLen("", "a"))
        assertEquals(2, TerminalImeDelta.longestCommonPrefixLen("hello", "help"))
        assertEquals(5, TerminalImeDelta.longestCommonPrefixLen("hello", "hello"))
    }
}
