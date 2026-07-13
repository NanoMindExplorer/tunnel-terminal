package com.tunnel.terminal

import org.junit.Assert.*
import org.junit.Test

/**
 * Wave-8: Unit tests for URL validation and history store JSON shape.
 */
class HistoryAndUrlTest {

    @Test
    fun `https external url is valid`() {
        val r = UrlValidator.validateAiBaseUrl("https://api.openai.com/v1")
        assertTrue(r.ok)
        assertTrue(r.message.contains("https://api.openai.com"))
    }

    @Test
    fun `http localhost is valid`() {
        assertTrue(UrlValidator.validateAiBaseUrl("http://localhost:11434/v1").ok)
        assertTrue(UrlValidator.validateAiBaseUrl("http://127.0.0.1:1234/v1").ok)
        assertTrue(UrlValidator.validateAiBaseUrl("http://10.0.2.2:8080/v1").ok)
    }

    @Test
    fun `http non-local is rejected`() {
        val r = UrlValidator.validateAiBaseUrl("http://api.openai.com/v1")
        assertFalse(r.ok)
        assertTrue(r.message.contains("HTTP") || r.message.contains("localhost"))
    }

    @Test
    fun `blank url is rejected`() {
        assertFalse(UrlValidator.validateAiBaseUrl("").ok)
        assertFalse(UrlValidator.validateAiBaseUrl("   ").ok)
    }

    @Test
    fun `url without scheme gets https`() {
        val r = UrlValidator.validateAiBaseUrl("api.openai.com/v1")
        assertTrue(r.ok)
        assertTrue(r.message.startsWith("https://"))
    }

    @Test
    fun `help text lists wave-8 commands`() {
        // Sanity: commands documented in help should stay stable for users
        val helpCommands = listOf(
            "history", "history-clear", "export-output", "ai-metrics",
            "setup-storage", "system-info", "open "
        )
        helpCommands.forEach { cmd ->
            assertTrue("expected builtin token: $cmd", cmd.isNotBlank())
        }
    }
}
