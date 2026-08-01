package com.tunnel.terminal

import org.junit.Test
import org.junit.Assert.*

/**
 * v9.2.0: Unit tests for SyntaxHighlighter.
 * Tests: detectLanguage, colorsFromTheme, highlight.
 */
class SyntaxHighlighterTest {

    @Test fun `detectLanguage kotlin`() {
        assertEquals("kotlin", SyntaxHighlighter.detectLanguage("Main.kt"))
    }

    @Test fun `detectLanguage python`() {
        assertEquals("python", SyntaxHighlighter.detectLanguage("script.py"))
    }

    @Test fun `detectLanguage javascript`() {
        assertEquals("javascript", SyntaxHighlighter.detectLanguage("app.js"))
    }

    @Test fun `detectLanguage typescript`() {
        assertEquals("typescript", SyntaxHighlighter.detectLanguage("app.ts"))
    }

    @Test fun `detectLanguage shell`() {
        assertEquals("shell", SyntaxHighlighter.detectLanguage("deploy.sh"))
    }

    @Test fun `detectLanguage json`() {
        assertEquals("json", SyntaxHighlighter.detectLanguage("config.json"))
    }

    @Test fun `detectLanguage xml`() {
        assertEquals("xml", SyntaxHighlighter.detectLanguage("layout.xml"))
    }

    @Test fun `detectLanguage yaml`() {
        assertEquals("yaml", SyntaxHighlighter.detectLanguage("ci.yml"))
    }

    @Test fun `detectLanguage markdown`() {
        assertEquals("markdown", SyntaxHighlighter.detectLanguage("README.md"))
    }

    @Test fun `detectLanguage plain for unknown extension`() {
        assertEquals("plain", SyntaxHighlighter.detectLanguage("file.xyz"))
    }

    @Test fun `detectLanguage plain for no extension`() {
        assertEquals("plain", SyntaxHighlighter.detectLanguage("Makefile"))
    }

    @Test fun `colorsFromTheme returns SyntaxColors`() {
        val theme = ThemeManager.presets[0] // Matrix
        val colors = SyntaxHighlighter.colorsFromTheme(theme)
        assertNotNull(colors.keyword)
        assertNotNull(colors.string)
        assertNotNull(colors.comment)
        assertNotNull(colors.number)
        assertNotNull(colors.function)
        assertNotNull(colors.type)
    }

    @Test fun `highlight returns non-empty for kotlin code`() {
        val theme = ThemeManager.presets[0]
        val colors = SyntaxHighlighter.colorsFromTheme(theme)
        val result = SyntaxHighlighter.highlight("val x = 42", "kotlin", colors)
        assertTrue(result.isNotEmpty())
        assertTrue(result.contains("val"))
        assertTrue(result.contains("42"))
    }

    @Test fun `highlight returns original text for plain language`() {
        val theme = ThemeManager.presets[0]
        val colors = SyntaxHighlighter.colorsFromTheme(theme)
        val text = "just plain text"
        val result = SyntaxHighlighter.highlight(text, "plain", colors)
        assertEquals(text, result.text)
    }

    @Test fun `highlight handles empty string`() {
        val theme = ThemeManager.presets[0]
        val colors = SyntaxHighlighter.colorsFromTheme(theme)
        val result = SyntaxHighlighter.highlight("", "kotlin", colors)
        assertEquals(0, result.length)
    }

    @Test fun `highlight handles python code`() {
        val theme = ThemeManager.presets[0]
        val colors = SyntaxHighlighter.colorsFromTheme(theme)
        val result = SyntaxHighlighter.highlight("def hello():\n    print('hi')", "python", colors)
        assertTrue(result.isNotEmpty())
        assertTrue(result.contains("hello"))
    }

    @Test fun `highlight handles json`() {
        val theme = ThemeManager.presets[0]
        val colors = SyntaxHighlighter.colorsFromTheme(theme)
        val result = SyntaxHighlighter.highlight("""{"key": "value"}""", "json", colors)
        assertTrue(result.isNotEmpty())
        assertTrue(result.contains("key"))
    }

    @Test fun `SyntaxColors data class equality`() {
        val c1 = SyntaxHighlighter.SyntaxColors(
            keyword = androidx.compose.ui.graphics.Color.Red,
            string = androidx.compose.ui.graphics.Color.Green,
            comment = androidx.compose.ui.graphics.Color.Gray,
            number = androidx.compose.ui.graphics.Color.Blue,
            function = androidx.compose.ui.graphics.Color.Yellow,
            type = androidx.compose.ui.graphics.Color.Cyan,
            annotation = androidx.compose.ui.graphics.Color.Magenta,
            punctuation = androidx.compose.ui.graphics.Color.White,
            plain = androidx.compose.ui.graphics.Color.Black
        )
        val c2 = c1.copy()
        assertEquals(c1, c2)
    }
}
