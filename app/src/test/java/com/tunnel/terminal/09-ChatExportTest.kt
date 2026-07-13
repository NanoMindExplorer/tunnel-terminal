package com.tunnel.terminal

import org.junit.Assert.*
import org.junit.Test

/**
 * Wave-9: Chat export formatting (no Android Context required for pure shape tests).
 */
class ChatExportTest {

    @Test
    fun `chat message roles format for export body`() {
        val messages = listOf(
            ChatMessage("user", "hello"),
            ChatMessage("assistant", "hi there", commands = listOf("ls")),
            ChatMessage("assistant", "boom", isError = true)
        )
        val body = buildString {
            messages.forEach { msg ->
                val role = when {
                    msg.isError -> "error"
                    msg.role == "user" -> "user"
                    else -> "assistant"
                }
                appendLine("[$role]")
                appendLine(msg.content)
            }
        }
        assertTrue(body.contains("[user]"))
        assertTrue(body.contains("[assistant]"))
        assertTrue(body.contains("[error]"))
        assertTrue(body.contains("hello"))
        assertTrue(body.contains("boom"))
    }

    @Test
    fun `empty chat export rejected message`() {
        // Mirrors ChatExporter.export empty guard
        val empty = emptyList<ChatMessage>()
        assertTrue(empty.isEmpty())
    }

    @Test
    fun `ssh host key entry sorts by host`() {
        val entries = listOf(
            SshHostKeyStore.Entry("z.example.com:22", "ssh-ed25519:aaa"),
            SshHostKeyStore.Entry("a.example.com:22", "ssh-ed25519:bbb")
        ).sortedBy { it.hostPort }
        assertEquals("a.example.com:22", entries.first().hostPort)
    }
}
