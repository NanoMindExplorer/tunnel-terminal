package com.tunnel.terminal

import org.junit.Assert.*
import org.junit.Test

/**
 * Wave-27/29: IME delta planner — vanish guards + full-rewrite when cursor not at EOL.
 */
class TerminalImeDeltaTest {

    @Test
    fun `append single and second char`() {
        val a = TerminalImeDelta.plan("", "a")
        assertEquals(0, a.backspaces)
        assertEquals("a", a.typeChars)
        assertEquals("a", a.syncTo)
        assertFalse(a.ignored)
        assertFalse(a.fullRewrite)

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
        assertFalse(p.fullRewrite)
    }

    @Test
    fun `full wipe multi-char is ignored`() {
        val p = TerminalImeDelta.plan("hello", "")
        assertTrue(p.ignored)
        assertEquals(0, p.backspaces)
        assertEquals("hello", p.syncTo)
    }

    @Test
    fun `partial multi-char shrink is ignored`() {
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
    fun `autocorrect replace uses LCP when cursor at end`() {
        val p = TerminalImeDelta.plan("teh", "the", commandBuffer = "teh", cursorLikelyAtEnd = true)
        assertFalse(p.ignored)
        assertFalse(p.fullRewrite)
        assertEquals(2, p.backspaces)
        assertEquals("he", p.typeChars)
        assertEquals("the", p.syncTo)
    }

    @Test
    fun `cursor not at end forces full rewrite`() {
        val p = TerminalImeDelta.plan(
            last = "hello",
            newValue = "hellox",
            commandBuffer = "hello",
            cursorLikelyAtEnd = false
        )
        assertFalse(p.ignored)
        assertTrue(p.fullRewrite)
        assertEquals("hellox", p.typeChars)
        assertEquals("hellox", p.syncTo)
    }

    @Test
    fun `buffer desync alone does not force rewrite at EOL`() {
        /* Wave-31: desync no longer fullRewrite — that made ls/cd vanish. */
        val p = TerminalImeDelta.plan(
            last = "abc",
            newValue = "abcd",
            commandBuffer = "ab",
            cursorLikelyAtEnd = true
        )
        assertFalse(p.fullRewrite)
        assertEquals(0, p.backspaces)
        assertEquals("d", p.typeChars)
    }

    @Test
    fun `suffix replace one char uses LCP`() {
        val p = TerminalImeDelta.plan("hello", "hellp")
        assertFalse(p.fullRewrite)
        assertEquals(1, p.backspaces)
        assertEquals("p", p.typeChars)
    }

    @Test
    fun `enter clears sync target`() {
        val p = TerminalImeDelta.plan("ls", "ls\n")
        assertTrue(p.containsEnter)
        assertEquals("", p.syncTo)
    }

    @Test
    fun `identical is no-op`() {
        val p = TerminalImeDelta.plan("pwd", "pwd")
        assertEquals(0, p.backspaces)
        assertEquals("", p.typeChars)
        assertEquals("pwd", p.syncTo)
    }

    @Test
    fun `ime restart sending only last glyph is ignored`() {
        val p = TerminalImeDelta.plan("ls", "s")
        assertTrue(p.ignored)
        assertEquals(0, p.backspaces)
        assertEquals("ls", p.syncTo)
    }

    @Test
    fun `ime restart with a new glyph appends without deleting the line`() {
        val p = TerminalImeDelta.plan("ls", "c")
        assertFalse(p.ignored)
        assertEquals(0, p.backspaces)
        assertEquals("c", p.typeChars)
        assertEquals("lsc", p.syncTo)
    }

    @Test
    fun `lcp helper`() {
        assertEquals(0, TerminalImeDelta.longestCommonPrefixLen("", "a"))
        assertEquals(3, TerminalImeDelta.longestCommonPrefixLen("hello", "help"))
        assertEquals(5, TerminalImeDelta.longestCommonPrefixLen("hello", "hello"))
    }
}
