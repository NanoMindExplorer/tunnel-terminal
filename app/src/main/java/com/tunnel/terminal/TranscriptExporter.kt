package com.tunnel.terminal

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Wave-8: Export terminal clean output / command history to app private storage.
 */
object TranscriptExporter {
    data class ExportResult(val ok: Boolean, val path: String, val message: String)

    fun exportSession(
        context: Context,
        session: TerminalSession,
        includeHistory: Boolean = true
    ): ExportResult {
        return try {
            val dir = File(context.filesDir, "exports").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "transcript_${session.sessionType}_${session.id}_$stamp.txt")
            val body = buildString {
                appendLine("Tunnel Terminal export")
                appendLine("session_id=${session.id} type=${session.sessionType}")
                appendLine("exported_at=$stamp")
                appendLine("env=${session.environmentDescription}")
                appendLine("--- history ---")
                if (includeHistory) {
                    session.commandHistory.forEachIndexed { i, cmd ->
                        appendLine("${i + 1}. $cmd")
                    }
                } else {
                    appendLine("(skipped)")
                }
                appendLine("--- terminal output (ANSI stripped) ---")
                appendLine(session.getCleanOutput())
            }
            file.writeText(body)
            ExportResult(true, file.absolutePath, "Exported ${body.length} chars")
        } catch (e: Exception) {
            ExportResult(false, "", "Export failed: ${e.message}")
        }
    }

    fun exportHistoryOnly(context: Context, history: List<String>): ExportResult {
        return try {
            val dir = File(context.filesDir, "exports").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "history_$stamp.txt")
            file.writeText(history.joinToString("\n"))
            ExportResult(true, file.absolutePath, "Exported ${history.size} commands")
        } catch (e: Exception) {
            ExportResult(false, "", "Export failed: ${e.message}")
        }
    }
}
