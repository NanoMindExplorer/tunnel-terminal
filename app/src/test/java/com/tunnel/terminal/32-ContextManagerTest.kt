package com.tunnel.terminal

import android.content.Context
import android.content.SharedPreferences
import org.junit.Test
import org.junit.Assert.*

/**
 * v9.2.0: Unit tests for ContextManager mention parsing.
 * Tests: parseMentions, stripMentions, MentionType, ResolvedMention.
 *
 * Uses same mock pattern as PermissionManagerTest (ContextWrapper + in-memory prefs).
 */
class ContextManagerTest {

    /** Create ContextManager with a mock Context (same pattern as 05-PermissionManagerTest). */
    private fun createContextManager(): ContextManager {
        val ctx = object : android.content.ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                return MockSharedPreferences()
            }
        }
        return ContextManager(ctx)
    }

    @Test fun `parseMentions extracts file mention`() {
        val cm = createContextManager()
        val mentions = cm.parseMentions("Hello @file:main.kt world")
        assertEquals(1, mentions.size)
        assertTrue(mentions[0].startsWith("@file:"))
    }

    @Test fun `parseMentions extracts multiple mentions`() {
        val cm = createContextManager()
        val mentions = cm.parseMentions("@file:a.kt and @block:1 and @terminal")
        assertEquals(3, mentions.size)
    }

    @Test fun `parseMentions handles no mentions`() {
        val cm = createContextManager()
        val mentions = cm.parseMentions("just regular text")
        assertTrue(mentions.isEmpty())
    }

    @Test fun `parseMentions extracts block mention with index`() {
        val cm = createContextManager()
        val mentions = cm.parseMentions("See @block:3 for details")
        assertEquals(1, mentions.size)
        assertTrue(mentions[0].contains("@block:3"))
    }

    @Test fun `parseMentions extracts command mention`() {
        val cm = createContextManager()
        val mentions = cm.parseMentions("Run @command:ls -la")
        assertEquals(1, mentions.size)
        assertTrue(mentions[0].contains("@command:"))
    }

    @Test fun `parseMentions extracts terminal mention`() {
        val cm = createContextManager()
        val mentions = cm.parseMentions("Check @terminal output")
        assertEquals(1, mentions.size)
        assertEquals("@terminal", mentions[0])
    }

    @Test fun `parseMentions extracts snippet mention`() {
        val cm = createContextManager()
        val mentions = cm.parseMentions("Use @snippet:deploy")
        assertEquals(1, mentions.size)
        assertTrue(mentions[0].contains("@snippet:"))
    }

    @Test fun `stripMentions removes all mentions`() {
        val cm = createContextManager()
        val text = "Hello @file:main.kt and @block:1 world"
        val stripped = cm.stripMentions(text)
        assertFalse(stripped.contains("@file:"))
        assertFalse(stripped.contains("@block:"))
        assertTrue(stripped.contains("Hello"))
        assertTrue(stripped.contains("world"))
    }

    @Test fun `stripMentions with no mentions returns original`() {
        val cm = createContextManager()
        val text = "just regular text"
        assertEquals(text, cm.stripMentions(text))
    }

    @Test fun `MentionType enum has all expected types`() {
        val types = ContextManager.MentionType.values().map { it.name }
        assertTrue(types.contains("FILE"))
        assertTrue(types.contains("BLOCK"))
        assertTrue(types.contains("COMMAND"))
        assertTrue(types.contains("TERMINAL"))
        assertTrue(types.contains("SNIPPET"))
        assertTrue(types.contains("UNKNOWN"))
    }

    @Test fun `ResolvedMention data class has expected fields`() {
        val rm = ContextManager.ResolvedMention(
            mention = "@file:main.kt",
            type = ContextManager.MentionType.FILE,
            content = "file content here",
            displayName = "main.kt"
        )
        assertEquals("@file:main.kt", rm.mention)
        assertEquals(ContextManager.MentionType.FILE, rm.type)
        assertEquals("file content here", rm.content)
        assertEquals("main.kt", rm.displayName)
    }
}
