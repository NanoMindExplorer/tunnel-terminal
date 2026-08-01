package com.tunnel.terminal

/**
 * AnsiUtils — Shared ANSI escape sequence stripping + truncation (v8.6.0 fix M2).
 *
 * Sebelumnya: getCleanOutput() body diduplikasi antara PtySessionBase.kt:226
 * dan SshShellExecutor.kt:430. Keduanya punya regex yang sama + logic yang sama
 * + magic number 8000 (hardcoded di SshShellExecutor, const di PtySessionBase).
 *
 * Fix: Extract ke object AnsiUtils. Single source of truth untuk ANSI stripping.
 *
 * Constants juga dipromosi ke sini supaya shared:
 * - OUTPUT_RING_CHARS = 16000 (ring buffer size)
 * - CLEAN_OUTPUT_CHARS = 8000 (AI context output cap)
 */
object AnsiUtils {

    /** Max chars untuk output ring buffer (sebelum di-trim dari depan). */
    const val OUTPUT_RING_CHARS = 16000

    /** Max chars untuk getCleanOutput() yang dikirim ke AI context. */
    const val CLEAN_OUTPUT_CHARS = 8000

    /** Regex untuk ANSI escape sequences: CSI, OSC, dan subset CSI. */
    private val ANSI_REGEX = Regex(
        "\u001B\\[[;?\\d]*[A-Za-z]|\u001B\\][^\\u0007]*\\u0007|\u001B\\[[0-9;]*[A-Za-z]"
    )

    /**
     * Strip ANSI escape sequences dari raw terminal output.
     *
     * @param raw Raw output yang mungkin mengandung ANSI escape codes
     * @return Clean text tanpa escape sequences, trimmed
     */
    fun stripAnsi(raw: String): String {
        val sb = StringBuilder(raw.length)
        var lastEnd = 0
        ANSI_REGEX.findAll(raw).forEach { m ->
            sb.append(raw, lastEnd, m.range.first)
            lastEnd = m.range.last + 1
        }
        sb.append(raw, lastEnd, raw.length)
        return sb.toString().trim()
    }

    /**
     * Strip ANSI + truncate dengan marker supaya AI tahu output di-cut.
     *
     * v8.5.0 fix (H2): Append "... (truncated, N more chars)" marker supaya AI
     * tahu output incomplete dan bisa request lebih jika perlu.
     *
     * @param raw Raw output yang mungkin mengandung ANSI escape codes
     * @param maxChars Max chars untuk output (default CLEAN_OUTPUT_CHARS = 8000)
     * @return Clean, truncated text dengan marker jika di-cut
     */
    fun stripAnsiAndTruncate(raw: String, maxChars: Int = CLEAN_OUTPUT_CHARS): String {
        val cleaned = stripAnsi(raw)
        return if (cleaned.length > maxChars) {
            cleaned.take(maxChars) +
            "\n... (truncated, ${cleaned.length - maxChars} more chars)"
        } else {
            cleaned
        }
    }
}
