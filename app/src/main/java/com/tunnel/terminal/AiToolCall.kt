package com.tunnel.terminal

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.io.File

/**
 * AiToolCall - Representasi satu tool call dari AI.
 *
 * Phase 22: AI tool calling (function calling) — like Claude Code.
 * AI bisa request tool execution: read_file, write_file, run_command, etc.
 * Setiap tool call butuh permission dari user (kecuali read-only tools).
 *
 * AI tool calling — AI requests tool execution, user grants permission.
 */
data class AiToolCall(
    val tool: String,
    val args: Map<String, String>,
    val reasoning: String = ""
) {
    /** True jika tool read-only (tidak butuh permission). */
    val isReadOnly: Boolean get() = tool in READ_ONLY_TOOLS

    /** True jika tool destructive (butuh explicit permission). */
    val isDestructive: Boolean get() = tool in DESTRUCTIVE_TOOLS

    /** BUG-04 fix: Tampilkan argumen penuh, tidak dipotong di 50 karakter.
     * Jika terlalu panjang, displayTextFull dipakai di dialog dengan scroll. */
    val displayText: String get() = buildString {
        append(tool)
        append("(")
        append(args.entries.joinToString(", ") { "${it.key}=${it.value.take(200)}" })
        append(")")
    }
    /** Full text tanpa truncation — untuk dialog izin. */
    val displayTextFull: String get() = buildString {
        append(tool)
        append("(")
        args.entries.forEachIndexed { idx, (key, value) ->
            if (idx > 0) append(", ")
            append(key)
            append("=\"")
            append(value)
            append("\"")
        }
        append(")")
    }

    companion object {
        val READ_ONLY_TOOLS = setOf("read_file", "list_files", "search_files", "get_terminal_output")
        val DESTRUCTIVE_TOOLS = setOf("write_file", "delete_file", "run_command")

        /** Parse tool call dari AI response.
         * Format: <tool_call>{"tool":"read_file","args":{"path":"/foo"}}</tool_call>
         * Atau: ```tool\n{"tool":"run_command","args":{"cmd":"ls"}}\n```
         */
        fun parseFromResponse(response: String): List<AiToolCall> {
            val calls = mutableListOf<AiToolCall>()

            /* BUG-38 fix: Hanya parse tool calls yang TIDAK berada di dalam
             * markdown code blocks (```...```). AI yang sedang menjelaskan
             * sintaks tool-call akan mengutipnya di code block — itu tidak
             * boleh dieksekusi.
             * Caranya: hapus semua code blocks dari response sebelum parse. */
            val codeBlockRegex = Regex("```[\\s\\S]*?```")
            val responseWithoutCodeBlocks = codeBlockRegex.replace(response, "")

            /* Format 1: <tool_call>{json}</tool_call> */
            val toolCallRegex = Regex("<tool_call>([\\s\\S]*?)</tool_call>")
            toolCallRegex.findAll(responseWithoutCodeBlocks).forEach { match ->
                try {
                    val json = JSONObject(match.groupValues[1].trim())
                    val tool = json.optString("tool", "")
                    val argsJson = json.optJSONObject("args") ?: JSONObject()
                    val args = mutableMapOf<String, String>()
                    argsJson.keys().forEach { key ->
                        args[key] = argsJson.optString(key)
                    }
                    val reasoning = json.optString("reasoning", "")
                    if (tool.isNotBlank()) {
                        calls.add(AiToolCall(tool, args, reasoning))
                    }
                } catch (_: Exception) {
                    /* Invalid JSON — skip. */
                }
            }

            /* BUG-38 fix: Format 2 (```tool blocks) dihapus — code blocks sudah
             * di-strip di atas untuk mencegah eksekusi kutipan AI. */

            return calls
        }

        /** System prompt yang menjelaskan tools tersedia untuk AI. */
        val SYSTEM_PROMPT_TOOLS = """
            Anda memiliki akses ke tools berikut untuk menyelesaikan tugas user:

            TOOLS READ-ONLY (tidak butuh permission):
            - read_file: Baca file. Args: path
            - list_files: List direktori. Args: dir
            - search_files: Cari file berisi pattern. Args: pattern, dir (optional)
            - get_terminal_output: Ambil output terminal terakhir. Args: (none)

            TOOLS DESTRUCTIVE (butuh permission user):
            - write_file: Tulis file. Args: path, content
            - delete_file: Hapus file. Args: path
            - run_command: Jalankan command di terminal. Args: cmd

            Untuk memanggil tool, sertakan dalam response:
            <tool_call>{"tool":"read_file","args":{"path":"/sdcard/test.txt"},"reasoning":"Saya perlu baca file ini untuk memahami konteks"}</tool_call>

            Anda bisa memanggil MULTIPLE tools dalam satu response. Setelah tool call,
            sistem akan eksekusi (dengan permission user jika destructive) dan berikan
            hasilnya di message berikutnya. Anda bisa lanjutkan analisa berdasarkan hasil.

            Contoh workflow:
            1. User: "Fix bug di app.kt"
            2. AI: <tool_call>{"tool":"read_file","args":{"path":"app.kt"}}</tool_call>
            3. System: (file content)
            4. AI: <tool_call>{"tool":"write_file","args":{"path":"app.kt","content":"...fixed..."}}</tool_call>
            5. User grants permission → file written
            6. AI: "Saya sudah fix bugnya. Perubahan: ..."
        """.trimIndent()
    }
}

