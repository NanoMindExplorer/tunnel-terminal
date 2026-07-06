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
        val DESTRUCTIVE_TOOLS = setOf("write_file", "edit_file", "delete_file", "run_command")

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

        /** System prompt yang menjelaskan tools tersedia untuk AI.
         *  Phase 47 (Bagian 1 Fix 2): Hapus contoh path menyesatkan (/sdcard/...),
         *  tambah instruksi workspace yang jelas. */
        val SYSTEM_PROMPT_TOOLS = """
            Anda memiliki akses ke tools berikut untuk menyelesaikan tugas user:

            TOOLS READ-ONLY (tidak butuh permission):
            - read_file: Baca file. Args: path
            - list_files: List direktori. Args: dir (opsional, default = workspace)
            - search_files: Cari file berisi pattern. Args: pattern, dir (opsional)
            - get_terminal_output: Ambil output terminal terakhir. Args: (none)
            - plan_task: Set rencana tugas di awal tugas kompleks. Args: steps (array string, maks 20)
            - update_task_status: Update status langkah rencana. Args: step_id (int), status (PENDING/IN_PROGRESS/DONE/FAILED)

            TOOLS DESTRUCTIVE (butuh permission user):
            - write_file: Tulis file (full overwrite). Args: path, content. Gunakan untuk file BARU atau saat mengganti seluruh isi file.
            - edit_file: Edit parsial file (cari & ganti). Args: path, old_string, new_string. Gunakan untuk mengubah bagian file yang SUDAH ADA — old_string HARUS match persis 1 kali. Jauh lebih hemat token daripada write_file untuk file besar.
            - delete_file: Hapus file. Args: path
            - run_command: Jalankan command di terminal. Args: cmd

            ## DIREKTORI KERJA (PENTING)

            Semua path yang kamu tulis TANPA awalan "/" otomatis berada di workspace
            project privat (selalu bisa ditulis, tidak perlu izin apa pun). Gunakan
            ini sebagai DEFAULT, contoh: {"path":"main.py"} atau {"path":"src/utils.py"}.

            Kalau user secara eksplisit minta simpan ke folder pribadi mereka (Download,
            Documents, dst) — user harus sudah menjalankan "setup-storage" satu kali.
            Baru gunakan path absolut seperti "/storage/emulated/0/Download/x.txt".
            JANGAN pakai path absolut untuk file kerja biasa — selalu default ke path
            relatif workspace.

            Contoh workflow:
            1. User: "Buat file hello.py yang print Hello World"
            2. AI: <tool_call>{"tool":"write_file","args":{"path":"hello.py","content":"print('Hello World')"}}</tool_call>
            3. System: OK: wrote 22 chars to /data/data/com.tunnel.terminal/files/workspace/hello.py
            4. AI: "File hello.py sudah dibuat di workspace kamu."

            Untuk memanggil tool, sertakan dalam response:
            <tool_call>{"tool":"read_file","args":{"path":"main.py"}}</tool_call>

            Anda bisa memanggil MULTIPLE tools dalam satu response. Setelah tool call,
            sistem akan eksekusi (dengan permission user jika destructive) dan berikan
            hasilnya di message berikutnya. Anda bisa lanjutkan analisa berdasarkan hasil.
        """.trimIndent()
    }
}

/**
 * ToolExecutor - Eksekusi AiToolCall.
 *
 * Phase 22: Execute AI tool calls dengan permission flow.
 *
 * Phase 47 (Bagian 1 Fix 1): Path resolution terpusat dengan workspace sandbox.
 *
 * OLD BUG: write_file/read_file/list_files/search_files semua pakai java.io.File
 * mentah. System prompt AI tidak pernah kasih tahu direktori kerja yang pasti —
 * jadi AI menebak path setiap kali, kadang berhasil (kebetulan path-nya valid),
 * kadang gagal diam-diam (file "hilang" karena ditulis ke cwd proses Android yang
 * defaultnya "/", read-only).
 *
 * FIX: Semua path dari AI di-resolve lewat resolvePath() SEBELUM dipakai.
 * - Path relatif (tidak diawali "/") → otomatis masuk workspace privat app
 *   (filesDir/workspace/...). Selalu bisa ditulis, tanpa permission apa pun.
 * - Path absolut yang berada di dalam tree SAF yang sudah di-grant → diizinkan
 *   (user sudah eksplisit beri akses via setup-storage).
 * - Selain dua itu → ditolak dengan pesan jelas, bukan gagal diam-diam.
 */
