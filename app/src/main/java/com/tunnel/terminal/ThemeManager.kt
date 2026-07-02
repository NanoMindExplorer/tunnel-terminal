package com.tunnel.terminal

import android.content.Context
import androidx.compose.ui.graphics.Color

/**
 * TerminalTheme - Definisi tema terminal.
 *
 * Mendefinisikan warna background, foreground, dan palette ANSI 16 warna
 * (8 normal + 8 bright) yang digunakan emulator terminal.
 *
 * Defines background, foreground, and 16-color ANSI palette used by terminal emulator.
 */
data class TerminalTheme(
    val name: String,
    val background: Color,
    val foreground: Color,
    val cursor: Color,
    val selection: Color,
    val uiBg: Color,           // Background UI elements (drawer, bars)
    val uiSurface: Color,      // Surface color (cards, dialogs)
    val uiAccent: Color,       // Accent color (buttons, highlights)
    val uiText: Color,         // UI text color
    val uiTextMuted: Color,    // Muted/secondary text
    val ansi: List<Color>      // 16 ANSI colors: 0=black ... 7=white, 8=bright black ... 15=bright white
) {
    /** Warna ANSI index 0-7 (normal). */
    val normal: List<Color> get() = ansi.subList(0, 8)
    /** Warna ANSI index 8-15 (bright). */
    val bright: List<Color> get() = ansi.subList(8, 16)
}

/**
 * ThemeManager - Kumpulan preset tema + persistensi pilihan user.
 *
 * Phase 18 (Streaming + Themes):
 * - 6 tema siap pakai: Matrix (default), Dracula, Solarized Dark, Monokai Pro, Nord, Tokyo Night
 * - Persist pilihan ke SharedPreferences
 * - Akses tema aktif via getActiveTheme()
 *
 * Theme presets + persistence. 6 themes included.
 */
object ThemeManager {
    private const val PREFS_NAME = "TunnelTheme"
    private const val KEY_THEME_NAME = "theme_name"
    private const val DEFAULT_THEME = "Matrix"

    // ─── Theme Definitions (MUST be declared before presets list) ─────

    /** Matrix - tema hijau neon default (signature Tunnel Terminal). */
    val matrixTheme = TerminalTheme(
        name = "Matrix",
        background = Color(0xFF000000),
        foreground = Color(0xFF00FF00),
        cursor = Color(0xFF00FF00),
        selection = Color(0xFF005500),
        uiBg = Color(0xFF1A1A1A),
        uiSurface = Color(0xFF2B2B2B),
        uiAccent = Color(0xFF6200EE),
        uiText = Color(0xFFFFFFFF),
        uiTextMuted = Color(0xFFAAAAAA),
        ansi = listOf(
            Color(0xFF000000), Color(0xFFFF5252), Color(0xFF4CAF50), Color(0xFFFFC107), // 0-3 black red green yellow
            Color(0xFF2196F3), Color(0xFFE040FB), Color(0xFF00BCD4), Color(0xFFEEEEEE), // 4-7 blue magenta cyan white
            Color(0xFF757575), Color(0xFFFF8A80), Color(0xFFB9F6CA), Color(0xFFFFF59D), // 8-11 bright
            Color(0xFF82B1FF), Color(0xFFEA80FC), Color(0xFF84FFFF), Color(0xFFFFFFFF)  // 12-15 bright
        )
    )

    /** Dracula - tema gelap populer untuk developer. */
    val draculaTheme = TerminalTheme(
        name = "Dracula",
        background = Color(0xFF282A36),
        foreground = Color(0xFFF8F8F2),
        cursor = Color(0xFFFF79C6),
        selection = Color(0xFF6272A4),
        uiBg = Color(0xFF21222C),
        uiSurface = Color(0xFF44475A),
        uiAccent = Color(0xFFBD93F9),
        uiText = Color(0xFFF8F8F2),
        uiTextMuted = Color(0xFF6272A4),
        ansi = listOf(
            Color(0xFF000000), Color(0xFFFF5555), Color(0xFF50FA7B), Color(0xFFF1FA8C),
            Color(0xFFBD93F9), Color(0xFFFF79C6), Color(0xFF8BE9FD), Color(0xFFBFBFBF),
            Color(0xFF4D4D4D), Color(0xFFFF6E67), Color(0xFF5AF78E), Color(0xFFF4F99D),
            Color(0xFFCAA9FA), Color(0xFFFF92D0), Color(0xFF9AEDFE), Color(0xFFE6E6E6)
        )
    )

