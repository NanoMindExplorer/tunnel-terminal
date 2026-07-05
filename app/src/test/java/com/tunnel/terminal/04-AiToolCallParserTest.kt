package com.tunnel.terminal

import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 51 fix (C-5): Unit tests untuk AiToolCall parser.
 *
 * Regression test untuk BUG-38 fix (anti prompt-injection: tool-call di dalam
 * code block tidak boleh dieksekusi). Plus test multiple tool calls, literal
 * </tool_call> di dalam argumen, dan edge cases.
 */
class AiToolCallParserTest {

    @Test
    fun `parse single tool call`() {
        val response = """<tool_call>{"tool":"read_file","args":{"path":"main.py"}}</tool_call>"""
        val calls = AiToolCall.parseFromResponse(response)
        assertEquals(1, calls.size)
        assertEquals("read_file", calls[0].tool)
        assertEquals("main.py", calls[0].args["path"])
    }

    @Test
    fun `parse multiple tool calls`() {
        val response = """
            <tool_call>{"tool":"read_file","args":{"path":"a.py"}}</tool_call>
            <tool_call>{"tool":"write_file","args":{"path":"b.py","content":"print('hi')"}}</tool_call>
        """.trimIndent()
        val calls = AiToolCall.parseFromResponse(response)
        assertEquals(2, calls.size)
        assertEquals("read_file", calls[0].tool)
        assertEquals("a.py", calls[0].args["path"])
        assertEquals("write_file", calls[1].tool)
        assertEquals("b.py", calls[1].args["path"])
    }

    @Test
    fun `tool call inside code block is NOT parsed (BUG-38 regression test)`() {
        val response = """
            Here's an example of how to use tools:
            ```xml
            <tool_call>{"tool":"read_file","args":{"path":"secret.txt"}}</tool_call>
            ```
            But don't execute that — it's just an example.
        """.trimIndent()
        val calls = AiToolCall.parseFromResponse(response)
        assertTrue("Tool call inside code block should NOT be parsed", calls.isEmpty())
    }

    @Test
    fun `tool call outside code block IS parsed even when code block present`() {
        val response = """
            ```python
            print("hello")
            ```
            <tool_call>{"tool":"run_command","args":{"cmd":"echo hi"}}</tool_call>
        """.trimIndent()
        val calls = AiToolCall.parseFromResponse(response)
        assertEquals(1, calls.size)
        assertEquals("run_command", calls[0].tool)
    }

    @Test
    fun `tool call with reasoning field`() {
        val response = """<tool_call>{"tool":"read_file","args":{"path":"config.json"},"reasoning":"Need to check config"}</tool_call>"""
        val calls = AiToolCall.parseFromResponse(response)
        assertEquals(1, calls.size)
        assertEquals("Need to check config", calls[0].reasoning)
    }

    @Test
    fun `invalid JSON in tool call is skipped`() {
        val response = """<tool_call>{invalid json}</tool_call>"""
        val calls = AiToolCall.parseFromResponse(response)
        assertTrue("Invalid JSON should be skipped", calls.isEmpty())
    }

    @Test
    fun `empty tool name is skipped`() {
        val response = """<tool_call>{"tool":"","args":{}}</tool_call>"""
        val calls = AiToolCall.parseFromResponse(response)
        assertTrue("Empty tool name should be skipped", calls.isEmpty())
    }

    @Test
    fun `displayText includes tool name and args`() {
        val call = AiToolCall("read_file", mapOf("path" to "main.py"))
        val display = call.displayText
        assertTrue(display.contains("read_file"))
        assertTrue(display.contains("main.py"))
    }

    @Test
    fun `isReadOnly returns true for read-only tools`() {
        assertTrue(AiToolCall("read_file", emptyMap()).isReadOnly)
        assertTrue(AiToolCall("list_files", emptyMap()).isReadOnly)
        assertTrue(AiToolCall("search_files", emptyMap()).isReadOnly)
        assertFalse(AiToolCall("write_file", emptyMap()).isReadOnly)
        assertFalse(AiToolCall("delete_file", emptyMap()).isReadOnly)
        assertFalse(AiToolCall("run_command", emptyMap()).isReadOnly)
    }

    @Test
    fun `isDestructive returns true for destructive tools`() {
        assertTrue(AiToolCall("write_file", emptyMap()).isDestructive)
        assertTrue(AiToolCall("delete_file", emptyMap()).isDestructive)
        assertTrue(AiToolCall("run_command", emptyMap()).isDestructive)
        assertFalse(AiToolCall("read_file", emptyMap()).isDestructive)
    }

    @Test
    fun `stripMarker removes marker from output`() {
        val output = "ls output\n__TT_DONE_1_a3f2_0__"
        val stripped = MarkerExecutor.stripMarker(output)
        assertEquals("ls output", stripped)
    }

    @Test
    fun `parseMarker extracts exit code from new format`() {
        // Phase 48 format: __TT_DONE_<counter>_<hex4>_<exitcode>__
        val output = "__TT_DONE_1_a3f2_0__"
        val result = MarkerExecutor.parseMarker(output)
        assertNotNull(result)
        assertEquals(1L, result!!.id)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `parseMarker extracts non-zero exit code`() {
        val output = "__TT_DONE_2_b4e1_127__"
        val result = MarkerExecutor.parseMarker(output)
        assertNotNull(result)
        assertEquals(2L, result!!.id)
        assertEquals(127, result.exitCode)
    }

    @Test
    fun `parseMarker returns null for no marker`() {
        val output = "just regular output"
        val result = MarkerExecutor.parseMarker(output)
        assertNull(result)
    }

    @Test
    fun `wrapCommand includes subshell and exit code capture`() {
        val wrapped = MarkerExecutor.wrapCommand("ls -la", "1_a3f2")
        assertTrue(wrapped.contains("{ ls -la ; }"))
        assertTrue(wrapped.contains("ec="))
        assertTrue(wrapped.contains("__TT_DONE_1_a3f2_"))
    }
}
