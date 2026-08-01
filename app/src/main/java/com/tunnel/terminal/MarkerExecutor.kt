package com.tunnel.terminal

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong

/**
 * MarkerExecutor - Eksekusi command dengan unique marker untuk deteksi completion + exit code.
 *
 * Phase 37: Revolusi command execution untuk AI Auto-Pilot + Tool Calling.
 * Phase 46 (Pilar 1): Fix computeNewOutput bug + dual-layer timeout (max + idle) +
 * ExecutionOutcome sealed class untuk bedakan "completed" vs "possibly waiting for input"
 * vs "timed out".
 *
 * Masalah lama:
 * - Auto-Pilot pakai regex nebak prompt shell → false positive (output mengandung $ atau #)
 * - run_command fire-and-forget → AI tidak tahu hasil/exit code command
 * - @command: stub kosong → tidak pernah benar-benar mengeksekusi
 *
 * Solusi: Marker-based execution
 * - Setiap command dibungkus: `{ cmd ; } ; ec=$?; echo "__TT_DONE_<id>_${ec}__"`
 * - Marker unik per-command (AtomicLong counter) → tidak collide dengan output command
 * - Tunggu marker muncul di output → command selesai, exit code tercapture
 * - Kirim hasil balik ke AI sebagai context untuk analisis/next step
 *
 * Phase 46 (Pilar 1) — Dua perbaikan utama:
 *
 * 1. **computeNewOutput helper** — satukan logic "hitung output baru" jadi satu fungsi
 *    dengan fallback konsisten untuk kasus buffer roll-over. OLD BUG: pakai
 *    `substringAfter(before, "")` yang diam-diam mengembalikan string kosong persis
 *    saat buffer sudah ke-truncate (output command lebih panjang dari kapasitas buffer).
 *
 * 2. **Dual-layer timeout (max + idle)** — bedakan "masih progress" vs "diam mencurigakan".
 *    apt-get install yang wajar HAMPIR SELALU menghasilkan output baru minimal setiap
 *    beberapa detik (progress download, "Unpacking...", "Setting up..."). Diam total
 *    selama 15 detik = sinyal kuat bahwa proses menunggu input, bukan cuma lambat.
 *
 * Marker format: __TT_DONE_<id>_<exitcode>__
 * - id: unique counter (mis. 1, 2, 3, ...)
 * - exitcode: $? dari shell (0 = success, non-zero = error)
 */
class MarkerExecutor {

