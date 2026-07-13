package com.tunnel.terminal

import org.junit.Assert.*
import org.junit.Test

/**
 * Wave-7: Extra pure-JVM tests for tool parsing and prompt cwd parsing helpers.
 */
class AiToolCallAndPromptTest {

    @Test
    fun `parseFromResponse extracts tool_call tags`() {
        val response = """
            I'll write a file.
            <tool_call>{"tool":"write_file","args":{"path":"a.py","content":"print(1)"}}</tool_call>
            Done.
        """.trimIndent()
        val calls = AiToolCall.parseFromResponse(response)
        assertEquals(1, calls.size)
        assertEquals("write_file", calls[0].tool)
        assertEquals("a.py", calls[0].args["path"])
        assertEquals("print(1)", calls[0].args["content"])
    }

    @Test
    fun `parseFromResponse ignores tool_call inside fenced code block`() {
        val response = """
            Example syntax:
            ```
            <tool_call>{"tool":"delete_file","args":{"path":"x"}}</tool_call>
            ```
            Real call:
            <tool_call>{"tool":"list_files","args":{"dir":"."}}</tool_call>
        """.trimIndent()
        val calls = AiToolCall.parseFromResponse(response)
        assertEquals(1, calls.size)
        assertEquals("list_files", calls[0].tool)
    }

    @Test
    fun `read_only tools include grep_content and plan_task`() {
        assertTrue(AiToolCall("grep_content", mapOf("pattern" to "x")).isReadOnly)
        assertTrue(AiToolCall("plan_task", mapOf("steps" to "[]")).isReadOnly)
        assertFalse(AiToolCall("write_file", mapOf("path" to "a")).isReadOnly)
    }

    @Test
    fun `prompt cwd parse supports ubuntu and local styles`() {
        // Mirror Wave-7 parseWorkingDir regexes (kept in sync with MainActivity)
        fun parse(prompt: String): String {
            val patterns = listOf(
                Regex("""tunnel@android:([^\s\$#]+)[\$#]\s*$"""),
                Regex("""[\w.-]+@[\w.-]+:([^\s\$#]+)[\$#]\s*$""")
            )
            for (regex in patterns) {
                val match = regex.find(prompt.trim()) ?: continue
                return match.groupValues[1].trim()
            }
            return ""
        }
        assertEquals("/data/data/com.tunnel.terminal/files/home", parse("tunnel@android:/data/data/com.tunnel.terminal/files/home$ "))
        assertEquals("~/project", parse("root@ubuntu:~/project# "))
        assertEquals("/var/www", parse("user@server:/var/www$"))
        assertEquals("", parse("no prompt here"))
    }

    @Test
    fun `strip marker removes done marker from output`() {
        val id = MarkerExecutor.nextMarkerId()
        val counter = id.substringBefore("_")
        val hex = id.substringAfter("_")
        val raw = "line1\n__TT_DONE_${counter}_${hex}_0__\nline2"
        val cleaned = MarkerExecutor.stripMarker(raw)
        assertFalse(cleaned.contains("__TT_DONE_"))
        assertTrue(cleaned.contains("line1"))
    }
}
