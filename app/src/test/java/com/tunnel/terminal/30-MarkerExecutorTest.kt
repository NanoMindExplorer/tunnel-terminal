package com.tunnel.terminal

import org.junit.Test
import org.junit.Assert.*

/**
 * v9.2.0: Unit tests for MarkerExecutor.
 * Tests: wrapCommand, parseMarker, stripMarker, nextMarkerId.
 */
class MarkerExecutorTest {

    @Test fun `wrapCommand produces subshell with marker echo`() {
        val wrapped = MarkerExecutor.wrapCommand("ls -la", "123_abc")
        assertTrue(wrapped.contains("{ ls -la ; }"))
        assertTrue(wrapped.contains("__TT_DONE_123_abc_"))
        assertTrue(wrapped.contains("ec=\$?"))
    }

    @Test fun `parseMarker extracts counter and exit code`() {
        val output = "some output\n__TT_DONE_42_a1b2_0__\n"
        val result = MarkerExecutor.parseMarker(output)
        assertNotNull(result)
        assertEquals(42L, result!!.id)
        assertEquals(0, result.exitCode)
        assertTrue(result.isSuccess)
    }

    @Test fun `parseMarker returns null for no marker`() {
        val output = "just some output without marker"
        assertNull(MarkerExecutor.parseMarker(output))
    }

    @Test fun `parseMarker extracts non-zero exit code`() {
        val output = "error\n__TT_DONE_1_c3d4_127__\n"
        val result = MarkerExecutor.parseMarker(output)
        assertNotNull(result)
        assertEquals(1L, result!!.id)
        assertEquals(127, result.exitCode)
        assertFalse(result.isSuccess)
    }

    @Test fun `parseMarker handles exit code 1`() {
        val output = "__TT_DONE_5_e5f6_1__"
        val result = MarkerExecutor.parseMarker(output)
        assertNotNull(result)
        assertEquals(1, result!!.exitCode)
        assertFalse(result.isSuccess)
    }

    @Test fun `stripMarker removes marker from output`() {
        val output = "line1\n__TT_DONE_1_abcd_0__\nline2"
        val stripped = MarkerExecutor.stripMarker(output)
        assertFalse(stripped.contains("__TT_DONE_"))
        assertTrue(stripped.contains("line1"))
        assertTrue(stripped.contains("line2"))
    }

    @Test fun `stripMarker with no marker returns original`() {
        val output = "just text"
        assertEquals(output, MarkerExecutor.stripMarker(output))
    }

    @Test fun `nextMarkerId produces unique ids`() {
        val id1 = MarkerExecutor.nextMarkerId()
        val id2 = MarkerExecutor.nextMarkerId()
        assertNotEquals(id1, id2)
        assertTrue(id1.contains("_"))
        assertTrue(id2.contains("_"))
    }

    @Test fun `nextMarkerId has counter and hex components`() {
        val id = MarkerExecutor.nextMarkerId()
        val parts = id.split("_")
        assertEquals(2, parts.size)
        // Counter should be a number
        parts[0].toLongOrNull() != null
        // Hex should be 4 chars
        assertEquals(4, parts[1].length)
    }

    @Test fun `CommandResult isSuccess checks exit code`() {
        val r1 = MarkerExecutor.CommandResult("cmd", "output", 0, true, 100)
        assertTrue(r1.isSuccess)
        val r2 = MarkerExecutor.CommandResult("cmd", "output", 1, false, 100)
        assertFalse(r2.isSuccess)
    }

    @Test fun `MarkerResult isSuccess checks exit code`() {
        val r1 = MarkerExecutor.MarkerResult(1, 0, "__TT_DONE_1_abcd_0__")
        assertTrue(r1.isSuccess)
        val r2 = MarkerExecutor.MarkerResult(1, 1, "__TT_DONE_1_abcd_1__")
        assertFalse(r2.isSuccess)
    }

    @Test fun `formatResultForAI includes command and exit code`() {
        val result = MarkerExecutor.CommandResult("ls -la", "file1\nfile2", 0, true, 50)
        val formatted = MarkerExecutor.formatResultForAI(result)
        assertTrue(formatted.contains("ls -la"))
        assertTrue(formatted.contains("file1"))
        assertTrue(formatted.contains("exit"))
        assertTrue(formatted.contains("0"))
    }

    @Test fun `formatResultForAI includes error for non-zero exit`() {
        val result = MarkerExecutor.CommandResult("false", "", 1, false, 10)
        val formatted = MarkerExecutor.formatResultForAI(result)
        assertTrue(formatted.contains("1"))
    }
}
