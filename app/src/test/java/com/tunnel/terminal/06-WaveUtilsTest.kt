package com.tunnel.terminal

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Wave-6: Unit tests for path sandbox, display width, and AI metrics helpers.
 * Pure JVM — no Robolectric required.
 */
class WaveUtilsTest {

    @Test
    fun `isPathInside requires boundary separator`() {
        val parent = "/data/data/com.tunnel.terminal/files/workspace"
        assertTrue(SessionTargetResolver.isPathInside(parent, parent))
        assertTrue(SessionTargetResolver.isPathInside("$parent/src/main.py", parent))
        assertFalse(SessionTargetResolver.isPathInside("${parent}_evil/x", parent))
        assertFalse(SessionTargetResolver.isPathInside("/tmp/other", parent))
    }

    @Test
    fun `isPathInside trims trailing slash on parent`() {
        val parent = "/data/app/workspace/"
        assertTrue(SessionTargetResolver.isPathInside("/data/app/workspace/a", parent))
        assertFalse(SessionTargetResolver.isPathInside("/data/app/workspace_x/a", parent))
    }

    @Test
    fun `CharDisplayWidth ascii is one`() {
        assertEquals(1, CharDisplayWidth.of('A'))
        assertEquals(1, CharDisplayWidth.of('9'))
        assertEquals(1, CharDisplayWidth.of(' '))
    }

    @Test
    fun `CharDisplayWidth CJK is two`() {
        assertEquals(2, CharDisplayWidth.of('中'))
        assertEquals(2, CharDisplayWidth.of('日'))
        assertEquals(2, CharDisplayWidth.of('한'))
    }

    @Test
    fun `CharDisplayWidth combining mark is zero`() {
        // Combining acute accent
        assertEquals(0, CharDisplayWidth.of('\u0301'))
    }

    @Test
    fun `AiMetrics records and summarizes last request`() {
        AiMetrics.record(
            AiMetrics.RequestStat(
                timestampMs = 1L,
                provider = "OpenAI",
                model = "gpt-4o-mini",
                latencyMs = 123,
                requestChars = 100,
                responseChars = 50,
                apiStyle = "openai",
                success = true
            )
        )
        val last = AiMetrics.last
        assertNotNull(last)
        assertEquals(123, last!!.latencyMs)
        assertTrue(AiMetrics.summaryLine().contains("gpt-4o-mini"))
        assertTrue(AiMetrics.summaryLine().contains("123ms"))
    }

    @Test
    fun `MarkerExecutor wrapCommand includes marker id`() {
        val id = MarkerExecutor.nextMarkerId()
        val wrapped = MarkerExecutor.wrapCommand("echo hi", id)
        assertTrue(wrapped.contains(id))
        assertTrue(wrapped.contains("__TT_DONE_"))
        assertTrue(wrapped.contains("echo hi"))
    }

    @Test
    fun `MarkerExecutor parseMarker extracts exit code`() {
        val id = MarkerExecutor.nextMarkerId()
        val counter = id.substringBefore("_")
        val hex = id.substringAfter("_")
        val output = "hello\n__TT_DONE_${counter}_${hex}_0__\n"
        val parsed = MarkerExecutor.parseMarker(output)
        assertNotNull(parsed)
        assertEquals(0, parsed!!.exitCode)
        assertTrue(parsed.isSuccess)
    }

    @Test
    fun `workspace sibling path is rejected by isPathInside`() {
        val ws = File("/tmp/tt_ws_test_${System.nanoTime()}").apply { mkdirs() }
        val sibling = File(ws.parentFile, ws.name + "_evil").apply { mkdirs() }
        try {
            assertFalse(
                SessionTargetResolver.isPathInside(
                    sibling.canonicalPath + "/secret.txt",
                    ws.canonicalPath
                )
            )
        } finally {
            ws.deleteRecursively()
            sibling.deleteRecursively()
        }
    }
}