    companion object {
        private const val TAG = "MarkerExecutor"
        private const val MARKER_PREFIX = "__TT_DONE_"
        private const val MARKER_SUFFIX = "__"
        /** Phase 48 fix (A-5): Regex sekarang match <counter>_<hex4>_<exitcode>
         * Format: __TT_DONE_<counter>_<hex4>_<exitcode>__ */
        private val MARKER_REGEX = Regex("__TT_DONE_(\\d+)_[0-9a-f]{4}_(\\d+)__")

        /** Atomic counter untuk unique marker ID. */
        private val markerIdCounter = AtomicLong(0)

        /** Phase 48 fix (A-5): Random component supaya marker tidak predictable.
         * Counter alone bisa collide dengan output command yang kebetulan mengandung
         * string mirip marker. Tambah 4-byte random dari SecureRandom. */
        private val secureRandom = java.security.SecureRandom()

        /** Generate unique marker ID — counter + random component. */
        fun nextMarkerId(): String {
            val counter = markerIdCounter.incrementAndGet()
            val random = secureRandom.nextInt(0xFFFF)
            return "${counter}_${String.format("%04x", random)}"
        }

        /**
         * Build command dengan marker appended.
         * Phase 40 fix (H6): Capture exit code SEBELUM echo, bukan di echo-nya.
         */
        fun wrapCommand(command: String, markerId: String): String {
            return "{ $command ; } ; ec=\$?; echo \"${MARKER_PREFIX}${markerId}_\${ec}${MARKER_SUFFIX}\""
        }

        /** Parse marker dari output terminal. Returns MarkerResult jika ditemukan.
         *  Phase 48 fix (A-5): markerId sekarang String (counter + hex random).
         *  parseMarker ekstrak counter dari markerId untuk kompatibilitas. */
        fun parseMarker(output: String): MarkerResult? {
            val match = MARKER_REGEX.find(output) ?: return null
            val counter = match.groupValues[1].toLongOrNull() ?: return null
            val exitCode = match.groupValues[2].toIntOrNull() ?: return null
            return MarkerResult(id = counter, exitCode = exitCode, rawMarker = match.value)
        }

        /** Hapus marker dari output (untuk display yang clean). */
        fun stripMarker(output: String): String {
            return MARKER_REGEX.replace(output, "").trim()
        }

        /**
         * Phase 46 fix (Pilar 1a): Hitung output baru dengan fallback yang konsisten
         * untuk kasus buffer roll-over.
         *
         * OLD BUG: Di polling loop, logic `if (after.length > before.length && after.startsWith(before))`
         * handle roll-over dengan fallback ke `after` apa adanya (benar). TAPI di final
         * result extraction, pakai `substringAfter(before, "")` yang diam-diam mengembalikan
         * string kosong persis saat kondisi roll-over terjadi (before tidak lagi jadi prefix).
         *
         * FIX: Satukan logic ke fungsi ini, pakai di kedua tempat. Fallback ke `after`
         * apa adanya kalau roll-over terjadi — jangan pernah kembalikan string kosong.
         */
        private fun computeNewOutput(before: String, after: String): String {
            return if (after.length > before.length && after.startsWith(before)) {
                after.substring(before.length)
            } else {
                // Buffer sudah ke-truncate (output command lebih panjang dari kapasitas
                // buffer getCleanOutput()) — 'before' tidak lagi jadi prefix dari 'after'.
                // Pakai 'after' apa adanya. JANGAN pakai substringAfter(before, "") di sini —
                // itu diam-diam mengembalikan string kosong persis saat kondisi ini terjadi.
                after
            }
        }
    }

    /** Result dari marker parsing.
     *  id = counter (Long), bukan full markerId string — untuk kompatibilitas.
     *  Full markerId (counter + hex) hanya dipakai internal saat wrap/parse. */
    data class MarkerResult(
        val id: Long,
        val exitCode: Int,
        val rawMarker: String
    ) {
        val isSuccess: Boolean get() = exitCode == 0
    }

    /** Result lengkap dari command execution. */
    data class CommandResult(
        val command: String,
        val output: String,
        val exitCode: Int,
        val isSuccess: Boolean,
        val executionTimeMs: Long
    )

    /**
     * Phase 46 (Pilar 1b): Outcome dari eksekusi command dengan dual-layer timeout.
     *
     * Tiga kemungkinan outcome:
     * - **Completed**: Marker ditemukan → command selesai, exit code tercapture.
     * - **PossiblyWaitingForInput**: Tidak ada output baru selama idleTimeoutMs →
     *   kemungkinan command sedang menunggu input interaktif (mis. apt dialog konfirmasi).
     *   JANGAN kirim command baru menebak jawaban — tampilkan ke user + beri tahu AI.
     * - **TimedOut**: Total waktu melebihi maxTimeoutMs tanpa marker → command benar-benar
     *   macet atau terlalu lambat.
     *
     * Idle-based detection jauh lebih andal daripada single timeout: apt-get install
     * yang wajar HAMPIR SELALU menghasilkan output baru minimal setiap beberapa detik.
     * Diam total selama 15s = sinyal kuat bahwa proses menunggu input, bukan cuma lambat.
     */
    sealed class ExecutionOutcome {
        /** Command selesai dengan exit code tercapture. */
        data class Completed(val result: CommandResult) : ExecutionOutcome()

