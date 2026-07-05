package com.tunnel.terminal

import android.util.Log

/**
 * TaskPlanManager — Externalize AI task plan supaya imun dari cap 20 pesan histori.
 *
 * Phase 58 fix (§4.6): Untuk tugas sistematis yang panjang (buat proyek Flask lengkap:
 * model, route, test, requirements.txt, README), bisa mudah melibatkan lebih dari 20
 * pertukaran pesan. Begitu cap tercapai, pesan-pesan lama (termasuk rencana awal AI!)
 * mulai terpotong — AI berisiko "lupa" apa yang sudah ia rencanakan.
 *
 * FIX: Rencana disimpan di state terpisah (di luar histori chat), di-inject ulang
 * tiap turn sebagai bagian system prompt. AI panggil plan_task() di awal, lalu
 * update_task_status() setelah tiap langkah.
 *
 * Pola: Plan → Act → Observe → Verify
 * 1. AI: plan_task(["Install deps", "Tulis app.py", "Tulis test", "Jalankan test"])
 * 2. AI: run_command("pip install flask pytest") → update_task_status(0, IN_PROGRESS)
 * 3. AI: write_file("app.py", ...) → update_task_status(1, IN_PROGRESS)
 * 4. AI: run_command("python3 -m py_compile app.py") → verify → update_task_status(1, DONE)
 * 5. AI: run_command("pytest") → update_task_status(3, DONE)
 * 6. AI: <agent_done> Semua langkah selesai </agent_done>
 */
class TaskPlanManager {
    companion object {
        private const val TAG = "TaskPlanManager"
        private const val MAX_STEPS = 20
    }

    data class PlanStep(
        val id: Int,
        val description: String,
        var status: StepStatus = StepStatus.PENDING
    )

    enum class StepStatus { PENDING, IN_PROGRESS, DONE, FAILED }

    private val currentPlan = mutableListOf<PlanStep>()

    /** Set rencana baru (dipanggil AI via tool plan_task). */
    fun setPlan(steps: List<String>): String {
        currentPlan.clear()
        if (steps.isEmpty()) return "Error: steps tidak boleh kosong"
        if (steps.size > MAX_STEPS) return "Error: maksimal $MAX_STEPS langkah (diberikan ${steps.size})"
        steps.forEachIndexed { i, desc ->
            currentPlan.add(PlanStep(id = i, description = desc))
        }
        Log.i(TAG, "Plan set dengan ${steps.size} langkah")
        return "OK: Plan diset dengan ${steps.size} langkah:\n" +
               currentPlan.joinToString("\n") { "${it.id}. [${it.status}] ${it.description}" }
    }

    /** Update status langkah (dipanggil AI via tool update_task_status). */
    fun markStep(stepId: Int, status: String): String {
        val step = currentPlan.find { it.id == stepId }
            ?: return "Error: step $stepId tidak ditemukan (total: ${currentPlan.size} langkah)"
        val newStatus = try { StepStatus.valueOf(status.uppercase()) }
            catch (e: Exception) { return "Error: status tidak valid: $status (pakai: PENDING, IN_PROGRESS, DONE, FAILED)" }
        step.status = newStatus
        Log.i(TAG, "Step $stepId '$step.description' → $newStatus")
        return "OK: Step $stepId '$step.description' → $newStatus"
    }

    /** Render rencana untuk system prompt — di-inject tiap turn. */
    fun renderForSystemPrompt(): String {
        if (currentPlan.isEmpty()) return ""
        return buildString {
            append("RENCANA TUGAS SAAT INI (JANGAN buat rencana baru dari nol, lanjutkan dari status ini):\n")
            currentPlan.forEach { step ->
                val icon = when (step.status) {
                    StepStatus.PENDING -> "⬜"
                    StepStatus.IN_PROGRESS -> "🔄"
                    StepStatus.DONE -> "✅"
                    StepStatus.FAILED -> "❌"
                }
                append("${step.id}. $icon [${step.status}] ${step.description}\n")
            }
            append("\nLanjutkan dari langkah PENDING atau IN_PROGRESS berikutnya.")
        }
    }

    /** Clear rencana (dipanggil saat task selesai atau user reset). */
    fun clearPlan() {
        currentPlan.clear()
    }

    /** Cek apakah rencana ada. */
    fun hasPlan(): Boolean = currentPlan.isNotEmpty()

    /** Get semua langkah (untuk UI display). */
    fun getSteps(): List<PlanStep> = currentPlan.toList()
}
