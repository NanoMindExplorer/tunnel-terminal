package com.tunnel.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * SyntaxHighlighter - Regex-based syntax highlighting untuk TunnelEditor.
 *
 * Phase 21: Syntax highlighting untuk code editor. Mendukung 6 bahasa:
 * - Kotlin (.kt, .kts)
 * - Python (.py)
 * - JavaScript/TypeScript (.js, .ts, .mjs)
 * - Shell/Bash (.sh, .bash)
 * - JSON (.json)
 * - XML (.xml)
 *
 * Pendekatan: regex-based (bukan tree-sitter). Lebih ringan (~400 lines vs
 * tree-sitter +2-5MB NDK grammar builds). Cukup akurat untuk mobile editor.
 *
 * Regex-based syntax highlighting. Lighter than tree-sitter (~400 lines vs +2-5MB).
 */
object SyntaxHighlighter {

    /** Theme-aware syntax colors (dari terminal ANSI palette). */
    data class SyntaxColors(
        val keyword: Color,
        val string: Color,
        val comment: Color,
        val number: Color,
        val function: Color,
        val type: Color,
        val annotation: Color,
        val punctuation: Color,
        val plain: Color
    )

    /** Get syntax colors dari terminal theme. */
    fun colorsFromTheme(theme: TerminalTheme): SyntaxColors {
        return SyntaxColors(
            keyword = theme.ansi.getOrElse(5) { Color(0xFFE040FB) },     // magenta
            string = theme.ansi.getOrElse(2) { Color(0xFF4CAF50) },      // green
            comment = theme.ansi.getOrElse(8) { Color(0xFF757575) },     // bright black (gray)
            number = theme.ansi.getOrElse(3) { Color(0xFFFFC107) },      // yellow
            function = theme.ansi.getOrElse(6) { Color(0xFF00BCD4) },    // cyan
            type = theme.ansi.getOrElse(4) { Color(0xFF2196F3) },        // blue
            annotation = theme.ansi.getOrElse(1) { Color(0xFFFF5252) },  // red
            punctuation = theme.ansi.getOrElse(7) { Color.White },       // white
            plain = theme.foreground
        )
    }

