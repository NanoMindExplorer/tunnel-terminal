package com.tunnel.terminal

import android.util.Log
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.yield

/**
 * AgentTaskRunner — Loop otonom AI untuk tugas panjang.
 *
 * Phase 47 (Bagian 2): Berbeda dari chat biasa ( respon sekali per pesan),
 * AgentWorkflow (langkah ditentukan di depan), dan Auto-Pilot (command list pasti) —
 * AgentTaskRunner adalah loop otonom: AI dikasih goal → AI putuskan aksi → aksi
 * dieksekusi → hasilnya (termasuk error) dikasih balik ke AI secara otomatis →
 * AI putuskan aksi berikutnya → ulang sampai AI bilang selesai (atau macet).
 *
 * Arsitektur:
 * - Environment default: Ubuntu (proot) — kesalahan AI terkurung di rootfs proot
 *   (filesDir/linux/ubuntu/...), sepenuhnya privat. Plus toolchain asli (python3, gcc, node).
 * - Path AI di-sandbox ke workspaceRoot (filesDir/workspace/...) oleh ToolExecutor.resolvePath().
 *   Tidak ada dialog permission yang bisa gagal selama task berjalan.
 * - Risk assessment: command berisiko (rm -rf di luar workspace, curl|sh, dd, sudo) tetap
 *   butuh approval manual walau mode otonom. Otonom bukan berarti tanpa rem.
 * - maxIterations (default 40) sebagai jaring pengaman keras — cegah loop tak berujung
 *   dan tagihan API tak berujung.
 * - verifyCompletion(): setelah AI klaim selesai, verifikasi independen (jangan percaya
 *   klaim AI begitu saja). Kalau gagal, masukkan lagi ke loop dengan pesan "masih ada masalah".
 *
 * Event flow:
 *   Status → ToolResult (per aksi) → ... → Done atau StoppedForSafety
 * UI menampilkan event real-time via mutableStateListOf<AgentEvent>.
 */
