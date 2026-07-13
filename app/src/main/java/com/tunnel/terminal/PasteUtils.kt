package com.tunnel.terminal

/**
 * Wave-12: Safe clipboard paste for PTY shells.
 *
 * - Multi-line paste without bracketed mode would execute every line (dangerous).
 * - Bracketed paste (DEC mode 2004) lets bash/readline insert literally.
 */
object PasteUtils {
    private const val MAX_CHARS = 64_000
    private const val BRACKET_START = "\u001B[200~"
    private const val BRACKET_END = "\u001B[201~"

    data class PreparedPaste(
        /** Bytes/string to send to the PTY. */
        val payload: String,
        /** Single-line text tracked in app IME / currentCommandBuffer (no newlines). */
        val lineBuffer: String,
        val multiLine: Boolean,
        val truncated: Boolean
    )

    /**
     * Normalize clipboard text and choose bracketed vs flat paste.
     * @param bracketed when true, wrap with OSC 200~ / 201~ so shells do not run intermediate newlines.
     * @param flattenNewlines when !bracketed and multi-line, replace newlines with spaces (safer default).
     */
    fun prepare(
        raw: String,
        bracketed: Boolean,
        flattenNewlines: Boolean = true
    ): PreparedPaste {
        var text = raw
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("\u0000", "")
        var truncated = false
        if (text.length > MAX_CHARS) {
            text = text.take(MAX_CHARS)
            truncated = true
        }
        val multi = text.contains('\n')
        val lineBuffer = text.replace("\n", " ").trimEnd()
        val payload = when {
            text.isEmpty() -> ""
            bracketed -> BRACKET_START + text + BRACKET_END
            multi && flattenNewlines -> lineBuffer
            else -> text
        }
        return PreparedPaste(payload, lineBuffer, multi, truncated)
    }
}
