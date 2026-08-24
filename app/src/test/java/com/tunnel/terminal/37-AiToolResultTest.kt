package com.tunnel.terminal

import org.junit.Assert.*
import org.junit.Test

class AiToolResultTest {
    @Test fun `ok prefix is success`() {
        assertTrue(AiToolResult.looksSuccessful("OK: wrote 12 chars"))
    }

    @Test fun `error prefix is failure`() {
        assertFalse(AiToolResult.looksSuccessful("Error: path required"))
        assertFalse(AiToolResult.looksSuccessful("Ditolak user"))
        assertFalse(AiToolResult.looksSuccessful("MCP error: timeout"))
    }

    @Test fun `empty is failure`() {
        assertFalse(AiToolResult.looksSuccessful("  "))
    }

    @Test fun `neutral list output is success`() {
        assertTrue(AiToolResult.looksSuccessful("file.txt\nnotes.md"))
    }

    @Test fun `file content starting with Failed is not a tool failure`() {
        assertTrue(AiToolResult.looksSuccessful("Failed login attempts: 0\nOK"))
    }
}