    /** Deteksi bahasa dari file extension. */
    fun detectLanguage(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "kt", "kts" -> "kotlin"
            "py" -> "python"
            "js", "mjs" -> "javascript"
            "ts" -> "typescript"
            "sh", "bash" -> "shell"
            "json" -> "json"
            "xml", "html", "svg" -> "xml"
            "java" -> "kotlin" // Java mirip Kotlin untuk highlighting dasar
            "c", "cpp", "h" -> "kotlin" // C/C++ mirip untuk highlighting dasar
            "yml", "yaml" -> "yaml"
            "md" -> "markdown"
            else -> "plain"
        }
    }

    /** Highlight text sesuai bahasa. Returns AnnotatedString untuk Compose Text/BasicTextField. */
    fun highlight(text: String, language: String, colors: SyntaxColors): AnnotatedString {
        return when (language) {
            "kotlin" -> highlightKotlin(text, colors)
            "python" -> highlightPython(text, colors)
            "javascript", "typescript" -> highlightJavaScript(text, colors)
            "shell" -> highlightShell(text, colors)
            "json" -> highlightJson(text, colors)
            "xml" -> highlightXml(text, colors)
            "yaml" -> highlightYaml(text, colors)
            "markdown" -> highlightMarkdown(text, colors)
            else -> AnnotatedString(text)
        }
    }

    // ─── Language-specific highlighters ───────────────────────────────

    /** Kotlin keywords. */
    private val kotlinKeywords = setOf(
        "fun", "val", "var", "class", "object", "interface", "enum", "sealed", "data",
        "override", "open", "abstract", "final", "private", "public", "protected", "internal",
        "companion", "init", "constructor", "this", "super", "return", "if", "else", "when",
        "for", "while", "do", "break", "continue", "in", "is", "as", "throw", "try", "catch",
        "finally", "import", "package", "typealias", "suspend", "inline", "operator", "infix",
        "lateinit", "const", "vararg", "reified", "out", "in", "where", "by", "get", "set"
    )

    private fun highlightKotlin(text: String, colors: SyntaxColors): AnnotatedString = buildAnnotatedString {
        val keywordRegex = Regex("\\b(${kotlinKeywords.joinToString("|")})\\b")
        val stringRegex = Regex("\"\"\"[\\s\\S]*?\"\"\"|\"[^\"\\n]*\"|'[^'\\n]*'")
        val commentRegex = Regex("//[^\\n]*|/\\*[\\s\\S]*?\\*/")
        val numberRegex = Regex("\\b\\d[\\d_]*\\.?\\d*([eE][+-]?\\d+)?[fFlL]?\\b")
        val annotationRegex = Regex("@[A-Za-z_][A-Za-z0-9_]*")
        val functionRegex = Regex("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*(?=\\()")
        val typeRegex = Regex("\\b([A-Z][A-Za-z0-9_]*)\\b")

        highlightWithRegexes(text, this, listOf(
            Triple(commentRegex, SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)),
            Triple(stringRegex, SpanStyle(color = colors.string)),
            Triple(annotationRegex, SpanStyle(color = colors.annotation)),
            Triple(keywordRegex, SpanStyle(color = colors.keyword, fontWeight = FontWeight.Bold)),
            Triple(numberRegex, SpanStyle(color = colors.number)),
            Triple(typeRegex, SpanStyle(color = colors.type)),
            Triple(functionRegex, SpanStyle(color = colors.function))
        ), colors.plain)
    }

    /** Python keywords. */
    private val pythonKeywords = setOf(
        "def", "class", "if", "elif", "else", "for", "while", "try", "except", "finally",
        "with", "as", "import", "from", "return", "yield", "raise", "pass", "break",
        "continue", "lambda", "global", "nonlocal", "assert", "del", "in", "is", "not",
        "and", "or", "None", "True", "False", "self", "cls", "async", "await"
    )

    private fun highlightPython(text: String, colors: SyntaxColors): AnnotatedString = buildAnnotatedString {
        val keywordRegex = Regex("\\b(${pythonKeywords.joinToString("|")})\\b")
        val stringRegex = Regex("\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''|\"[^\"\\n]*\"|'[^'\\n]*'|f\"[^\"\\n]*\"|f'[^'\\n]*'")
        val commentRegex = Regex("#[^\\n]*")
        val numberRegex = Regex("\\b\\d+\\.?\\d*([eE][+-]?\\d+)?[jJ]?\\b")
        val functionRegex = Regex("\\bdef\\s+([A-Za-z_][A-Za-z0-9_]*)")
        val decoratorRegex = Regex("@[A-Za-z_][A-Za-z0-9_.]*")

        highlightWithRegexes(text, this, listOf(
            Triple(commentRegex, SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)),
            Triple(stringRegex, SpanStyle(color = colors.string)),
            Triple(decoratorRegex, SpanStyle(color = colors.annotation)),
            Triple(keywordRegex, SpanStyle(color = colors.keyword, fontWeight = FontWeight.Bold)),
            Triple(numberRegex, SpanStyle(color = colors.number)),
            Triple(functionRegex, SpanStyle(color = colors.function))
        ), colors.plain)
    }

    /** JavaScript/TypeScript keywords. */
    private val jsKeywords = setOf(
        "function", "const", "let", "var", "class", "extends", "implements", "interface",
        "type", "enum", "return", "if", "else", "for", "while", "do", "switch", "case",
        "break", "continue", "new", "delete", "typeof", "instanceof", "in", "of", "this",
        "super", "import", "export", "from", "as", "default", "async", "await", "yield",
        "try", "catch", "finally", "throw", "public", "private", "protected", "readonly",
        "static", "get", "set", "abstract", "namespace", "true", "false", "null", "undefined"
    )

    private fun highlightJavaScript(text: String, colors: SyntaxColors): AnnotatedString = buildAnnotatedString {
        val keywordRegex = Regex("\\b(${jsKeywords.joinToString("|")})\\b")
        val stringRegex = Regex("`[\\s\\S]*?`|\"[^\"\\n]*\"|'[^'\\n]*'")
        val commentRegex = Regex("//[^\\n]*|/\\*[\\s\\S]*?\\*/")
        val numberRegex = Regex("\\b\\d+\\.?\\d*([eE][+-]?\\d+)?\\b")
        val functionRegex = Regex("\\bfunction\\s+([A-Za-z_][A-Za-z0-9_]*)|\\b([A-Za-z_][A-Za-z0-9_]*)\\s*(?=\\()")
        val decoratorRegex = Regex("@[A-Za-z_][A-Za-z0-9_]*")

        highlightWithRegexes(text, this, listOf(
            Triple(commentRegex, SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)),
            Triple(stringRegex, SpanStyle(color = colors.string)),
            Triple(decoratorRegex, SpanStyle(color = colors.annotation)),
            Triple(keywordRegex, SpanStyle(color = colors.keyword, fontWeight = FontWeight.Bold)),
            Triple(numberRegex, SpanStyle(color = colors.number)),
            Triple(functionRegex, SpanStyle(color = colors.function))
        ), colors.plain)
    }

    /** Shell/Bash. */
    private fun highlightShell(text: String, colors: SyntaxColors): AnnotatedString = buildAnnotatedString {
        val commentRegex = Regex("#[^\\n]*")
        val stringRegex = Regex("\"[^\"\\n]*\"|'[^'\\n]*'")
        val keywordRegex = Regex("\\b(if|then|else|elif|fi|for|in|do|done|while|case|esac|function|return|exit|export|local|readonly|unset|shift|source|echo|printf|read|set|unset)\\b")
        val variableRegex = Regex("\\$\\{?[A-Za-z_][A-Za-z0-9_]*\\}?|\\$[0-9]|\\$[@#?*!]")
        val numberRegex = Regex("\\b\\d+\\b")

        highlightWithRegexes(text, this, listOf(
            Triple(commentRegex, SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)),
            Triple(stringRegex, SpanStyle(color = colors.string)),
            Triple(keywordRegex, SpanStyle(color = colors.keyword, fontWeight = FontWeight.Bold)),
            Triple(variableRegex, SpanStyle(color = colors.type)),
            Triple(numberRegex, SpanStyle(color = colors.number))
        ), colors.plain)
    }

    /** JSON. */
    private fun highlightJson(text: String, colors: SyntaxColors): AnnotatedString = buildAnnotatedString {
        val keyRegex = Regex("\"([^\"\\\\]|\\\\.)*\"(?=\\s*:)")
        val stringRegex = Regex("(?<=:)\\s*\"([^\"\\\\]|\\\\.)*\"")
        val numberRegex = Regex("\\b-?\\d+\\.?\\d*([eE][+-]?\\d+)?\\b")
        val booleanRegex = Regex("\\b(true|false|null)\\b")

        highlightWithRegexes(text, this, listOf(
            Triple(keyRegex, SpanStyle(color = colors.type, fontWeight = FontWeight.Bold)),
            Triple(stringRegex, SpanStyle(color = colors.string)),
            Triple(numberRegex, SpanStyle(color = colors.number)),
            Triple(booleanRegex, SpanStyle(color = colors.keyword))
        ), colors.plain)
    }

    /** XML/HTML. */
    private fun highlightXml(text: String, colors: SyntaxColors): AnnotatedString = buildAnnotatedString {
        val tagRegex = Regex("</?[A-Za-z][A-Za-z0-9]*|/?>")
        val attrRegex = Regex("\\b([A-Za-z_-]+)(?=\\=)")
        val stringRegex = Regex("\"[^\"]*\"|'[^']*'")
        val commentRegex = Regex("<!--[\\s\\S]*?-->")

        highlightWithRegexes(text, this, listOf(
            Triple(commentRegex, SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)),
            Triple(tagRegex, SpanStyle(color = colors.keyword, fontWeight = FontWeight.Bold)),
            Triple(stringRegex, SpanStyle(color = colors.string)),
            Triple(attrRegex, SpanStyle(color = colors.type))
        ), colors.plain)
    }

    /** YAML. */
    private fun highlightYaml(text: String, colors: SyntaxColors): AnnotatedString = buildAnnotatedString {
        val keyRegex = Regex("^\\s*([A-Za-z_][A-Za-z0-9_-]*)(?=\\s*:)", RegexOption.MULTILINE)
        val stringRegex = Regex("\"[^\"]*\"|'[^']*'")
        val commentRegex = Regex("#[^\\n]*")
        val numberRegex = Regex("\\b\\d+\\.?\\d*\\b")
        val booleanRegex = Regex("\\b(true|false|null|yes|no)\\b")

        highlightWithRegexes(text, this, listOf(
            Triple(commentRegex, SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)),
            Triple(keyRegex, SpanStyle(color = colors.type, fontWeight = FontWeight.Bold)),
            Triple(stringRegex, SpanStyle(color = colors.string)),
            Triple(numberRegex, SpanStyle(color = colors.number)),
            Triple(booleanRegex, SpanStyle(color = colors.keyword))
        ), colors.plain)
    }

    /** Markdown (simplified). */
    private fun highlightMarkdown(text: String, colors: SyntaxColors): AnnotatedString = buildAnnotatedString {
        val headerRegex = Regex("^#{1,6}\\s.*$", RegexOption.MULTILINE)
        val codeRegex = Regex("```[\\s\\S]*?```|`[^`\\n]*`")
        val linkRegex = Regex("\\[[^\\]]*\\]\\([^)]*\\)")
        val boldRegex = Regex("\\*\\*[^*\\n]*\\*\\*|__[^_\\n]*__")

        highlightWithRegexes(text, this, listOf(
            Triple(headerRegex, SpanStyle(color = colors.keyword, fontWeight = FontWeight.Bold)),
            Triple(codeRegex, SpanStyle(color = colors.string, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)),
            Triple(linkRegex, SpanStyle(color = colors.type)),
            Triple(boldRegex, SpanStyle(color = colors.function, fontWeight = FontWeight.Bold))
        ), colors.plain)
    }

    // ─── Highlight engine ─────────────────────────────────────────────

    /**
     * Apply multiple regex highlighters to text. Earlier patterns take priority
     * (later patterns won't overlap already-styled regions).
     */
    private fun highlightWithRegexes(
        text: String,
        builder: AnnotatedString.Builder,
        patterns: List<Triple<Regex, SpanStyle>>,
        plainColor: Color
    ) {
        /* Track which character ranges are already styled. */
        val styled = BooleanArray(text.length)
        val ranges = mutableListOf<Triple<Int, Int, SpanStyle>>()

        for ((regex, style) in patterns) {
            regex.findAll(text).forEach { match ->
                val start = match.range.first
                val end = match.range.last + 1
                /* Skip if any part already styled. */
                var overlap = false
                for (i in start until end) {
                    if (i < styled.size && styled[i]) { overlap = true; break }
                }
                if (!overlap) {
                    ranges.add(Triple(start, end, style))
                    for (i in start until end) {
                        if (i < styled.size) styled[i] = true
                    }
                }
            }
        }

        /* Sort ranges by start position. */
        ranges.sortBy { it.first }

        /* Build annotated string: plain text + styled ranges. */
        var pos = 0
        for ((start, end, style) in ranges) {
            if (pos < start) {
                builder.withStyle(SpanStyle(color = plainColor)) {
                    append(text.substring(pos, start))
                }
            }
            builder.withStyle(style) {
                append(text.substring(start, end))
            }
            pos = end
        }
        if (pos < text.length) {
            builder.withStyle(SpanStyle(color = plainColor)) {
                append(text.substring(pos))
            }
        }
    }
}
