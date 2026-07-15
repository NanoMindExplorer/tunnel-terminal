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
        /* Wave-2: plan_task / update_task_status are meta tools (no FS/shell side effects). */
        val READ_ONLY_TOOLS = setOf(
            "read_file", "list_files", "search_files", "grep_content", "get_terminal_output",
            "plan_task", "update_task_status"
        )
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
            - read_file: Baca file (maks ~5KB). Args: path
            - list_files: List direktori. Args: dir (opsional, default = workspace/home sesi)
            - search_files: Cari file berdasarkan NAMA (regex). Args: pattern, dir (opsional)
            - grep_content: Cari TEKS di dalam file (content search). Args: pattern, dir (opsional), max_results (opsional)
            - get_terminal_output: Ambil output terminal aktif terkini. Args: (none)
            - plan_task: Set rencana tugas di awal tugas kompleks. Args: steps (array string, maks 20)
            - update_task_status: Update status langkah rencana. Args: step_id (int), status (PENDING/IN_PROGRESS/DONE/FAILED)

            TOOLS DESTRUCTIVE (butuh permission user):
            - write_file: Tulis file (full overwrite). Args: path, content. Gunakan untuk file BARU atau saat mengganti seluruh isi file.
            - edit_file: Edit parsial file (cari & ganti). Args: path, old_string, new_string. Gunakan untuk mengubah bagian file yang SUDAH ADA — old_string HARUS match persis 1 kali. Jauh lebih hemat token daripada write_file untuk file besar.
            - delete_file: Hapus file. Args: path
            - run_command: Jalankan command di terminal AKTIF (local sh / Ubuntu bash / SSH). Args: cmd

            ## DIREKTORI KERJA (PENTING — tergantung tab aktif)

            ### Tab Local (Android shell)
            Path RELATIF → workspace app (filesDir/workspace/...). Contoh: {"path":"hello.py"}
            Path Download absolut hanya setelah setup-storage (SAF).

            ### Tab Ubuntu (proot) — UTAMAKAN INI saat environmentDescription bilang Ubuntu
            Path RELATIF → /root/ di dalam Ubuntu (bukan workspace Android).
            Contoh: write_file path "demo.py" → /root/demo.py di guest.
            Lalu run_command: python3 demo.py  (cwd /root).
            apt: DEBIAN_FRONTEND=noninteractive apt-get install -y <pkg>
            JANGAN gunakan path /data/data/... di run_command Ubuntu.
            /mnt/workspace = workspace Android (bind-mount), opsional.

            ### Tab SSH
            File tools via SFTP; shell via run_command di remote.

            Contoh Ubuntu:
            1. write_file {"path":"hello.py","content":"print('hi')"}
            2. run_command {"cmd":"python3 hello.py"}
            3. OK — file ada di /root/hello.py di sesi Ubuntu.

            Untuk memanggil tool:
            <tool_call>{"tool":"read_file","args":{"path":"main.py"}}</tool_call>

            Anda bisa MULTIPLE tools per response. Setelah eksekusi, lanjutkan dari hasil.
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

    companion object {
        private const val MAX_READ_CHARS = 5000
        private const val MAX_READ_BYTES = 2 * 1024 * 1024 // 2MB hard cap before truncate
        private const val MAX_SEARCH_MATCHES = 100
        private const val MAX_GREP_MATCHES = 40
        private const val MAX_GREP_FILES = 300
    }

    /** Wave-4: Provider for live terminal output (set from MainActivity). */
    @Volatile
    private var terminalOutputProvider: (() -> String)? = null

    fun setTerminalOutputProvider(provider: (() -> String)?) {
        terminalOutputProvider = provider
    }

    /**
     * Workspace root — direktori kerja privat app yang selalu bisa ditulis.
     * Semua path relatif dari AI otomatis masuk sini.
     */
    val workspaceRoot: File by lazy {
        File(context.filesDir, "workspace").apply { mkdirs() }
    }

    /**
     * Phase 47 (Bagian 1 Fix 1) + Wave-19: Resolve path AI ke File asli, dengan sandbox.
     *
     * - Path relatif (tidak diawali "/") → workspaceRoot/path (selalu diizinkan)
     * - Prefix "storage/" → file virtual di bawah tree SAF (absolute mapped path)
     * - Path absolut di dalam workspaceRoot → diizinkan
     * - Path absolut di dalam tree SAF yang sudah di-grant → diizinkan (I/O via SAF)
     * - Selain itu → SecurityException dengan pesan jelas
     */
    private fun resolvePath(rawPath: String): File {
        val sm = storageManager
        /* Wave-19: storage/foo.txt → relative path under granted SAF tree. */
        if (sm != null && sm.isSetupDone()) {
            val storageRel = when {
                rawPath.startsWith("storage/") -> rawPath.removePrefix("storage/")
                rawPath == "storage" || rawPath == "storage/" -> ""
                rawPath.startsWith("~/storage/shared/") -> rawPath.removePrefix("~/storage/shared/")
                rawPath.startsWith("~/storage/shared") -> rawPath.removePrefix("~/storage/shared").trimStart('/')
                else -> null
            }
            if (storageRel != null) {
                val root = sm.getGrantedRootPath()
                    ?: throw SecurityException("setup-storage belum memetakan path root. Jalankan setup-storage lagi.")
                val mapped = if (storageRel.isBlank()) File(root) else File(root, storageRel)
                return mapped
            }
        }

        /* Phase 57 fix (§4.1): Pakai SessionTargetResolver kalau ada (untuk support Ubuntu).
         * Fallback ke resolvePath lama kalau resolver belum di-set (backward compat). */
        val resolver = sessionTargetResolver
        if (resolver != null) {
            val file = resolver.resolvePhysicalPath(rawPath)
            val canonical = try { file.canonicalFile } catch (e: Exception) { file }

            /* Sandbox check: izinkan kalau di dalam workspace, rootfs Ubuntu, atau SAF tree. */
            if (!resolver.isPathAllowed(canonical)) {
                val insideGrantedStorage = sm?.isPathWithinGrantedTree(canonical) ?: false
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

        val workspacePath = try { workspaceRoot.canonicalPath } catch (e: Exception) { workspaceRoot.absolutePath }
        /* Wave-1: boundary-aware prefix check (not bare startsWith). */
        val insideWorkspace = SessionTargetResolver.isPathInside(canonical.canonicalPath, workspacePath)

        val insideGrantedStorage = sm?.isPathWithinGrantedTree(canonical) ?: false

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

    /**
     * Wave-19: If this absolute path is under the granted SAF tree, return the
     * relative DocumentFile path so we use ContentResolver I/O (real device write).
     * Raw java.io.File fails on Android 11+ even when path is "allowed" by sandbox.
     */
    private fun safRelativeFor(file: File): String? {
        val sm = storageManager ?: return null
        if (!sm.isSetupDone()) return null
        val abs = try { file.canonicalPath } catch (_: Exception) { file.absolutePath }
        if (sm.shouldUseSafForAbsolutePath(abs)) {
            return sm.relativePathUnderGrant(abs) ?: ""
        }
        /* Bridge marker ~/storage/shared/... when symlink is not a real bind. */
        val bridge = try { sm.sharedLinkFile.canonicalPath } catch (_: Exception) { sm.sharedLinkFile.absolutePath }
        if (SessionTargetResolver.isPathInside(abs, bridge)) {
            return abs.removePrefix(bridge).trimStart('/')
        }
        return null
    }

    private fun safWriteText(file: File, content: String): String? {
        val rel = safRelativeFor(file) ?: return null
        val sm = storageManager ?: return null
        return sm.writeTextRelative(rel, content).fold(
            onSuccess = { it },
            onFailure = { "Error: ${it.message}" }
        )
    }

    private fun safReadText(file: File, maxChars: Int): String? {
        val rel = safRelativeFor(file) ?: return null
        val sm = storageManager ?: return null
        return sm.readTextRelative(rel, maxChars).fold(
            onSuccess = { it },
            onFailure = { "Error: ${it.message}" }
        )
    }

    private fun listViaSaf(file: File): String? {
        val rel = safRelativeFor(file) ?: return null
        val sm = storageManager ?: return null
        return sm.listRelative(rel).fold(
            onSuccess = { rows ->
                if (rows.isEmpty()) "(empty) ${sm.getDisplayName()}/$rel"
                else rows.joinToString("\n")
            },
            onFailure = { "Error: ${it.message}" }
        )
    }

    private fun safDelete(file: File): String? {
        val rel = safRelativeFor(file) ?: return null
        val sm = storageManager ?: return null
        return sm.deleteRelative(rel).fold(
            onSuccess = { it },
            onFailure = { "Error: ${it.message}" }
        )
    }

    /**
     * Wave-1: Expose path resolution for MainActivity write_file diff flow so
     * UI does not bypass the sandbox with raw File(path).
     */
    fun resolvePathForAccess(rawPath: String): File = resolvePath(rawPath)

    /** Phase 47 (Fix 1): Expose workspaceRoot untuk AgentTaskRunner. */
    fun workspaceRootFile(): File = workspaceRoot

    /** Wave-23: Active session type for Agent / prompts. */
    fun activeSessionType(): String = sessionTargetResolver?.sessionType ?: "local"

    /** Wave-23: Guest-visible home path for cwd (Ubuntu /root vs Android workspace). */
    fun guestWorkDir(): String = sessionTargetResolver?.guestHome ?: workspaceRoot.absolutePath

    fun sessionPathInstructions(): String =
        sessionTargetResolver?.pathInstructionsForAi()
            ?: SessionTargetResolver("local", workspaceRoot, null).pathInstructionsForAi()

    /** Phase 57 fix (§4.1): Update SessionTargetResolver saat user pindah tab. */
    fun setSessionTargetResolver(resolver: SessionTargetResolver?) {
        sessionTargetResolver = resolver
    }

    /** Default directory for list/search/grep when dir is "." */
    private fun defaultListDir(): File {
        return if (sessionTargetResolver?.sessionType == "ubuntu") {
            try {
                resolvePath(".")
            } catch (_: Exception) {
                workspaceRoot
            }
        } else {
            workspaceRoot
        }
    }

    /** Phase 58 fix (§4.1-D): Update SshShellExecutor reference saat pindah tab SSH. */
    fun setSshExecutor(executor: SshShellExecutor?) {
        sshExecutor = executor
    }

    /** Phase 57 fix (§4.2): edit_file — edit parsial ala Aider/Claude Code.
     * old_string HARUS match persis 1 kali di file — mencegah AI salah
     * mengganti bagian yang mirip tapi berbeda konteks. */
    private fun executeEditFile(path: String, oldString: String, newString: String): String {
        /* Wave-6: SSH edit via SFTP read → replace → write. */
        val sessionType = sessionTargetResolver?.sessionType ?: "local"
        if (sessionType == "ssh" && sshExecutor != null) {
            val original = sshExecutor!!.readFileRemote(path)
                ?: return "Error: cannot read remote file: $path"
            val occurrences = Regex(Regex.escape(oldString)).findAll(original).count()
            return when {
                occurrences == 0 -> "Error: old_string tidak ditemukan di remote $path. Baca ulang dulu."
                occurrences > 1 -> "Error: old_string muncul $occurrences kali di remote $path — perlu lebih spesifik."
                else -> {
                    val updated = original.replaceFirst(oldString, newString)
                    if (sshExecutor!!.writeFileRemote(path, updated)) {
                        "OK: edited $path (remote, replaced 1 occurrence)"
                    } else {
                        "Error: failed to write remote file after edit: $path"
                    }
                }
            }
        }

        val file = resolvePath(path)
        /* Wave-19: SAF read for device paths under granted tree. */
        val original = when (val safBody = safReadText(file, 500_000)) {
            null -> {
                if (!file.exists()) return "Error: file not found: ${file.absolutePath}"
                try { file.readText() } catch (e: Exception) {
                    return "Error: cannot read file: ${e.message}"
                }
            }
            else -> {
                if (safBody.startsWith("Error:")) return safBody
                safBody
            }
        }
        val occurrences = Regex(Regex.escape(oldString)).findAll(original).count()
        return when {
            occurrences == 0 -> "Error: old_string tidak ditemukan persis di ${file.absolutePath}. Baca ulang file dulu sebelum edit."
            occurrences > 1 -> "Error: old_string muncul $occurrences kali — perlu lebih spesifik (sertakan lebih banyak baris konteks)."
            else -> {
                checkpointManager?.saveCheckpointBeforeWrite(file.absolutePath)
                val updated = original.replaceFirst(oldString, newString)
                val safResult = safWriteText(file, updated)
                if (safResult != null) {
                    if (safResult.startsWith("Error:")) safResult
                    else "OK: edited ${file.absolutePath} via SAF (replaced 1 occurrence)"
                } else {
                    file.writeText(updated)
                    "OK: edited ${file.absolutePath} (replaced 1 occurrence)"
                }
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
                        if (text != null) formatReadResult(path, text) else "Error: cannot read remote file: $path"
                    } else {
                        val file = resolvePath(path)
                        /* Wave-19: device paths under SAF grant → ContentResolver read. */
                        val safBody = safReadText(file, MAX_READ_CHARS)
                        if (safBody != null) {
                            if (safBody.startsWith("Error:")) return safBody
                            return formatReadResult(file.absolutePath, safBody)
                        }
                        if (!file.exists()) return "Error: file not found: ${file.absolutePath}"
                        if (!file.canRead()) return "Error: cannot read file (permission denied): ${file.absolutePath}"
                        if (file.isDirectory) return "Error: path is a directory: ${file.absolutePath}"
                        if (file.length() > MAX_READ_BYTES) {
                            return "Error: file too large (${file.length()} bytes, max $MAX_READ_BYTES). Use grep_content or head via run_command."
                        }
                        /* Wave-4: stream first MAX_READ_CHARS only — avoid OOM on large files. */
                        formatReadResult(file.absolutePath, readFileHead(file, MAX_READ_CHARS))
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
                        /* Wave-23: On Ubuntu, "." = /root in rootfs (not Android workspace). */
                        val file = if (dirRaw == "." || dirRaw.isBlank()) defaultListDir() else resolvePath(dirRaw)
                        val viaSaf = listViaSaf(file)
                        if (viaSaf != null) return viaSaf
                        if (!file.exists() || !file.isDirectory) return "Error: not a directory: ${file.absolutePath}"
                        file.listFiles()?.joinToString("\n") { f ->
                            "${if (f.isDirectory) "d" else "-"} ${f.name}"
                        } ?: "Error: cannot list directory"
                    }
                }
                "search_files" -> {
                    val pattern = call.args["pattern"] ?: return "Error: pattern required"
                    val dirRaw = call.args["dir"] ?: "."
                    /* Wave-4/23: match by filename; Ubuntu default = guest /root. */
                    val file = if (dirRaw == "." || dirRaw.isBlank()) defaultListDir() else resolvePath(dirRaw)
                    val regex = try {
                        Regex(pattern, RegexOption.IGNORE_CASE)
                    } catch (e: Exception) {
                        return "Error: invalid regex pattern: ${e.message}"
                    }
                    val results = mutableListOf<String>()
                    var visited = 0
                    for (f in file.walkTopDown()) {
                        visited++
                        if (visited > 5000) break
                        if (f.isFile && regex.containsMatchIn(f.name)) {
                            results.add(f.absolutePath)
                            if (results.size >= MAX_SEARCH_MATCHES) break
                        }
                    }
                    if (results.isEmpty()) "No files found matching name pattern: $pattern"
                    else results.joinToString("\n") +
                        if (results.size >= MAX_SEARCH_MATCHES) "\n... (truncated at $MAX_SEARCH_MATCHES matches)" else ""
                }
                "grep_content" -> {
                    /* Wave-4: content search (grep-like) inside workspace files. */
                    val pattern = call.args["pattern"] ?: return "Error: pattern required"
                    val dirRaw = call.args["dir"] ?: "."
                    val maxResults = call.args["max_results"]?.toIntOrNull()?.coerceIn(1, 100) ?: MAX_GREP_MATCHES
                    val root = if (dirRaw == "." || dirRaw.isBlank()) defaultListDir() else resolvePath(dirRaw)
                    if (!root.exists()) return "Error: directory not found: ${root.absolutePath}"
                    val regex = try {
                        Regex(pattern, RegexOption.IGNORE_CASE)
                    } catch (e: Exception) {
                        return "Error: invalid regex pattern: ${e.message}"
                    }
                    val hits = mutableListOf<String>()
                    var filesScanned = 0
                    root.walkTopDown().maxDepth(8).forEach { f ->
                        if (hits.size >= maxResults) return@forEach
                        if (!f.isFile) return@forEach
                        if (f.length() > 512 * 1024) return@forEach // skip >512KB
                        val name = f.name.lowercase()
                        if (name.endsWith(".so") || name.endsWith(".png") || name.endsWith(".jpg") ||
                            name.endsWith(".jar") || name.endsWith(".apk") || name.endsWith(".zip")
                        ) return@forEach
                        filesScanned++
                        if (filesScanned > MAX_GREP_FILES) return@forEach
                        try {
                            f.useLines { lines ->
                                var lineNo = 0
                                for (line in lines) {
                                    lineNo++
                                    if (line.length > 2000) continue
                                    if (regex.containsMatchIn(line)) {
                                        hits.add("${f.absolutePath}:$lineNo:${line.take(200)}")
                                        if (hits.size >= maxResults) break
                                    }
                                    if (lineNo > 5000) break
                                }
                            }
                        } catch (_: Exception) { /* skip unreadable */ }
                    }
                    if (hits.isEmpty()) "No content matches for: $pattern (scanned $filesScanned files)"
                    else hits.joinToString("\n") +
                        if (hits.size >= maxResults) "\n... (truncated at $maxResults matches)" else ""
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
                    /* Wave-4: Real terminal output via provider from MainActivity. */
                    val out = terminalOutputProvider?.invoke()
                    when {
                        out == null -> "Error: no active terminal session wired"
                        out.isBlank() -> "(terminal output empty)"
                        else -> out.take(3000)
                    }
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
                        /* Wave-19: real device write via SAF when under granted tree. */
                        val safResult = safWriteText(file, content)
                        if (safResult != null) return safResult
                        file.parentFile?.mkdirs()
                        file.writeText(content)
                        /* Wave-23: Tell AI the guest path on Ubuntu so run_command matches. */
                        val guest = sessionTargetResolver?.guestPathForPhysical(file)
                        val guestHint = if (guest != null) {
                            " (Ubuntu guest path: $guest — pakai ini di run_command)"
                        } else ""
                        "OK: wrote ${content.length} chars to ${file.absolutePath}$guestHint"
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
                        val safDel = safDelete(file)
                        if (safDel != null) return safDel
                        if (!file.exists()) return "Error: file not found: ${file.absolutePath}"
                        if (file.isDirectory) return "Error: refusing to delete directory via delete_file: ${file.absolutePath}"
                        /* Wave-4: checkpoint snapshot before delete so undo is possible. */
                        checkpointManager?.saveCheckpointBeforeWrite(file.absolutePath)
                        if (file.delete()) "OK: deleted ${file.absolutePath} (checkpoint saved if file existed)"
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

    /** Wave-4: Read only the first maxChars of a file (streamed). */
    private fun readFileHead(file: File, maxChars: Int): String {
        val sb = StringBuilder(maxChars.coerceAtMost(8192))
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            val buf = CharArray(4096)
            var total = 0
            while (total < maxChars) {
                val toRead = minOf(buf.size, maxChars - total)
                val n = reader.read(buf, 0, toRead)
                if (n < 0) break
                sb.append(buf, 0, n)
                total += n
            }
            if (reader.read() != -1) {
                sb.append("\n... (truncated, file longer than $maxChars chars)")
            }
        }
        return sb.toString()
    }

    private fun formatReadResult(path: String, text: String): String {
        return if (text.length >= MAX_READ_CHARS || text.contains("(truncated")) {
            text.take(MAX_READ_CHARS + 80)
        } else {
            text
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
        val state = getPermission(call.tool)
        /* Wave-7: ALWAYS_DENY skips prompt (caller should treat as denied). */
        if (state == PermissionState.ALWAYS_DENY) return false
        // BUG-01 fix: run_command dan delete_file SELALU butuh prompt (unless denied)
        if (call.tool in alwaysDenyAlwaysAllow) return true
        return state == PermissionState.ASK
    }

    /** Check if tool call is pre-approved. */
    fun isApproved(call: AiToolCall): Boolean {
        if (call.isReadOnly) return true
        val state = getPermission(call.tool)
        if (state == PermissionState.ALWAYS_DENY) return false
        // BUG-01 fix: run_command dan delete_file tidak pernah pre-approved
        if (call.tool in alwaysDenyAlwaysAllow) return false
        return state == PermissionState.ALWAYS_ALLOW
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
    onDeny: () -> Unit,
    /* Wave-7: Optional Never allow → ALWAYS_DENY for this session. */
    onNeverAllow: (() -> Unit)? = null
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDeny) {
                        Text("Deny once", color = Color(0xFFFF5252), fontSize = 11.sp)
                    }
                    /* Wave-7: Never allow works for all destructive tools including run_command. */
                    if (onNeverAllow != null) {
                        TextButton(onClick = onNeverAllow) {
                            Text("Never allow", color = Color(0xFFFF8A80), fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    )
}