class ToolExecutor(
    private val context: Context,
    /* Phase 47 (Bagian 1 Fix 1): StorageManager untuk cek granted SAF tree. */
    private val storageManager: StorageManager? = null,
    /* Phase 50 fix (B-4): CheckpointManager untuk undo AI file edits. */
    private val checkpointManager: CheckpointManager? = null,
    /* Phase 57 fix (§4.1): SessionTargetResolver untuk resolve path berdasarkan sesi aktif. */
    private var sessionTargetResolver: SessionTargetResolver? = null,
    /* Phase 58 fix (§4.6): TaskPlanManager untuk plan/act/observe/verify loop. */
    private val taskPlanManager: TaskPlanManager? = null,
    /* Phase 58 fix (§4.1-D): SshShellExecutor reference untuk SFTP file I/O. */
    private var sshExecutor: SshShellExecutor? = null
) {
    private val tag = "ToolExecutor"

    /**
     * Workspace root — direktori kerja privat app yang selalu bisa ditulis.
     * Semua path relatif dari AI otomatis masuk sini.
     */
    val workspaceRoot: File by lazy {
        File(context.filesDir, "workspace").apply { mkdirs() }
    }

    /**
     * Phase 47 (Bagian 1 Fix 1): Resolve path AI ke File asli, dengan sandbox.
     *
     * - Path relatif (tidak diawali "/") → workspaceRoot/path (selalu diizinkan)
     * - Path absolut di dalam workspaceRoot → diizinkan
     * - Path absolut di dalam tree SAF yang sudah di-grant → diizinkan
     * - Selain itu → SecurityException dengan pesan jelas
     */
    private fun resolvePath(rawPath: String): File {
        /* Phase 57 fix (§4.1): Pakai SessionTargetResolver kalau ada (untuk support Ubuntu).
         * Fallback ke resolvePath lama kalau resolver belum di-set (backward compat). */
        val resolver = sessionTargetResolver
        if (resolver != null) {
            val file = resolver.resolvePhysicalPath(rawPath)
            val canonical = try { file.canonicalFile } catch (e: Exception) { file }

            /* Sandbox check: izinkan kalau di dalam workspace, rootfs Ubuntu, atau SAF tree. */
            if (!resolver.isPathAllowed(canonical)) {
                val insideGrantedStorage = storageManager?.isPathWithinGrantedTree(canonical) ?: false
                if (!insideGrantedStorage) {
                    throw SecurityException(
                        "Path '$rawPath' di luar workspace project dan di luar folder yang " +
                        "sudah diizinkan lewat setup-storage. Gunakan path relatif (otomatis " +
                        "masuk workspace: ${workspaceRoot.absolutePath}), atau minta user " +
                        "jalankan setup-storage dulu kalau memang perlu akses ke folder lain."
                    )
                }
            }
            return canonical
        }

        /* Fallback: perilaku lama (workspace lokal Android). */
        val candidate = if (rawPath.startsWith("/")) File(rawPath) else File(workspaceRoot, rawPath)
        val canonical = try { candidate.canonicalFile } catch (e: Exception) { candidate }

        val workspacePath = workspaceRoot.canonicalPath
        val insideWorkspace = try {
            canonical.canonicalPath.startsWith(workspacePath)
        } catch (e: Exception) { false }

        val insideGrantedStorage = storageManager?.isPathWithinGrantedTree(canonical) ?: false

        if (!insideWorkspace && !insideGrantedStorage) {
            throw SecurityException(
                "Path '$rawPath' di luar workspace project dan di luar folder yang " +
                "sudah diizinkan lewat setup-storage. Gunakan path relatif (otomatis " +
                "masuk workspace: ${workspaceRoot.absolutePath}), atau minta user " +
                "jalankan setup-storage dulu kalau memang perlu akses ke folder lain."
            )
        }
        return canonical
    }

    /** Phase 47 (Fix 1): Expose workspaceRoot untuk AgentTaskRunner. */
    fun workspaceRootFile(): File = workspaceRoot

    /** Phase 57 fix (§4.1): Update SessionTargetResolver saat user pindah tab. */
    fun setSessionTargetResolver(resolver: SessionTargetResolver?) {
        sessionTargetResolver = resolver
    }

    /** Phase 58 fix (§4.1-D): Update SshShellExecutor reference saat pindah tab SSH. */
    fun setSshExecutor(executor: SshShellExecutor?) {
        sshExecutor = executor
    }

    /** Phase 57 fix (§4.2): edit_file — edit parsial ala Aider/Claude Code.
     * old_string HARUS match persis 1 kali di file — mencegah AI salah
     * mengganti bagian yang mirip tapi berbeda konteks. */
    private fun executeEditFile(path: String, oldString: String, newString: String): String {
        val file = resolvePath(path)
        if (!file.exists()) return "Error: file not found: ${file.absolutePath}"
        val original = try { file.readText() } catch (e: Exception) {
            return "Error: cannot read file: ${e.message}"
        }
        val occurrences = original.split(oldString).size - 1
        return when {
            occurrences == 0 -> "Error: old_string tidak ditemukan persis di ${file.absolutePath}. Baca ulang file dulu sebelum edit."
            occurrences > 1 -> "Error: old_string muncul $occurrences kali — perlu lebih spesifik (sertakan lebih banyak baris konteks)."
            else -> {
                /* Phase 50 fix (B-4): Save checkpoint sebelum edit. */
                checkpointManager?.saveCheckpointBeforeWrite(file.absolutePath)
                val updated = original.replaceFirst(oldString, newString)
                file.writeText(updated)
                "OK: edited ${file.absolutePath} (replaced 1 occurrence)"
            }
        }
    }

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
                    /* Phase 58 fix (§4.1-D): SFTP untuk tab SSH. */
                    val sessionType = sessionTargetResolver?.sessionType ?: "local"
                    if (sessionType == "ssh" && sshExecutor != null) {
                        val text = sshExecutor!!.readFileRemote(path)
                        if (text != null) text.take(5000) else "Error: cannot read remote file: $path"
                    } else {
                        val file = resolvePath(path)
                        if (!file.exists()) return "Error: file not found: ${file.absolutePath}"
                        if (!file.canRead()) return "Error: cannot read file (permission denied): ${file.absolutePath}"
                        file.readText().take(5000)
                    }
                }
                "list_files" -> {
                    val dirRaw = call.args["dir"] ?: "."
                    /* Phase 58 fix (§4.1-D): SFTP untuk tab SSH. */
                    val sessionType = sessionTargetResolver?.sessionType ?: "local"
                    if (sessionType == "ssh" && sshExecutor != null) {
                        val files = sshExecutor!!.listFilesRemote(dirRaw)
                        if (files != null) files.joinToString("\n") else "Error: cannot list remote directory: $dirRaw"
                    } else {
                        val file = if (dirRaw == ".") workspaceRoot else resolvePath(dirRaw)
                        if (!file.exists() || !file.isDirectory) return "Error: not a directory: ${file.absolutePath}"
                        file.listFiles()?.joinToString("\n") { f ->
                            "${if (f.isDirectory) "d" else "-"} ${f.name}"
                        } ?: "Error: cannot list directory"
                    }
                }
                "search_files" -> {
                    val pattern = call.args["pattern"] ?: return "Error: pattern required"
                    val dirRaw = call.args["dir"] ?: "."
                    /* Phase 58: search_files tetap lokal (SFTP ls tidak support regex search). */
                    val file = if (dirRaw == ".") workspaceRoot else resolvePath(dirRaw)
                    val regex = Regex(pattern, RegexOption.IGNORE_CASE)
                    val results = mutableListOf<String>()
                    file.walkTopDown().take(100).forEach { f ->
                        if (f.isFile && regex.containsMatchIn(f.name)) {
                            results.add(f.absolutePath)
                        }
                    }
                    if (results.isEmpty()) "No files found matching: $pattern"
                    else results.joinToString("\n")
                }
                "plan_task" -> {
                    /* Phase 58 fix (§4.6): Set rencana tugas. */
                    val stepsStr = call.args["steps"] ?: return "Error: steps required (JSON array string)"
                    val steps = try { val arr = org.json.JSONArray(stepsStr); (0 until arr.length()).map { arr[it].toString() } }
                        catch (e: Exception) { return "Error: steps must be JSON array: ${e.message}" }
                    taskPlanManager?.setPlan(steps) ?: "Error: TaskPlanManager not available"
                }
                "update_task_status" -> {
                    /* Phase 58 fix (§4.6): Update status langkah. */
                    val stepId = call.args["step_id"]?.toIntOrNull() ?: return "Error: step_id required (int)"
                    val status = call.args["status"] ?: return "Error: status required (PENDING/IN_PROGRESS/DONE/FAILED)"
                    taskPlanManager?.markStep(stepId, status) ?: "Error: TaskPlanManager not available"
                }
                "get_terminal_output" -> {
                    /* Output terminal akan di-inject oleh caller. */
                    "Use terminal context from system message."
                }
                "write_file" -> {
                    val path = call.args["path"] ?: return "Error: path required"
                    val content = call.args["content"] ?: return "Error: content required"
                    /* Phase 58 fix (§4.1-D): SFTP untuk tab SSH. */
                    val sessionType = sessionTargetResolver?.sessionType ?: "local"
                    if (sessionType == "ssh" && sshExecutor != null) {
                        if (sshExecutor!!.writeFileRemote(path, content)) "OK: wrote ${content.length} chars to $path (remote)"
                        else "Error: failed to write remote file: $path"
                    } else {
                        val file = resolvePath(path)
                        checkpointManager?.saveCheckpointBeforeWrite(file.absolutePath)
                        file.parentFile?.mkdirs()
                        file.writeText(content)
                        "OK: wrote ${content.length} chars to ${file.absolutePath}"
                    }
                }
                "delete_file" -> {
                    val path = call.args["path"] ?: return "Error: path required"
                    /* Phase 58 fix (§4.1-D): SFTP untuk tab SSH. */
                    val sessionType = sessionTargetResolver?.sessionType ?: "local"
                    if (sessionType == "ssh" && sshExecutor != null) {
                        if (sshExecutor!!.deleteFileRemote(path)) "OK: deleted $path (remote)"
                        else "Error: failed to delete remote file: $path"
                    } else {
                        val file = resolvePath(path)
                        if (!file.exists()) return "Error: file not found: ${file.absolutePath}"
                        if (file.delete()) "OK: deleted ${file.absolutePath}"
                        else "Error: failed to delete ${file.absolutePath}"
                    }
                }
                "run_command" -> {
                    /* Command akan di-execute oleh caller via ShellExecutor. */
                    "Command forwarded to terminal. Check output in terminal view."
                }
                "edit_file" -> {
                    /* Phase 57 fix (§4.2): edit_file — edit parsial ala Aider/Claude Code. */
                    val path = call.args["path"] ?: return "Error: path required"
                    val oldString = call.args["old_string"] ?: return "Error: old_string required"
                    val newString = call.args["new_string"] ?: return "Error: new_string required"
                    executeEditFile(path, oldString, newString)
                }
                else -> "Error: unknown tool: ${call.tool}"
            }
        } catch (e: SecurityException) {
            Log.w(tag, "SecurityException in tool execution: ${e.message}")
            "Error: ${e.message}"
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
 *
 * Phase 43 fix (HIGH-05): Permission scope PER-SESSION (tab).
 * OLD BUG: "Always Allow" untuk write_file bersifat GLOBAL — user yang approve
 * di tab Local ikut approve di tab SSH/Ubuntu (konteks risiko berbeda).
 * FIX: Sertakan sessionId di key permission: "tool_<sessionId>_<tool>".
 * Saat pindah tab, permission "Always Allow" dari tab lain tidak berlaku.
 */
class PermissionManager(context: Context) {
    private val prefs = context.getSharedPreferences("TunnelPermissions", Context.MODE_PRIVATE)

    /** Permission state per tool. */
    enum class PermissionState { ASK, ALWAYS_ALLOW, ALWAYS_DENY }

    /** BUG-01 fix: Tools yang TIDAK boleh "Always Allow" — terlalu berbahaya
     * jika AI di-inject via indirect prompt injection. */
    private val alwaysDenyAlwaysAllow = setOf("run_command", "delete_file")

    /** Session ID aktif saat ini. Diperbarui saat user pindah tab. */
    @Volatile
    private var activeSessionId: Int = 0

    /** Update session ID aktif (dipanggil saat user pindah tab). */
    fun setActiveSession(sessionId: Int) {
        activeSessionId = sessionId
    }

    /** Build permission key dengan scope session. */
    private fun permissionKey(tool: String): String = "tool_${activeSessionId}_$tool"

    /** Get permission state for tool (scoped ke session aktif). */
    fun getPermission(tool: String): PermissionState {
        val state = prefs.getString(permissionKey(tool), PermissionState.ASK.name)
        return runCatching { PermissionState.valueOf(state!!) }.getOrDefault(PermissionState.ASK)
    }

    /** Set permission state for tool (scoped ke session aktif).
     * BUG-01 fix: Tolak ALWAYS_ALLOW untuk run_command/delete_file. */
    fun setPermission(tool: String, state: PermissionState) {
        val effectiveState = if (tool in alwaysDenyAlwaysAllow && state == PermissionState.ALWAYS_ALLOW) {
            PermissionState.ASK // Degrade ke ASK — terlalu berbahaya untuk blanket allow
        } else {
            state
        }
        prefs.edit().putString(permissionKey(tool), effectiveState.name).apply()
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

    /** Reset all permissions to ASK (untuk session aktif). */
    fun resetAll() {
        /* Phase 43 fix: Hanya reset permission untuk session aktif, bukan semua session. */
        val keysToRemove = prefs.all.keys.filter { it.startsWith("tool_${activeSessionId}_") }
        val editor = prefs.edit()
        keysToRemove.forEach { editor.remove(it) }
        editor.apply()
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
