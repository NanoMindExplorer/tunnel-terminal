package com.tunnel.terminal

/**
 * Wave-27/29/31: Pure IME → PTY delta planner for the transparent terminal field.
 *
 * Wave-31: Do **not** force fullRewrite on tracker desync alone — that caused
 * clear+rewrite loops that made "ls"/"cd" characters vanish on toybox sh.
 * Full rewrite only when cursor is known not at EOL (Home/←).
 */
object TerminalImeDelta {

    data class Plan(
        val backspaces: Int,
        val typeChars: String,
        val syncTo: String,
        val ignored: Boolean = false,
        val containsEnter: Boolean = false,
        val fullRewrite: Boolean = false
    )

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

        if (newValue.isEmpty() && last.length >= 2) {
            return Plan(0, "", last, ignored = true)
        }

        if (deleteCount > 1 && append.isEmpty()) {
            return Plan(0, "", last, ignored = true)
        }

        if (newValue.isEmpty() && last.length == 1) {
            return Plan(backspaces = 1, typeChars = "", syncTo = "")
        }

        /*
         * IME restart: after we rewrite TextFieldValue (or composition commits),
         * many keyboards send only the newest glyph ("ls" → "s") instead of the
         * full line. Treating that as LCP=0 would backspace the whole command.
         */
        if (lcp == 0 && last.isNotEmpty() && newValue.isNotEmpty() &&
            !containsEnter && cursorLikelyAtEnd
        ) {
            if (newValue.length <= 2 && last.endsWith(newValue) && last.length > newValue.length) {
                return Plan(0, "", last, ignored = true)
            }
            if (newValue.length == 1 && last.length >= 2) {
                return Plan(
                    backspaces = 0,
                    typeChars = newValue,
                    syncTo = last + newValue
                )
            }
        }

        /* Only rewrite when cursor was moved mid-line — not on buffer drift. */
        val needRewrite = !cursorLikelyAtEnd

        if (containsEnter) {
            val withoutEnter = newValue.replace("\r", "").replace("\n", "")
            return Plan(
                backspaces = 0,
                typeChars = if (needRewrite) withoutEnter + "\n" else append,
                syncTo = "",
                containsEnter = true,
                fullRewrite = needRewrite
            )
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
            syncTo = newValue
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
