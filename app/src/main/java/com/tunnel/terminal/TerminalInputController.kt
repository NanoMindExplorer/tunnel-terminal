package com.tunnel.terminal

/**
 * v9.4.0: Terminal soft-IME / line-buffer controller extracted from MainActivity.
 *
 * Pure decision + side-effect callbacks so unit tests can cover typing without Compose.
 */
class TerminalInputController(
    private val sessionType: () -> String,
    private val getBuffer: () -> String,
    private val setBuffer: (String) -> Unit,
    private val writeRaw: (String) -> Unit,
    private val isCursorAtEnd: () -> Boolean,
    private val setCursorAtEnd: (Boolean) -> Unit,
    private val getImeLast: () -> String,
    private val setImeLast: (String) -> Unit,
    private val pinIme: (String) -> Unit,
    private val onEnterLine: (String) -> Unit,
    private val isEnterHandledByKey: () -> Boolean,
    private val clearEnterHandled: () -> Unit
) {

    data class HandleResult(
        val applied: Boolean,
        val ignoredNoise: Boolean = false
    )

    fun onImeTextChange(newValue: String): HandleResult {
        val last = getImeLast()
        if (newValue == last) {
            pinIme(last)
            return HandleResult(applied = false)
        }

        /* Soft-repair tracker-only drift when at EOL. */
        if (getBuffer() != last && isCursorAtEnd()) {
            val buf = getBuffer()
            if (last.startsWith(buf) || buf.startsWith(last) || buf.isEmpty() || last.isEmpty()) {
                setBuffer(last)
            }
        }

        val plan = TerminalImeDelta.plan(
            last = last,
            newValue = newValue,
            commandBuffer = getBuffer(),
            cursorLikelyAtEnd = isCursorAtEnd()
        )

        if (plan.ignored) {
            pinIme(plan.syncTo)
            setImeLast(plan.syncTo)
            return HandleResult(applied = false, ignoredNoise = true)
        }

        val readline = sessionType() == "ubuntu" || sessionType() == "ssh"

        fun sendBackspace() {
            if (getBuffer().isNotEmpty()) setBuffer(getBuffer().dropLast(1))
            writeRaw("\u007F")
        }

        fun typePrintable(ch: Char) {
            setBuffer(getBuffer() + ch)
            writeRaw(ch.toString())
        }

        fun clearLineFromTracker() {
            if (readline) writeRaw(5.toChar().toString()) /* Ctrl+E only for bash/readline */
            val n = getBuffer().length
            repeat(n) { writeRaw("\u007F") }
            setBuffer("")
        }

        fun rewrite(desired: String) {
            clearLineFromTracker()
            if (desired.isNotEmpty()) writeRaw(desired)
            setBuffer(desired)
            setCursorAtEnd(true)
        }

        if (plan.containsEnter) {
            val line = newValue.replace("\r", "").replace("\n", "")
            if (plan.fullRewrite) {
                rewrite(line)
            } else if (getBuffer() != line) {
                setBuffer(line)
            }
            if (!isEnterHandledByKey()) {
                onEnterLine(getBuffer() + "\n")
                setBuffer("")
            }
            clearEnterHandled()
            pinIme("")
            setImeLast("")
            setCursorAtEnd(true)
            return HandleResult(applied = true)
        }

        if (plan.fullRewrite) {
            rewrite(plan.syncTo)
            pinIme(plan.syncTo)
            setImeLast(plan.syncTo)
            return HandleResult(applied = true)
        }

        /* LCP delta — never Ctrl+E on local sh. */
        setCursorAtEnd(true)
        repeat(plan.backspaces) { sendBackspace() }
        for (ch in plan.typeChars) {
            if (ch == '\n' || ch == '\r') continue
            if (ch == '\u007F' || ch == '\b') sendBackspace()
            else typePrintable(ch)
        }
        pinIme(plan.syncTo)
        setImeLast(plan.syncTo)
        return HandleResult(applied = true)
    }
}