/**
 * ToolExecutor - Eksekusi AiToolCall.
 *
 * Phase 22: Execute AI tool calls dengan permission flow.
 */
class ToolExecutor(private val context: Context) {
    private val tag = "ToolExecutor"

    /**
     * Eksekusi tool call. Returns result string.
     * Caller bertanggung jawab untuk check permission sebelum call ini.
     *
     * Execute tool call. Caller must check permission first.
     */
    fun execute(call: AiToolCall): String {
        return try {
            when (call.tool) {
                "read_file" -> {
                    val path = call.args["path"] ?: return "Error: path required"
                    val file = File(path)
                    if (!file.exists()) return "Error: file not found: $path"
                    if (!file.canRead()) return "Error: cannot read file (permission denied): $path"
                    file.readText().take(5000)
                }
                "list_files" -> {
                    val dir = call.args["dir"] ?: context.filesDir.absolutePath
                    val file = File(dir)
                    if (!file.exists() || !file.isDirectory) return "Error: not a directory: $dir"
                    file.listFiles()?.joinToString("\n") { f ->
                        "${if (f.isDirectory) "d" else "-"} ${f.name}"
                    } ?: "Error: cannot list directory"
                }
                "search_files" -> {
                    val pattern = call.args["pattern"] ?: return "Error: pattern required"
                    val dir = call.args["dir"] ?: context.filesDir.absolutePath
                    val regex = Regex(pattern, RegexOption.IGNORE_CASE)
                    val results = mutableListOf<String>()
                    File(dir).walkTopDown().take(100).forEach { f ->
                        if (f.isFile && regex.containsMatchIn(f.name)) {
                            results.add(f.absolutePath)
                        }
                    }
                    if (results.isEmpty()) "No files found matching: $pattern"
                    else results.joinToString("\n")
                }
                "get_terminal_output" -> {
                    /* Output terminal akan di-inject oleh caller. */
                    "Use terminal context from system message."
                }
                "write_file" -> {
                    val path = call.args["path"] ?: return "Error: path required"
                    val content = call.args["content"] ?: return "Error: content required"
                    val file = File(path)
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                    "OK: wrote ${content.length} chars to $path"
                }
                "delete_file" -> {
                    val path = call.args["path"] ?: return "Error: path required"
                    val file = File(path)
                    if (!file.exists()) return "Error: file not found: $path"
                    /* BUG-37 fix: Cek return value dari delete(). */
                    if (file.delete()) "OK: deleted $path"
                    else "Error: failed to delete $path (mungkin direktori tidak kosong atau read-only)"
                }
                "run_command" -> {
                    /* Command akan di-execute oleh caller via ShellExecutor. */
                    "Command forwarded to terminal. Check output in terminal view."
                }
                else -> "Error: unknown tool: ${call.tool}"
            }
        } catch (e: Exception) {
            Log.e(tag, "Tool execution failed: ${e.message}")
            "Error: ${e.message}"
        }
    }
}

/**
 * PermissionManager - Manage permission untuk AI tool calls.
 *
 * Phase 22: Permission flow (like Claude Code).
 * User approve/deny sebelum AI run destructive tools.
 */
class PermissionManager(context: Context) {
    private val prefs = context.getSharedPreferences("TunnelPermissions", Context.MODE_PRIVATE)

    /** Permission state per tool. */
    enum class PermissionState { ASK, ALWAYS_ALLOW, ALWAYS_DENY }

    /** BUG-01 fix: Tools yang TIDAK boleh "Always Allow" — terlalu berbahaya
     * jika AI di-inject via indirect prompt injection. */
    private val alwaysDenyAlwaysAllow = setOf("run_command", "delete_file")