    /** Solarized Dark - tema ergonomis populer. */
    val solarizedDarkTheme = TerminalTheme(
        name = "Solarized Dark",
        background = Color(0xFF002B36),
        foreground = Color(0xFF839496),
        cursor = Color(0xFFEEE8D5),
        selection = Color(0xFF073642),
        uiBg = Color(0xFF00323F),
        uiSurface = Color(0xFF073642),
        uiAccent = Color(0xFF268BD2),
        uiText = Color(0xFFEEE8D5),
        uiTextMuted = Color(0xFF586E75),
        ansi = listOf(
            Color(0xFF073642), Color(0xFFDC322F), Color(0xFF859900), Color(0xFFB58900),
            Color(0xFF268BD2), Color(0xFFD33682), Color(0xFF2AA198), Color(0xFFEEE8D5),
            Color(0xFF002B36), Color(0xFFCB4B16), Color(0xFF586E75), Color(0xFF657B83),
            Color(0xFF839496), Color(0xFF6C71C4), Color(0xFF93A1A1), Color(0xFFFDF6E3)
        )
    )

    /** Monokai Pro - tema klasik editor Sublime Text. */
    val monokaiProTheme = TerminalTheme(
        name = "Monokai Pro",
        background = Color(0xFF2D2A2E),
        foreground = Color(0xFFFCFCFA),
        cursor = Color(0xFFFCFCFA),
        selection = Color(0xFF403E41),
        uiBg = Color(0xFF221F22),
        uiSurface = Color(0xFF403E41),
        uiAccent = Color(0xFFFF6188),
        uiText = Color(0xFFFCFCFA),
        uiTextMuted = Color(0xFF727072),
        ansi = listOf(
            Color(0xFF2D2A2E), Color(0xFFFF6188), Color(0xFFA9DC76), Color(0xFFFFD866),
            Color(0xFF78DCE8), Color(0xFFAB9DF2), Color(0xFF78DCE8), Color(0xFFFCFCFA),
            Color(0xFF727072), Color(0xFFFF6188), Color(0xFFA9DC76), Color(0xFFFFD866),
            Color(0xFF78DCE8), Color(0xFFAB9DF2), Color(0xFF78DCE8), Color(0xFFFCFCFA)
        )
    )

    /** Nord - tema Arctic, north-bluish color palette. */
    val nordTheme = TerminalTheme(
        name = "Nord",
        background = Color(0xFF2E3440),
        foreground = Color(0xFFD8DEE9),
        cursor = Color(0xFFD8DEE9),
        selection = Color(0xFF434C5E),
        uiBg = Color(0xFF292E39),
        uiSurface = Color(0xFF3B4252),
        uiAccent = Color(0xFF88C0D0),
        uiText = Color(0xFFECEFF4),
        uiTextMuted = Color(0xFF4C566A),
        ansi = listOf(
            Color(0xFF3B4252), Color(0xFFBF616A), Color(0xFFA3BE8C), Color(0xFFEBCB8B),
            Color(0xFF81A1C1), Color(0xFFB48EAD), Color(0xFF88C0D0), Color(0xFFE5E9F0),
            Color(0xFF4C566A), Color(0xFFBF616A), Color(0xFFA3BE8C), Color(0xFFEBCB8B),
            Color(0xFF81A1C1), Color(0xFFB48EAD), Color(0xFF8FBCBB), Color(0xFFECEFF4)
        )
    )

    /** Tokyo Night - tema gelap modern inspired by Tokyo city lights. */
    val tokyoNightTheme = TerminalTheme(
        name = "Tokyo Night",
        background = Color(0xFF1A1B26),
        foreground = Color(0xFFA9B1D6),
        cursor = Color(0xFFC0CAF5),
        selection = Color(0xFF33467C),
        uiBg = Color(0xFF16161E),
        uiSurface = Color(0xFF24283B),
        uiAccent = Color(0xFF7AA2F7),
        uiText = Color(0xFFC0CAF5),
        uiTextMuted = Color(0xFF565F89),
        ansi = listOf(
            Color(0xFF15161E), Color(0xFFF7768E), Color(0xFF9ECE6A), Color(0xFFE0AF68),
            Color(0xFF7AA2F7), Color(0xFFBB9AF7), Color(0xFF7DCFFF), Color(0xFFA9B1D6),
            Color(0xFF414868), Color(0xFFF7768E), Color(0xFF9ECE6A), Color(0xFFE0AF68),
            Color(0xFF7AA2F7), Color(0xFFBB9AF7), Color(0xFF7DCFFF), Color(0xFFC0CAF5)
        )
    )

    // ─── Presets & API (declared AFTER theme definitions) ─────────────

    /** Preset tema yang tersedia. Available theme presets. */
    val presets: List<TerminalTheme> = listOf(
        matrixTheme,
        draculaTheme,
        solarizedDarkTheme,
        monokaiProTheme,
        nordTheme,
        tokyoNightTheme
    )

    /** Tema default. Default theme. */
    val defaultTheme: TerminalTheme = matrixTheme

    /** Ambil tema aktif dari SharedPreferences. Get active theme from prefs. */
    fun getActiveTheme(context: Context): TerminalTheme {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_THEME_NAME, DEFAULT_THEME) ?: DEFAULT_THEME
        return presets.firstOrNull { it.name == name } ?: defaultTheme
    }

    /** Simpan pilihan tema. Save theme selection. */
    fun setActiveTheme(context: Context, theme: TerminalTheme) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_THEME_NAME, theme.name).apply()
    }
}
