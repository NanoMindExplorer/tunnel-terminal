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
 *
 * Masalah lama:
 * - Auto-Pilot pakai regex nebak prompt shell → false positive (output mengandung $ atau #)
 * - run_command fire-and-forget → AI tidak tahu hasil/exit code command
 * - @command: stub kosong → tidak pernah benar-benar mengeksekusi
 *
 * Solusi: Marker-based execution
 * - Setiap command dibungkus: `cmd ; echo "__TT_DONE_<id>_<exitcode>__"`
 * - Marker unik per-command (AtomicLong counter) → tidak collide dengan output command
 * - Tunggu marker muncul di output → command selesai, exit code tercapture
 * - Kirim hasil balik ke AI sebagai context untuk analisis/next step
 *
 * Marker format: __TT_DONE_<id>_<exitcode>__
 * - id: unique counter (mis. 1, 2, 3, ...)
 * - exitcode: $? dari shell (0 = success, non-zero = error)
 *
 * Contoh:
 *   Command: ls -la
 *   Dikirim ke PTY: ls -la ; echo "__TT_DONE_1_$?__"
 *   Output terminal: [ls output...]\n__TT_DONE_1_0__
 *   Parser: menemukan marker → id=1, exitCode=0 → command selesai, success
 */
class MarkerExecutor {

    companion object {
        private const val TAG = "MarkerExecutor"
        private const val MARKER_PREFIX = "__TT_DONE_"
        private const val MARKER_SUFFIX = "__"
        private val MARKER_REGEX = Regex("__TT_DONE_(\\d+)_(\\d+)__")

        /** Atomic counter untuk unique marker ID. */
        private val markerIdCounter = AtomicLong(0)

        /** Generate unique marker ID. */
        fun nextMarkerId(): Long = markerIdCounter.incrementAndGet()

        /**
         * Build command dengan marker appended.
         * Phase 40 fix (H6): Capture exit code SEBELUM echo, bukan di echo-nya.
         *
         * OLD BUG: `cmd ; echo "__TT_DONE_1_$?__"` — untuk command seperti
         * `cd /foo && ls`, exit code `$?` adalah exit code `echo` (selalu 0),
         * BUKAN exit code `ls`. Untuk `false || true`, exit code juga = 0
         * (padahal user mungkin mau tahu exit code `false`).
         *
         * FIX: Run command di subshell, capture exit code ke variable `ec`,
         * lalu echo marker dengan `ec` (bukan `$?` yang sudah tertimpa).
         */
        fun wrapCommand(command: String, markerId: Long): String {
            return "{ $command ; } ; ec=\$?; echo \"${MARKER_PREFIX}${markerId}_\${ec}${MARKER_SUFFIX}\""
        }

        /** Parse marker dari output terminal. Returns MarkerResult jika ditemukan. */
        fun parseMarker(output: String): MarkerResult? {
            val match = MARKER_REGEX.find(output) ?: return null
            val id = match.groupValues[1].toLongOrNull() ?: return null
            val exitCode = match.groupValues[2].toIntOrNull() ?: return null
            return MarkerResult(id = id, exitCode = exitCode, rawMarker = match.value)
        }

        /** Hapus marker dari output (untuk display yang clean). */
        fun stripMarker(output: String): String {
            return MARKER_REGEX.replace(output, "").trim()
        }
    }

    /** Result dari marker parsing. */
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
     * Eksekusi command di session tertentu, tunggu marker, return result.
     *
     * @param session TerminalSession (local PTY atau SSH)
     * @param command Command to execute (tanpa marker — akan di-wrap otomatis)
     * @param timeoutMs Timeout dalam millisecond (default 30s)
     * @return CommandResult dengan output, exit code, dan timing
     */
    suspend fun executeWithMarker(
        session: TerminalSession,
        command: String,
        timeoutMs: Long = 30000
    ): CommandResult = withContext(Dispatchers.IO) {
        val markerId = nextMarkerId()
        val wrappedCommand = wrapCommand(command, markerId)
        val startTime = System.currentTimeMillis()

        // Capture output sebelum command
        val outputBefore = session.getCleanOutput()

        // Kirim command + marker ke PTY
        session.writeRaw(wrappedCommand + "\n")

        // Poll untuk marker di output
        var result: MarkerResult? = null
        var outputAfter = ""

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            /* Phase 40 fix (M2): Reduce poll delay dari 100ms → 25ms.
             * OLD BUG: 100ms delay bisa miss exit code untuk command sangat cepat
             * (output buffer mungkin sudah ter-truncate). 25ms cukup responsif
             * tanpa terlalu banyak CPU usage. */
            delay(25)
            outputAfter = session.getCleanOutput()

            // Cari marker di output yang baru (setelah outputBefore)
            val newOutput = if (outputAfter.length > outputBefore.length && outputAfter.startsWith(outputBefore)) {
                outputAfter.substring(outputBefore.length)
            } else {
                outputAfter
            }

            result = parseMarker(newOutput)
            if (result != null && result.id == markerId) break
        }

        val executionTimeMs = System.currentTimeMillis() - startTime

        if (result == null) {
            // Timeout — marker tidak ditemukan
            Log.w(TAG, "Timeout waiting for marker $markerId (cmd: $command)")
            return@withContext CommandResult(
                command = command,
                output = stripMarker(outputAfter.substringAfter(outputBefore, "")),
                exitCode = -1,
                isSuccess = false,
                executionTimeMs = executionTimeMs
            )
        }

        // Extract output antara outputBefore dan marker
        val fullNewOutput = outputAfter.substringAfter(outputBefore, "")
        val markerPos = fullNewOutput.indexOf(result.rawMarker)
        val cleanOutput = if (markerPos >= 0) {
            stripMarker(fullNewOutput.substring(0, markerPos))
        } else {
            stripMarker(fullNewOutput)
        }

        Log.i(TAG, "Command '$command' completed: exitCode=${result.exitCode}, time=${executionTimeMs}ms")

        CommandResult(
            command = command,
            output = cleanOutput.trim(),
            exitCode = result.exitCode,
            isSuccess = result.isSuccess,
            executionTimeMs = executionTimeMs
        )
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
}
