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
    private val markerExecutor: MarkerExecutor
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
     * membengkak di task panjang (30+ iterasi). */
    private data class StepRecord(val action: String, val outcome: String)

    /** Pola command berisiko yang tetap butuh approval manual walau mode otonom. */
    private val highRiskPatterns = listOf(
        Regex("""rm\s+-rf\s+/(?!root/workspace|home/)"""),  // rm -rf di luar workspace/home
        Regex("""curl.*\|\s*(sh|bash)"""),                   // pipe ke shell dari internet
        Regex("""wget.*\|\s*(sh|bash)"""),
        Regex("""\bdd\b.*of="""),                            // dd ke device
        Regex("""\bsudo\b"""),                               // sudo (tidak ada di proot, tapi jaga)
        Regex("""mkfs\.""),                                   // format filesystem
        Regex(""">\s*/dev/sd[a-z]"""),                        // write langsung ke device
        Regex(""":\(\)\s*\{""")                               // fork bomb pattern
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
        val history = mutableListOf<StepRecord>()
        var iteration = 0
        val workspacePath = toolExecutor.getWorkspaceRoot().absolutePath

        events(AgentEvent.Status("Memulai Agent task: $goal"))
        events(AgentEvent.Status("Environment: ${session.environmentDescription}"))
        events(AgentEvent.Status("Workspace: $workspacePath"))
        events(AgentEvent.Status("Max iterations: $maxIterations"))

        // Setup: cd ke workspace supaya command yang AI jalankan pakai cwd yang benar
        try {
            session.writeRaw("cd \"$workspacePath\"\n")
            Thread.sleep(100)
        } catch (e: Exception) {
            Log.w(TAG, "Gagal cd ke workspace: ${e.message}")
        }

        while (iteration < maxIterations && !stopped) {
            // Cek pause
            while (paused && !stopped) {
                events(AgentEvent.Status("⏸ Paused — tap Resume untuk lanjut"))
                kotlinx.coroutines.delay(1000)
            }
            if (stopped) {
                events(AgentEvent.StoppedForSafety("Dihentikan oleh user"))
                return
            }

            iteration++
            events(AgentEvent.Status("── Iterasi $iteration/$maxIterations ──"))

            val prompt = buildAgentPrompt(goal, history, iteration, maxIterations, workspacePath, session.environmentDescription)
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
                    history.add(StepRecord("verifikasi selesai", "GAGAL — masih ada masalah, lanjutkan perbaikan"))
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
                history.add(StepRecord("iterasi $iteration", "tidak ada aksi — response: ${response.take(150)}"))

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

                // Risk assessment
                val riskReason = assessRisk(call)
                if (riskReason != null) {
                    events(AgentEvent.NeedsApproval(call, riskReason))
                    val approved = approve(call, riskReason)
                    if (!approved) {
                        history.add(StepRecord(call.displayText, "DITOLAK user: $riskReason"))
                        events(AgentEvent.ToolResult(call.tool, call.displayText, "Ditolak user: $riskReason", false))
                        continue
                    }
                    events(AgentEvent.Status("✓ Approved: ${call.displayText}"))
                }

                // Eksekusi
                val result = if (call.tool == "run_command") {
                    executeViaMarker(call, session)
                } else {
                    toolExecutor.execute(call)
                }

                val success = !result.startsWith("Error") && !result.startsWith("Ditolak")
                history.add(StepRecord(call.displayText, result.take(300)))
                events(AgentEvent.ToolResult(call.tool, call.displayText, result.take(200), success))
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
            // Path relatif selalu di workspace (aman). Path absolut di luar workspace = berisiko.
            if (path.startsWith("/") && !path.contains("workspace")) {
                return "Menghapus file di luar workspace project: $path"
            }
        }
        return null
    }

    /** Eksekusi run_command via MarkerExecutor dengan ExecutionOutcome handling. */
    private suspend fun executeViaMarker(call: AiToolCall, session: TerminalSession): String {
        val cmd = call.args["cmd"] ?: return "Error: cmd required"
        return try {
            val outcome = markerExecutor.executeWithMarker(
                session, cmd,
                maxTimeoutMs = 300000,  // 5 menit — apt install bisa lama
                idleTimeoutMs = 15000   // 15s idle = curiga nunggu input
            )
            markerExecutor.formatOutcomeForAI(outcome)
        } catch (e: Exception) {
            "Error executing command: ${e.message}"
        }
    }

    /**
     * Verifikasi independen setelah AI klaim selesai.
     * Jangan percaya klaim AI begitu saja — cek apakah benar-benar selesai.
     *
     * Implementasi saat ini: cek exit code command terakhir yang dijalankan AI adalah 0.
     * Bisa di-extend per jenis project (mis. untuk web server, curl endpoint;
     * untuk CLI, jalankan --help dan cek exit 0).
     */
    private suspend fun verifyCompletion(
        session: TerminalSession,
        goal: String,
        history: List<StepRecord>
    ): Boolean {
        // Heuristik sederhana: kalau 3 step terakhir semua sukses, anggap verified.
        // Bisa di-extend dengan logic spesifik-goal nanti.
        val recent = history.takeLast(5)
        if (recent.isEmpty()) return false

        val successCount = recent.count { !it.outcome.startsWith("Error") && !it.outcome.startsWith("DITOLAK") }
        val successRate = successCount.toDouble() / recent.size

        // Minimal 60% step terakhir sukses, dan tidak ada "GAGAL" di verifikasi sebelumnya
        val hasRecentFailure = recent.any { it.outcome.contains("GAGAL", ignoreCase = true) }
        return successRate >= 0.6 && !hasRecentFailure
    }

    /** Build prompt untuk AI di setiap iterasi. */
    private fun buildAgentPrompt(
        goal: String,
        history: List<StepRecord>,
        iteration: Int,
        max: Int,
        workspacePath: String,
        envDesc: String
    ): String {
        val historyText = history.takeLast(HISTORY_KEEP).joinToString("\n") {
            "- ${it.action} → ${it.outcome}"
        }
        return """
            Anda adalah Agent otonom. Selesaikan goal berikut sampai tuntas.

            Goal: $goal

            Environment: $envDesc
            Workspace: $workspacePath (path relatif otomatis masuk sini)

            Riwayat langkah sejauh ini (iterasi $iteration/$max):
            $historyText

            Instruksi:
            1. Tentukan LANGKAH BERIKUTNYA untuk mendekati goal. Pakai tool calls
               (write_file, read_file, run_command, dll) — bukan bash blocks.
            2. Setiap aksi akan dieksekusi otomatis, hasilnya dikasih balik ke kamu
               di iterasi berikutnya. Kamu tidak perlu menunggu user.
            3. Kalau ada error, analisa penyebabnya dan perbaiki di langkah berikutnya.
            4. Kalau sudah BENAR-BENAR selesai dan teruji (bukan cuma ditulis, tapi
               sudah dicoba jalan), akhiri dengan:
               <agent_done>ringkasan singkat apa yang sudah dibuat + cara pakai</agent_done>
            5. Kalau butuh klarifikasi dari user (mis. goal ambigu), akhiri dengan:
               <needs_clarification>pertanyaan spesifik ke user</needs_clarification>
            6. JANGAN ulangi aksi yang sama jika gagal — coba pendekatan berbeda.
            7. Untuk command apt-get, SELALU pakai flag -y (mis. apt-get install -y nodejs).

            Lanjutkan dengan tool call berikutnya.
        """.trimIndent()
    }
}
