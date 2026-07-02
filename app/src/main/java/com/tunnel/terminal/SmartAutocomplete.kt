package com.tunnel.terminal

import android.content.Context
import android.speech.SpeechRecognizer
import android.speech.RecognizerIntent
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * SmartAutocomplete - Command suggestions berdasarkan history + context.
 *
 * Phase 23: Smart autocomplete (Warp-style).
 * Saat user type di terminal input, suggest commands berdasarkan:
 * - Command history (most recent first)
 * - Common commands (ls, cd, cat, grep, etc.)
 * - File completion (if starts with path)
 * - AI suggestions (jika enabled)
 *
 * Smart autocomplete — suggest commands based on history + context.
 */
object SmartAutocomplete {
    /** Common commands untuk suggestion. */
    private val COMMON_COMMANDS = listOf(
        "ls", "ls -la", "ls -lh", "cd", "cd ..", "pwd", "cat", "echo", "mkdir",
        "rm", "rm -rf", "cp", "mv", "find", "grep", "grep -r", "sed", "awk",
        "head", "tail", "tail -f", "wc", "sort", "uniq", "diff", "chmod",
        "chown", "ps", "ps aux", "kill", "killall", "top", "df", "df -h",
        "du", "du -sh", "free", "uptime", "whoami", "date", "cal",
        "curl", "wget", "ping", "netstat", "ifconfig",
        "git status", "git add", "git commit", "git push", "git pull", "git log",
        "git diff", "git branch", "git checkout",
        "help", "clear", "exit", "history"
    )

    /** Get suggestions untuk partial command. */
    fun getSuggestions(
        partial: String,
        commandHistory: List<String>,
        limit: Int = 10
    ): List<String> {
        if (partial.isBlank()) return emptyList()

        val lower = partial.lowercase()
        val suggestions = mutableListOf<String>()

        /* 1. History matches (priority — most recent first). */
        commandHistory.reversed().forEach { cmd ->
            if (cmd.lowercase().startsWith(lower) && cmd !in suggestions) {
                suggestions.add(cmd)
            }
        }

        /* 2. Common commands. */
        COMMON_COMMANDS.forEach { cmd ->
            if (cmd.lowercase().startsWith(lower) && cmd !in suggestions) {
                suggestions.add(cmd)
            }
        }

        /* 3. History contains (substring match, lower priority). */
        commandHistory.reversed().forEach { cmd ->
            if (lower.length > 2 && cmd.lowercase().contains(lower) && cmd !in suggestions) {
                suggestions.add(cmd)
            }
        }

        return suggestions.take(limit)
    }
}

/**
 * VoiceInputManager - Speech-to-text untuk AI prompts.
 *
 * Phase 23: Voice input untuk AI chat.
 * User tap mic button → speech recognizer → text → AI prompt.
 *
 * Voice input — speech-to-text for AI prompts.
 */
class VoiceInputManager(private val context: Context) {
    private val tag = "VoiceInputManager"

    /** Check if speech recognition is available. */
    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    /** Create speech recognition intent. */
    fun createIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")  // Bahasa Indonesia
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")  // Fallback English
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your AI prompt...")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
    }
}

/**
 * AutocompleteDropdown - Dropdown composable untuk show suggestions.
 */
@Composable
fun AutocompleteDropdown(
    suggestions: List<String>,
    theme: TerminalTheme,
    onSelect: (String) -> Unit
) {
    if (suggestions.isEmpty()) return

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = theme.uiSurface,
        shape = RoundedCornerShape(4.dp)
    ) {
        LazyColumn(
            modifier = Modifier.heightIn(max = 200.dp),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(suggestions) { suggestion ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(suggestion) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("▶", color = theme.ansi.getOrElse(6) { Color(0xFF00BCD4) }, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        suggestion,
                        color = theme.uiText,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
