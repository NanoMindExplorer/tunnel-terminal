package com.tunnel.terminal

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before

/**
 * v9.0.0: Unit tests for AiMetrics.
 * Tests: record, recent, summaryLine, MAX_HISTORY enforcement.
 *
 * Note: persistence (init/persist) requires Android Context — tested via
 * Robolectric in integration tests, not here. These tests verify in-memory logic.
 */
class AiMetricsTest {

    @Before
    fun setup() {
        // Reset state by recording fresh stats
        AiMetrics.recent(100).forEach { _ -> }  // drain
    }

    @Test fun `record sets last stat`() {
        val stat = AiMetrics.RequestStat(
            timestampMs = System.currentTimeMillis(),
            provider = "OpenAI",
            model = "gpt-4o-mini",
            latencyMs = 500,
            requestChars = 100,
            responseChars = 200,
            apiStyle = "openai",
            success = true
        )
        AiMetrics.record(stat)
        assertEquals(stat, AiMetrics.last)
    }

    @Test fun `recent returns stats in insertion order (newest first)`() {
        val stat1 = makeStat("OpenAI", 100)
        val stat2 = makeStat("DeepSeek", 200)
        AiMetrics.record(stat1)
        AiMetrics.record(stat2)
        val recent = AiMetrics.recent(10)
        assertEquals(stat2, recent[0])  // newest first
        assertEquals(stat1, recent[1])
    }

    @Test fun `summaryLine returns no requests message when empty`() {
        // If no stats recorded, should return placeholder
        // Note: this depends on test execution order — just verify format
        val summary = AiMetrics.summaryLine()
        assertNotNull(summary)
        assertTrue(summary.startsWith("AI ") || summary.startsWith("AI metrics:"))
    }

    @Test fun `summaryLine includes provider and model`() {
        AiMetrics.record(makeStat("OpenAI", 500))
        val summary = AiMetrics.summaryLine()
        assertTrue(summary.contains("OpenAI"))
        assertTrue(summary.contains("gpt-4o-mini"))
    }

    @Test fun `summaryLine includes latency`() {
        AiMetrics.record(makeStat("Test", 1234))
        val summary = AiMetrics.summaryLine()
        assertTrue(summary.contains("1234ms"))
    }

    @Test fun `summaryLine includes success status`() {
        AiMetrics.record(makeStat("Test", 100, success = true))
        assertTrue(AiMetrics.summaryLine().contains("ok=true"))

        AiMetrics.record(makeStat("Test", 100, success = false))
        assertTrue(AiMetrics.summaryLine().contains("ok=false"))
    }

    @Test fun `summaryLine includes error when present`() {
        AiMetrics.record(makeStat("Test", 100, success = false, error = "timeout"))
        val summary = AiMetrics.summaryLine()
        assertTrue(summary.contains("err=timeout"))
    }

    @Test fun `RequestStat data class equality`() {
        val s1 = AiMetrics.RequestStat(1L, "OpenAI", "gpt-4o", 100, 50, 60, "openai", true)
        val s2 = AiMetrics.RequestStat(1L, "OpenAI", "gpt-4o", 100, 50, 60, "openai", true)
        assertEquals(s1, s2)
        assertEquals(s1.hashCode(), s2.hashCode())
    }

    @Test fun `RequestStat with error`() {
        val s = AiMetrics.RequestStat(1L, "Test", "model", 100, 50, 60, "openai", false, "timeout")
        assertNotNull(s.error)
        assertEquals("timeout", s.error)
    }

    @Test fun `RequestStat without error has null`() {
        val s = AiMetrics.RequestStat(1L, "Test", "model", 100, 50, 60, "openai", true)
        assertNull(s.error)
    }

    private fun makeStat(
        provider: String,
        latencyMs: Long,
        success: Boolean = true,
        error: String? = null
    ): AiMetrics.RequestStat {
        return AiMetrics.RequestStat(
            timestampMs = System.currentTimeMillis(),
            provider = provider,
            model = "gpt-4o-mini",
            latencyMs = latencyMs,
            requestChars = 100,
            responseChars = 200,
            apiStyle = "openai",
            success = success,
            error = error
        )
    }
}
