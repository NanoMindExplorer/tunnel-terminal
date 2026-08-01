package com.tunnel.terminal

import org.junit.Test
import org.junit.Assert.*

/**
 * v9.2.0: Unit tests for ContextManager mention parsing.
 * Tests: parseMentions, stripMentions, MentionAutoComplete.
 */
class ContextManagerTest {

    @Test fun `parseMentions extracts file mention`() {
        val mentions = ContextManager.parseMentions("Hello @file:main.kt world")
        assertEquals(1, mentions.size)
        assertTrue(mentions[0].startsWith("@file:"))
    }

    @Test fun `parseMentions extracts multiple mentions`() {
        val mentions = ContextManager.parseMentions("@file:a.kt and @block:1 and @terminal")
        assertEquals(3, mentions.size)
    }

    @Test fun `parseMentions handles no mentions`() {
        val mentions = ContextManager.parseMentions("just regular text")
        assertTrue(mentions.isEmpty())
    }

    @Test fun `parseMentions extracts block mention with index`() {
        val mentions = ContextManager.parseMentions("See @block:3 for details")
        assertEquals(1, mentions.size)
        assertTrue(mentions[0].contains("@block:3"))
    }

    @Test fun `parseMentions extracts command mention`() {
        val mentions = ContextManager.parseMentions("Run @command:ls -la")
        assertEquals(1, mentions.size)
        assertTrue(mentions[0].contains("@command:"))
    }

    @Test fun `parseMentions extracts terminal mention`() {
        val mentions = ContextManager.parseMentions("Check @terminal output")
        assertEquals(1, mentions.size)
        assertEquals("@terminal", mentions[0])
    }

    @Test fun `parseMentions extracts snippet mention`() {
        val mentions = ContextManager.parseMentions("Use @snippet:deploy")
        assertEquals(1, mentions.size)
        assertTrue(mentions[0].contains("@snippet:"))
    }

    @Test fun `stripMentions removes all mentions`() {
        val text = "Hello @file:main.kt and @block:1 world"
        val stripped = ContextManager.stripMentions(text)
        assertFalse(stripped.contains("@file:"))
        assertFalse(stripped.contains("@block:"))
        assertTrue(stripped.contains("Hello"))
        assertTrue(stripped.contains("world"))
    }

    @Test fun `stripMentions with no mentions returns original`() {
        val text = "just regular text"
        assertEquals(text, ContextManager.stripMentions(text))
    }

    @Test fun `MentionAutoComplete returns suggestions for partial file mention`() {
        val blockManager = BlockManager()
        val snippetManager = SnippetManager(org.robolectric.RuntimeEnvironment.getApplication())
        val suggestions = MentionAutoComplete.getSuggestions("@fi", blockManager, snippetManager)
        assertTrue(suggestions.isNotEmpty())
        assertTrue(suggestions.any { it.contains("@file") })
    }

    @Test fun `MentionAutoComplete returns suggestions for partial block mention`() {
        val blockManager = BlockManager()
        val snippetManager = SnippetManager(org.robolectric.RuntimeEnvironment.getApplication())
        val suggestions = MentionAutoComplete.getSuggestions("@bl", blockManager, snippetManager)
        assertTrue(suggestions.isNotEmpty())
        assertTrue(suggestions.any { it.contains("@block") })
    }

    @Test fun `MentionAutoComplete returns empty for non-mention text`() {
        val blockManager = BlockManager()
        val snippetManager = SnippetManager(org.robolectric.RuntimeEnvironment.getApplication())
        val suggestions = MentionAutoComplete.getSuggestions("hello", blockManager, snippetManager)
        assertTrue(suggestions.isEmpty())
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
}