class AgentTaskRunner(
    private val aiAgent: AIAgent,
    private val toolExecutor: ToolExecutor,
    private val permissionManager: PermissionManager,
    private val markerExecutor: MarkerExecutor,
    /* Wave-2: MCP tools in agent mode. */
    private val mcpManager: McpManager? = null
) {
    companion object {
        private const val TAG = "AgentTaskRunner"
        private const val DEFAULT_MAX_ITERATIONS = 40
        private const val HISTORY_KEEP = 15  // riwayat terakhir yang dikirim ke AI
    }

    /** Event yang dipancarkan selama task berjalan. UI menampilkan ini real-time. */
    sealed class AgentEvent {
        /** Status update (mulai, progress, info). */
        data class Status(val message: String) : AgentEvent()
        /** Hasil eksekusi satu tool call. */
        data class ToolResult(
            val tool: String,
            val argsSummary: String,
            val resultSummary: String,
            val success: Boolean
        ) : AgentEvent()
        /** Aksi berisiko butuh approval user. */
        data class NeedsApproval(val call: AiToolCall, val reason: String) : AgentEvent()
        /** Task selesai dengan sukses. */
        data class Done(val summary: String) : AgentEvent()
        /** Task dihentikan untuk alasan keamanan/batas iterasi. */
        data class StoppedForSafety(val reason: String) : AgentEvent()
        /** AI meminta klarifikasi dari user. */
        data class NeedsClarification(val question: String) : AgentEvent()
    }

    /** Ringkasan kompak per langkah — dikirim balik ke AI di iterasi berikutnya.
     * SENGAJA tidak menyimpan isi file/output penuh supaya context tidak
     * membengkak di task panjang (30+ iterasi).
     * Phase 52 fix (Bug #2): Tambah success + tool field supaya verifyCompletion
     * bisa cek exit code asli, bukan string-match yang salah. */
    private data class StepRecord(
        val action: String,
        val outcome: String,
        val success: Boolean,
        val tool: String
    )

    /** Phase 52 fix (Bug #2): Result dari executeViaMarker — text + success flag. */
    private data class ExecResult(val text: String, val success: Boolean)

    /** Pola command berisiko yang tetap butuh approval manual walau mode otonom. */
    private val highRiskPatterns = listOf(
        Regex("""rm\s+-rf\s+/(?!root/workspace|home/)"""),  // rm -rf di luar workspace/home
        Regex("""rm\s+-rf\s+\.(?:\s|$)"""),                 // rm -rf .
        Regex("""curl.*\|\s*(sh|bash|python)"""),           // pipe ke shell/python dari internet
        Regex("""wget.*\|\s*(sh|bash|python)"""),
        Regex("\\bdd\\b.*of="),                            // dd ke device
        Regex("""\bsudo\b"""),                               // sudo (tidak ada di proot, tapi jaga)
        Regex("mkfs\\."),  // format filesystem
        Regex(""">\s*/dev/sd[a-z]"""),                        // write langsung ke device
        Regex("""chmod\s+-R\s+777"""),
        Regex(":\\(\\)\\s*\\{")  // fork bomb pattern
    )

    /** Flag untuk Pause — dicek di awal tiap iterasi. */
    @Volatile
    private var paused = false

    /** Flag untuk Stop — membatalkan coroutine. */
    @Volatile
    private var stopped = false

    /** Jalankan Agent task.
     *
     * @param goal Deskripsi tugas dari user (mis. "Buat CLI tool Python yang hitung factorial")
     * @param session TerminalSession aktif (idealnya ProotShellExecutor untuk sandbox)
     * @param settings Konfigurasi AI provider
     * @param maxIterations Batas maksimum iterasi (default 40)
     * @param approve Callback untuk aksi berisiko — return true = approve, false = reject
     * @param events Callback untuk setiap event (Status, ToolResult, Done, dst)
     */
    suspend fun run(
        goal: String,
        session: TerminalSession,
        settings: AISettings,
        maxIterations: Int = DEFAULT_MAX_ITERATIONS,
        approve: suspend (AiToolCall, String) -> Boolean,
        events: (AgentEvent) -> Unit
    ) {
        /* Wave-1: Reset control flags so Start works after a previous Stop/Pause. */
        stopped = false
        paused = false

        val history = mutableListOf<StepRecord>()
        var iteration = 0
        /* Wave-23: On Ubuntu, guest HOME is /root — NOT the Android filesDir workspace path.
         * OLD BUG: cd /data/data/.../workspace inside proot → path missing → every command fails. */
        val isUbuntu = session.sessionType == "ubuntu"
        val workDir = if (isUbuntu) "/root" else toolExecutor.workspaceRootFile().absolutePath
        val pathHint = toolExecutor.sessionPathInstructions()

        events(AgentEvent.Status("Memulai Agent task: $goal"))
        events(AgentEvent.Status("Environment: ${session.environmentDescription}"))
        events(AgentEvent.Status("Work dir: $workDir (${if (isUbuntu) "Ubuntu guest" else "Android workspace"})"))
        events(AgentEvent.Status("Max iterations: $maxIterations"))

        try {
            session.writeRaw("cd \"$workDir\" 2>/dev/null || cd /root 2>/dev/null || true\n")
            if (isUbuntu) {
                session.writeRaw("export DEBIAN_FRONTEND=noninteractive\n")
            }
            kotlinx.coroutines.delay(150)
        } catch (e: Exception) {
            Log.w(TAG, "Gagal cd ke work dir: ${e.message}")
        }

        while (iteration < maxIterations && !stopped) {
            // Wave-17: Emit pause status once, then wait quietly.
            if (paused && !stopped) {
                events(AgentEvent.Status("⏸ Dijeda — ketuk Resume untuk lanjut"))
            }
            while (paused && !stopped) {
                kotlinx.coroutines.delay(400)
            }
            if (stopped) {
                events(AgentEvent.StoppedForSafety("Dihentikan oleh user"))
                return
            }

            iteration++
            events(AgentEvent.Status("── Iterasi $iteration/$maxIterations ──"))

            val prompt = buildAgentPrompt(
                goal, history, iteration, maxIterations, workDir,
                session.environmentDescription, pathHint, isUbuntu
            )
            val response = try {
                aiAgent.askAIStreaming(
                    settings,
                    listOf(ChatMessage("user", prompt, false)),
                    session.getCleanOutput(),
                    session.sessionType,
                    session.environmentDescription
                ).toList().joinToString("")
            } catch (e: Exception) {
                events(AgentEvent.StoppedForSafety("Error memanggil AI: ${e.message}"))
                return
            }

            // Cek sinyal selesai
            val doneMatch = Regex("<agent_done>([\\s\\S]*?)</agent_done>").find(response)
            if (doneMatch != null) {
                val summary = doneMatch.groupValues[1].trim()
                events(AgentEvent.Status("AI klaim selesai. Verifikasi..."))
                val verified = verifyCompletion(session, goal, history)
                if (verified) {
                    events(AgentEvent.Done(summary))
                    return
                } else {
                    history.add(StepRecord("verifikasi selesai", "GAGAL — masih ada masalah, lanjutkan perbaikan", false, "verify"))
                    events(AgentEvent.Status("⚠ Verifikasi gagal — AI diminta lanjut memperbaiki"))
                    continue
                }
            }

            // Cek sinyal butuh klarifikasi
            val clarifyMatch = Regex("<needs_clarification>([\\s\\S]*?)</needs_clarification>").find(response)
            if (clarifyMatch != null) {
                events(AgentEvent.NeedsClarification(clarifyMatch.groupValues[1].trim()))
                return
            }

            // Parse tool calls
            val calls = AiToolCall.parseFromResponse(response)
            if (calls.isEmpty()) {
                // AI tidak memberi aksi maupun sinyal selesai — kemungkinan stuck
                events(AgentEvent.Status("AI tidak memberi aksi jelas. Response: ${response.take(300)}"))
                history.add(StepRecord("iterasi $iteration", "tidak ada aksi — response: ${response.take(150)}", false, "none"))

                // Kalau 3 iterasi berturut-turut tidak ada aksi, stop
                val recentNoAction = history.takeLast(3).all { it.outcome.startsWith("tidak ada aksi") }
                if (recentNoAction) {
                    events(AgentEvent.StoppedForSafety("AI stuck — 3 iterasi berturut-turut tanpa aksi jelas"))
                    return
                }
                continue
            }

            // Eksekusi setiap tool call
            for (call in calls) {
                if (stopped) {
                    events(AgentEvent.StoppedForSafety("Dihentikan oleh user"))
                    return
                }

                /* Wave-2: Honor ALWAYS_DENY from PermissionManager. */
                if (permissionManager.getPermission(call.tool) ==
                    PermissionManager.PermissionState.ALWAYS_DENY
                ) {
                    history.add(StepRecord(call.displayText, "DITOLAK (Always Deny)", false, call.tool))
                    events(AgentEvent.ToolResult(call.tool, call.displayText, "Ditolak (Always Deny)", false))
                    continue
                }

                // Risk assessment + optional approval for high-risk actions
                val riskReason = assessRisk(call)
                if (riskReason != null) {
                    events(AgentEvent.NeedsApproval(call, riskReason))
                    val approved = approve(call, riskReason)
                    if (!approved) {
                        /* Phase 52 fix (Bug #2): StepRecord sekarang punya success + tool fields. */
                        history.add(StepRecord(call.displayText, "DITOLAK user: $riskReason", false, call.tool))
                        events(AgentEvent.ToolResult(call.tool, call.displayText, "Ditolak user: $riskReason", false))
                        continue
                    }
                    events(AgentEvent.Status("✓ Approved: ${call.displayText}"))
                }

                /* Phase 52 fix (Bug #2): Eksekusi dengan success detection yang benar.
                 * Wave-2: MCP tools routed via McpManager (not unknown tool). */
                val (resultText, success) = when {
                    call.tool == "run_command" -> {
                        val execResult = executeViaMarker(call, session)
                        execResult.text to execResult.success
                    }
                    call.tool.startsWith("mcp.") -> {
                        val parts = call.tool.removePrefix("mcp.").split(".", limit = 2)
                        if (parts.size != 2 || mcpManager == null) {
                            "Error: MCP tool unavailable: ${call.tool}" to false
                        } else {
                            val argsJson = org.json.JSONObject()
                            call.args.forEach { (k, v) -> argsJson.put(k, v) }
                            val text = mcpManager.invokeTool(parts[0], parts[1], argsJson.toString())
                            text to (!text.startsWith("Error") && !text.startsWith("MCP error") &&
                                !text.startsWith("MCP invoke error"))
                        }
                    }
                    else -> {
                        val text = toolExecutor.execute(call)
                        text to (!text.startsWith("Error") && !text.startsWith("Ditolak"))
                    }
                }

                history.add(StepRecord(call.displayText, resultText.take(300), success, call.tool))
                events(AgentEvent.ToolResult(call.tool, call.displayText, resultText.take(200), success))
            }

            yield() // beri chance coroutine untuk di-cancel
        }

        if (iteration >= maxIterations) {
            events(AgentEvent.StoppedForSafety("Mencapai batas $maxIterations iterasi tanpa sinyal selesai"))
        }
    }

    /** Pause task — loop akan menunggu di awal iterasi berikutnya. */
    fun pause() {
        paused = true
        Log.i(TAG, "Agent task paused")
    }

    /** Resume task dari pause. */
    fun resume() {
        paused = false
        Log.i(TAG, "Agent task resumed")
    }

    /** Stop task — membatalkan loop. */
    fun stop() {
        stopped = true
        Log.i(TAG, "Agent task stop requested")
    }

    /** Aksi yang dianggap berisiko tetap butuh approval eksplisit walau mode otonom. */
    private fun assessRisk(call: AiToolCall): String? {
        if (call.tool == "run_command") {
            val cmd = call.args["cmd"] ?: ""
            for (pattern in highRiskPatterns) {
                if (pattern.containsMatchIn(cmd)) {
                    return "Command mengandung pola berisiko: cocok pola '${pattern.pattern}'"
                }
            }
        }
        if (call.tool == "delete_file") {
            val path = call.args["path"] ?: ""
            /* Wave-2: Prefer canonical sandbox check when path is absolute. */
            if (path.startsWith("/")) {
                try {
                    val file = toolExecutor.resolvePathForAccess(path)
                    val workspace = toolExecutor.workspaceRootFile().canonicalPath
                    if (!SessionTargetResolver.isPathInside(file.canonicalPath, workspace)) {
                        return "Menghapus file di luar workspace project: $path"
                    }
                } catch (e: Exception) {
                    return "Menghapus file di luar workspace project: $path (${e.message})"
                }
            }
        }
        return null
    }

    /** Eksekusi run_command via MarkerExecutor dengan ExecutionOutcome handling.
     *  Phase 52 fix (Bug #2): Return ExecResult (text + success), bukan String.
     *  Success datang dari CommandResult.isSuccess (exit code asli), bukan string-match. */
    private suspend fun executeViaMarker(call: AiToolCall, session: TerminalSession): ExecResult {
        val cmd = call.args["cmd"] ?: return ExecResult("Error: cmd required", false)
        return try {
            val outcome = markerExecutor.executeWithMarker(
                session, cmd,
                maxTimeoutMs = 300000,  // 5 menit — apt install bisa lama
                idleTimeoutMs = 15000   // 15s idle = curiga nunggu input
            )
            val text = markerExecutor.formatOutcomeForAI(outcome)
            /* Phase 52 fix (Bug #2): Success dari CommandResult.isSuccess (exit code asli).
             * PossiblyWaitingForInput dan TimedOut selalu dianggap gagal. */
            val success = when (outcome) {
                is MarkerExecutor.ExecutionOutcome.Completed -> outcome.result.isSuccess
                else -> false  // PossiblyWaitingForInput / TimedOut = gagal
            }
            ExecResult(text, success)
        } catch (e: Exception) {
            ExecResult("Error executing command: ${e.message}", false)
        }
    }

    /**
     * Verifikasi independen setelah AI klaim selesai.
     * Jangan percaya klaim AI begitu saja — cek apakah benar-benar selesai.
     *
     * Phase 52 fix (Bug #2): Pakai success flag dari StepRecord (exit code asli),
     * bukan string-match yang salah (startsWith("Error") selalu false untuk run_command).
     *
     * Heuristik: cek run_command terakhir — kalau gagal, task belum selesai.
     * Kalau tidak ada run_command sama sekali, AI belum benar-benar menguji apa pun.
     */
    private suspend fun verifyCompletion(
        session: TerminalSession,
        goal: String,
        history: List<StepRecord>
    ): Boolean {
        /* Phase 52 fix (Bug #2): Cek run_command terakhir — kalau gagal, belum selesai.
         * Kalau tidak ada run_command sama sekali, AI belum menguji apa pun → tidak verified. */
        val lastRunCommand = history.lastOrNull { it.tool == "run_command" }
        if (lastRunCommand == null) {
            // AI klaim selesai tapi belum pernah menjalankan/menguji apa pun
            return false
        }
        // run_command terakhir harus sukses (exit code 0)
        if (!lastRunCommand.success) return false

        // Tambahan: minimal 60% step terakhir sukses (jaga-jaga untuk multi-step)
        val recent = history.takeLast(5)
        if (recent.isEmpty()) return false
        val successCount = recent.count { it.success }
        val successRate = successCount.toDouble() / recent.size
        return successRate >= 0.6
    }

    /** Build prompt untuk AI di setiap iterasi. */
    private fun buildAgentPrompt(
        goal: String,
        history: List<StepRecord>,
        iteration: Int,
        max: Int,
        workDir: String,
        envDesc: String,
        pathHint: String,
        isUbuntu: Boolean
    ): String {
        val historyText = history.takeLast(HISTORY_KEEP).joinToString("\n") {
            val mark = if (it.success) "OK" else "FAIL"
            "- [$mark] ${it.action} → ${it.outcome}"
        }
        val ubuntuExtra = if (isUbuntu) {
            """
            Ubuntu tips:
            - write_file "app.py" → file di /root/app.py (guest)
            - run_command "python3 app.py" (cwd sudah /root)
            - apt: DEBIAN_FRONTEND=noninteractive apt-get install -y <pkg>
            - Jangan cd ke path Android /data/data/...
            """.trimIndent()
        } else ""
        return """
            Anda adalah Agent otonom. Selesaikan goal berikut sampai tuntas.

            Goal: $goal

            Environment: $envDesc
            Work directory: $workDir
            $pathHint
            $ubuntuExtra

            Riwayat langkah sejauh ini (iterasi $iteration/$max):
            $historyText

            Instruksi:
            1. Tentukan LANGKAH BERIKUTNYA. Pakai tool calls (write_file, read_file,
               run_command, dll) — bukan bash blocks di chat.
            2. Setiap aksi dieksekusi otomatis; hasil balik di iterasi berikutnya.
            3. Kalau error, analisa dan perbaiki — jangan ulangi perintah gagal yang sama.
            4. Selesai + teruji → <agent_done>ringkasan + cara pakai</agent_done>
            5. Goal ambigu → <needs_clarification>pertanyaan</needs_clarification>
            6. apt-get SELALU -y + DEBIAN_FRONTEND=noninteractive.

            Lanjutkan dengan tool call berikutnya.
        """.trimIndent()
    }
}
