package com.tunnel.terminal

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Wave-9: Export AI chat transcript to app private storage.
 */
object ChatExporter {
    data class Result(val ok: Boolean, val path: String, val message: String)

    fun export(context: Context, messages: List<ChatMessage>): Result {
        if (messages.isEmpty()) {
            return Result(false, "", "Chat kosong")
        }
        return try {
            val dir = File(context.filesDir, "exports").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "chat_$stamp.txt")
            val body = buildString {
                appendLine("Tunnel Terminal AI chat export")
                appendLine("exported_at=$stamp")
                appendLine("messages=${messages.size}")
                appendLine("---")
                messages.forEach { msg ->
                    val role = when {
                        msg.isError -> "error"
                        msg.role == "user" -> "user"
                        else -> "assistant"
                    }
                    appendLine("[$role]")
                    appendLine(msg.content)
                    if (msg.commands.isNotEmpty()) {
                        appendLine("commands: ${msg.commands.joinToString(" | ")}")
                    }
                    appendLine()
                }
            }
            file.writeText(body)
            Result(true, file.absolutePath, "Exported ${messages.size} messages")
        } catch (e: Exception) {
            Result(false, "", "Export failed: ${e.message}")
        }
    }
}
