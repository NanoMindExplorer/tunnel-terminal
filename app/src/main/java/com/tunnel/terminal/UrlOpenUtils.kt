package com.tunnel.terminal

/**
 * Wave-14: Detect and sanitize http(s) URLs from terminal selection / output.
 */
object UrlOpenUtils {
    private val URL_REGEX = Regex(
        """https?://[^\s<>"'`\]\[(){}|\\^]+""",
        RegexOption.IGNORE_CASE
    )

    fun extractUrls(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return URL_REGEX.findAll(text)
            .map { trimTrailingPunct(it.value) }
            .filter { it.isNotBlank() && isSafeHttpUrl(it) }
            .distinct()
            .toList()
    }

    fun firstUrl(text: String): String? = extractUrls(text).firstOrNull()

    fun isSafeHttpUrl(url: String): Boolean {
        val u = url.trim()
        if (!u.startsWith("http://", ignoreCase = true) &&
            !u.startsWith("https://", ignoreCase = true)
        ) return false
        if (u.contains("\n") || u.contains("\r") || u.contains(' ')) return false
        return true
    }

    private fun trimTrailingPunct(url: String): String {
        var s = url
        while (s.isNotEmpty() && s.last() in ".,;:!?)]}>'\"") {
            s = s.dropLast(1)
        }
        return s
    }
}
