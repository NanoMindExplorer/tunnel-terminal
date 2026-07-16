package com.tunnel.terminal

/**
 * Wave-27/29: Pure IME → PTY delta planner for the transparent terminal field.
 *
 * Soft keyboards (Gboard etc.) + Compose recomposition (shell echo → screenDirty)
 * often fire spurious [onValueChange] events that:
 *  - wipe the whole field to ""
 *  - shrink by multiple characters in one event ("hello" → "hel")
 * Treating those as real backspaces erases characters the user already typed.
 *
 * Wave-29: Characters appearing *before* previously typed text is usually
 * TextField selection jumping to index 0 after recompose, or PTY cursor not
 * at EOL after Home/←. Caller must pin selection to end and snap PTY to EOL.
 *
 * Strategy:
 * 1. Longest-common-prefix (LCP) — minimal backspaces + append
 * 2. Ignore full wipe to empty when ≥2 chars were tracked
 * 3. Ignore pure multi-char deletion in a single event
 * 4. Prefer full line rewrite when buffer desyncs or cursor not at EOL
 */
object TerminalImeDelta {

    data class Plan(
        /** Number of DEL/backspace to send to PTY (and drop from line buffer). */
        val backspaces: Int,
        /** Characters to type after backspaces (may include \\n). */
        val typeChars: String,
        /** Value to store in the controlled TextField / last-tracker. */
        val syncTo: String,
        /** True when the event was treated as IME noise (no PTY mutation). */
        val ignored: Boolean = false,
        /** True when Enter was part of typeChars (caller may clear field). */
        val containsEnter: Boolean = false,
        /**
         * When true, caller should clear the whole shell line (from tracked length)
         * then write [syncTo] instead of applying backspaces/typeChars.
         * Used when PTY cursor may not be at EOL or trackers desynced.
         */
        val fullRewrite: Boolean = false
    )

    /**
     * @param last previously accepted IME string (imeFieldLast)
     * @param newValue incoming BasicTextField value
     * @param commandBuffer app-tracked shell line (currentCommandBuffer)
     * @param cursorLikelyAtEnd false after Home/←/Ctrl+A without End/→
     */
    fun plan(
        last: String,
        newValue: String,
        commandBuffer: String = last,
        cursorLikelyAtEnd: Boolean = true
    ): Plan {
        if (newValue == last) {
            return Plan(backspaces = 0, typeChars = "", syncTo = last)
        }

        val lcp = longestCommonPrefixLen(last, newValue)
        val deleteCount = last.length - lcp
        val append = newValue.substring(lcp)
        val containsEnter = append.contains('\n') || append.contains('\r')

        /* Full wipe → empty: almost always recompose noise, not user intent. */
        if (newValue.isEmpty() && last.length >= 2) {
            return Plan(0, "", last, ignored = true)
        }

        /* Pure multi-char shrink in ONE event — spurious recompose. */
        if (deleteCount > 1 && append.isEmpty()) {
            return Plan(0, "", last, ignored = true)
        }

        if (newValue.isEmpty() && last.length == 1) {
            return Plan(backspaces = 1, typeChars = "", syncTo = "", containsEnter = false)
        }

        val desync = commandBuffer != last
        val needRewrite = desync || !cursorLikelyAtEnd

        /* Enter: still use type path so processInput runs; rewrite line first if needed. */
        if (containsEnter) {
            val withoutEnter = newValue.replace("\r", "").replace("\n", "")
            return Plan(
                backspaces = 0,
                typeChars = "\n",
                syncTo = "",
                containsEnter = true,
                fullRewrite = needRewrite || withoutEnter != commandBuffer,
                /* typeChars only newline; caller rewrites [withoutEnter] first if fullRewrite */
            ).let { p ->
                /* Stash line-before-enter in typeChars as special? Caller uses last/newValue. */
                p.copy(typeChars = if (p.fullRewrite) withoutEnter + "\n" else append)
            }
        }

        if (needRewrite) {
            return Plan(
                backspaces = 0,
                typeChars = newValue,
                syncTo = newValue,
                fullRewrite = true
            )
        }

        return Plan(
            backspaces = deleteCount.coerceAtLeast(0),
            typeChars = append,
            syncTo = newValue,
            ignored = false,
            containsEnter = false,
            fullRewrite = false
        )
    }

    fun longestCommonPrefixLen(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    fun shouldRepairDesync(imeLast: String, commandBuffer: String): Boolean =
        imeLast != commandBuffer
}