        /** Command kemungkinan menunggu input interaktif (idle > idleTimeoutMs). */
        data class PossiblyWaitingForInput(
            val partialOutput: String,
            val elapsedMs: Long
        ) : ExecutionOutcome()

        /** Command melebihi maxTimeoutMs tanpa marker. */
        data class TimedOut(val partialOutput: String) : ExecutionOutcome()
    }

    /**
     * Eksekusi command di session tertentu, tunggu marker, return ExecutionOutcome.
     *
     * Phase 46 (Pilar 1b): Dual-layer timeout (max + idle).
     *
     * @param session TerminalSession (local PTY, SSH, atau proot/Ubuntu)
     * @param command Command to execute (tanpa marker — akan di-wrap otomatis)
     * @param maxTimeoutMs Total maximum time (default 30s). Untuk apt install, set 300000 (5 min).
     * @param idleTimeoutMs Jika tidak ada output baru selama ini → curiga nunggu input (default 15s).
     * @return ExecutionOutcome (Completed / PossiblyWaitingForInput / TimedOut)
     */
    suspend fun executeWithMarker(
        session: TerminalSession,
        command: String,
        maxTimeoutMs: Long = 30000,
        idleTimeoutMs: Long = 15000
    ): ExecutionOutcome = withContext(Dispatchers.IO) {
        val markerIdStr = nextMarkerId()
        val markerIdCounter = markerIdStr.substringBefore("_").toLong()
        val wrappedCommand = wrapCommand(command, markerIdStr)
        val startTime = System.currentTimeMillis()

        // Capture output sebelum command
        val outputBefore = session.getCleanOutput()

        // Kirim command + marker ke PTY
        session.writeRaw(wrappedCommand + "\n")

        // Track idle — update lastChangeTime setiap kali output berubah
        var lastOutputLen = outputBefore.length
        var lastChangeTime = startTime
        var outputAfter = outputBefore
        /* v8.6.0 fix (H3): Adaptive poll delay — exponential backoff saat output stabil.
         * Sebelumnya: fixed delay(25) = 12,000 polls untuk apt install 5 menit.
         * Sekarang: start 25ms, double saat output tidak berubah (cap 500ms).
         * Reset ke 25ms saat output berubah lagi. Saves ~90% CPU untuk long commands. */
        var pollDelayMs = 25L

        while (System.currentTimeMillis() - startTime < maxTimeoutMs) {
            /* Wave-3: Abort early if session died (no point waiting for marker). */
            if (!session.isAlive) {
                val partialOutput = stripMarker(computeNewOutput(outputBefore, session.getCleanOutput()))
                Log.w(TAG, "Session died while waiting for marker $markerIdCounter (cmd: $command)")
                return@withContext ExecutionOutcome.TimedOut(partialOutput)
            }
            delay(pollDelayMs)
            outputAfter = session.getCleanOutput()

            // Phase 46 (Pilar 1a): Pakai computeNewOutput helper (fix roll-over bug)
            val newOutput = computeNewOutput(outputBefore, outputAfter)

            // Cek marker
            val marker = parseMarker(newOutput)
            if (marker != null && marker.id == markerIdCounter) {
                val executionTimeMs = System.currentTimeMillis() - startTime
                val markerPos = newOutput.indexOf(marker.rawMarker)
                val cleanOutput = if (markerPos >= 0) {
                    stripMarker(newOutput.substring(0, markerPos))
                } else {
                    stripMarker(newOutput)
                }
                Log.i(TAG, "Command '$command' completed: exitCode=${marker.exitCode}, time=${executionTimeMs}ms")
                return@withContext ExecutionOutcome.Completed(
                    CommandResult(
                        command = command,
                        output = cleanOutput.trim(),
                        exitCode = marker.exitCode,
                        isSuccess = marker.isSuccess,
                        executionTimeMs = executionTimeMs
                    )
                )
            }

            // Track idle — update lastChangeTime kalau output berubah
            if (outputAfter.length != lastOutputLen) {
                lastOutputLen = outputAfter.length
                lastChangeTime = System.currentTimeMillis()
                /* v8.6.0 fix (H3): Output berubah → reset poll delay ke 25ms
                 * untuk responsif saat output aktif (apt sedang print progress). */
                pollDelayMs = 25L
            } else {
                /* v8.6.0 fix (H3): Output stabil → exponential backoff (cap 500ms).
                 * Saves CPU saat command butuh waktu lama tanpa output (e.g. compile). */
                pollDelayMs = (pollDelayMs * 2).coerceAtMost(500L)
            }
            if (System.currentTimeMillis() - lastChangeTime > idleTimeoutMs) {
                // Idle > idleTimeoutMs → kemungkinan menunggu input interaktif
                val elapsedMs = System.currentTimeMillis() - startTime
                val partialOutput = stripMarker(computeNewOutput(outputBefore, outputAfter))
                Log.w(TAG, "Command '$command' possibly waiting for input (idle ${idleTimeoutMs}ms, elapsed ${elapsedMs}ms)")
                return@withContext ExecutionOutcome.PossiblyWaitingForInput(
                    partialOutput = partialOutput,
                    elapsedMs = elapsedMs
                )
            }
        }

        // Max timeout tercapai tanpa marker
        val partialOutput = stripMarker(computeNewOutput(outputBefore, outputAfter))
        Log.w(TAG, "Timeout waiting for marker $markerIdCounter (cmd: $command, maxTimeout ${maxTimeoutMs}ms)")
        ExecutionOutcome.TimedOut(partialOutput)
    }