    /** Get permission state for tool. */
    fun getPermission(tool: String): PermissionState {
        val state = prefs.getString("tool_$tool", PermissionState.ASK.name)
        return runCatching { PermissionState.valueOf(state!!) }.getOrDefault(PermissionState.ASK)
    }

    /** Set permission state for tool.
     * BUG-01 fix: Tolak ALWAYS_ALLOW untuk run_command/delete_file. */
    fun setPermission(tool: String, state: PermissionState) {
        val effectiveState = if (tool in alwaysDenyAlwaysAllow && state == PermissionState.ALWAYS_ALLOW) {
            PermissionState.ASK // Degrade ke ASK — terlalu berbahaya untuk blanket allow
        } else {
            state
        }
        prefs.edit().putString("tool_$tool", effectiveState.name).apply()
    }

    /** Check if tool call needs permission prompt. */
    fun needsPrompt(call: AiToolCall): Boolean {
        if (call.isReadOnly) return false
        // BUG-01 fix: run_command dan delete_file SELALU butuh prompt
        if (call.tool in alwaysDenyAlwaysAllow) return true
        return getPermission(call.tool) == PermissionState.ASK
    }

    /** Check if tool call is pre-approved. */
    fun isApproved(call: AiToolCall): Boolean {
        if (call.isReadOnly) return true
        // BUG-01 fix: run_command dan delete_file tidak pernah pre-approved
        if (call.tool in alwaysDenyAlwaysAllow) return false
        return getPermission(call.tool) == PermissionState.ALWAYS_ALLOW
    }

    /** BUG-01 fix: Check apakah tool boleh di-"Always Allow". */
    fun canAlwaysAllow(tool: String): Boolean = tool !in alwaysDenyAlwaysAllow

    /** Reset all permissions to ASK. */
    fun resetAll() {
        prefs.edit().clear().apply()
    }
}

/**
 * PermissionDialog - Dialog untuk ask user permission saat AI call destructive tool.
 *
 * Phase 22: Permission prompt UI (like Claude Code).
 */
@Composable
fun PermissionDialog(
    call: AiToolCall,
    theme: TerminalTheme,
    onAllow: () -> Unit,
    onAlwaysAllow: () -> Unit,
    onDeny: () -> Unit
) {
    /* BUG-01 fix: Sembunyikan "Always Allow" untuk run_command/delete_file. */
    val canAlwaysAllow = call.tool !in setOf("run_command", "delete_file")
    val scrollState = androidx.compose.foundation.rememberScrollState()

    AlertDialog(
        onDismissRequest = onDeny,
        modifier = Modifier.background(theme.uiBg, RoundedCornerShape(8.dp)),
        title = {
            Text(
                "🔐 AI Permission Request",
                color = theme.ansi.getOrElse(3) { Color(0xFFFFC107) },
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp
            )
        },
        text = {
            Column {
                Text(
                    "AI wants to execute:",
                    color = theme.uiTextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(8.dp))
                /* BUG-04 fix: Tampilkan argumen penuh dengan scroll, bukan dipotong 50 char. */
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .background(theme.uiSurface, RoundedCornerShape(4.dp))
                        .padding(8.dp)
                        .verticalScroll(scrollState)
                ) {
                    Text(
                        call.displayTextFull,
                        color = theme.ansi.getOrElse(6) { Color(0xFF00BCD4) },
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (call.reasoning.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "AI reasoning: ${call.reasoning}",
                        color = theme.uiTextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    if (call.isDestructive) "⚠ This tool can modify your system."
                    else "ℹ This is a read-only tool.",
                    color = if (call.isDestructive) Color(0xFFFF8A80) else theme.uiTextMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        onClick = onAllow,
                        colors = ButtonDefaults.buttonColors(containerColor = theme.ansi.getOrElse(2) { Color(0xFF4CAF50) })
                    ) { Text("Allow once", color = Color.White, fontSize = 11.sp) }
                    /* BUG-01 fix: Hanya tampilkan "Always allow" untuk tool yang aman. */
                    if (canAlwaysAllow) {
                        Button(
                            onClick = onAlwaysAllow,
                            colors = ButtonDefaults.buttonColors(containerColor = theme.uiAccent)
                        ) { Text("Always allow", color = Color.White, fontSize = 11.sp) }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = onDeny) {
                    Text("Deny", color = Color(0xFFFF5252), fontSize = 11.sp)
                }
            }
        }
    )
}
