package com.tunnel.terminal

/**
 * Wave-27: Pure IME → PTY delta planner for the transparent terminal field.
 *
 * Soft keyboards (Gboard etc.) + Compose recomposition (shell echo → screenDirty)
 * often fire spurious [onValueChange] events that:
 *  - wipe the whole field to ""
 *  - shrink by multiple characters in one event ("hello" → "hel")
 * Treating those as real backspaces erases characters the user already typed
 * (they "disappear" on the PTY echo line).
 *
 * Strategy:
 * 1. Longest-common-prefix (LCP) — minimal backspaces + append (handles autocorrect)
 * 2. Ignore full wipe to empty when ≥2 chars were tracked
 * 3. Ignore pure multi-char deletion in a single event (real backspace is 1 char/event;
 *    hold-repeat still arrives as many single-char events)
 * 4. Real single-char backspace and normal append always applied
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
        val containsEnter: Boolean = false
    )

    /**
     * @param last previously accepted IME string (imeFieldLast)
     * @param newValue incoming BasicTextField value
     */
    fun plan(last: String, newValue: String): Plan {
        if (newValue == last) {
            return Plan(backspaces = 0, typeChars = "", syncTo = last)
        }

        val lcp = longestCommonPrefixLen(last, newValue)
        val deleteCount = last.length - lcp
        val append = newValue.substring(lcp)
        val containsEnter = append.contains('\n') || append.contains('\r')

        /* Full wipe → empty: almost always recompose noise, not user intent.
         * User clears line with ^U / Ctrl+U. Single last char may be deleted. */
        if (newValue.isEmpty() && last.length >= 2) {
            return Plan(0, "", last, ignored = true)
        }

        /* Pure multi-char shrink in ONE event ("hello"→"hel") — spurious.
         * Real backspace / key-repeat is always deleteCount == 1 per callback. */
        if (deleteCount > 1 && append.isEmpty()) {
            return Plan(0, "", last, ignored = true)
        }

        /* Empty after single char: real backspace of last glyph. */
        if (newValue.isEmpty() && last.length == 1) {
            return Plan(backspaces = 1, typeChars = "", syncTo = "", containsEnter = false)
        }

        return Plan(
            backspaces = deleteCount.coerceAtLeast(0),
            typeChars = append,
            syncTo = if (containsEnter) "" else newValue,
            ignored = false,
            containsEnter = containsEnter
        )
    }

    fun longestCommonPrefixLen(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    /**
     * After shell/ExtraKeys mutate the line, both trackers must match [line].
     * Also used to repair desync: if buffer and IME disagree, prefer [line].
     */
    fun shouldRepairDesync(imeLast: String, commandBuffer: String): Boolean {
        /* Compare printable view — control chars in buffer are rare mid-line. */
        return imeLast != commandBuffer
    }
}