    /**
     * Format result untuk dikirim balik ke AI sebagai context.
     * AI menerima output + exit code → bisa analisis dan tentukan next step.
     */
    fun formatResultForAI(result: CommandResult): String {
        return buildString {
            append("Command: ${result.command}\n")
            append("Exit code: ${result.exitCode}\n")
            if (result.isSuccess) {
                append("Status: SUCCESS\n")
            } else {
                append("Status: ${if (result.exitCode == -1) "TIMEOUT" else "ERROR"}\n")
            }
            append("Output:\n")
            if (result.output.isBlank()) {
                append("(no output)\n")
            } else {
                append(result.output.take(2000))
                if (result.output.length > 2000) {
                    append("\n... (output truncated, ${result.output.length - 2000} more chars)")
                }
                append("\n")
            }
        }
    }

    /**
     * Phase 46 (Pilar 1b): Format ExecutionOutcome untuk AI.
     * Tangani ketiga outcome dengan pesan yang jelas ke AI.
     */
    fun formatOutcomeForAI(outcome: ExecutionOutcome): String {
        return when (outcome) {
            is ExecutionOutcome.Completed -> formatResultForAI(outcome.result)
            is ExecutionOutcome.PossiblyWaitingForInput -> buildString {
                append("Command: (idle timeout — kemungkinan menunggu input)\n")
                append("Status: POSSIBLY_WAITING_FOR_INPUT\n")
                append("Elapsed: ${outcome.elapsedMs}ms (no new output for 15s)\n")
                append("Partial output:\n")
                if (outcome.partialOutput.isBlank()) {
                    append("(no output captured)\n")
                } else {
                    append(outcome.partialOutput.take(1500))
                    append("\n")
                }
                append("\nKemungkinan command sedang menunggu input interaktif (mis. konfirmasi apt). ")
                append("JANGAN kirim command baru menebak jawaban — tanyakan ke user apa yang harus dilakukan.")
            }
            is ExecutionOutcome.TimedOut -> buildString {
                append("Command: (max timeout exceeded)\n")
                append("Status: TIMED_OUT\n")
                append("Partial output:\n")
                if (outcome.partialOutput.isBlank()) {
                    append("(no output captured)\n")
                } else {
                    append(outcome.partialOutput.take(1500))
                    append("\n")
                }
            }
        }
    }
}
