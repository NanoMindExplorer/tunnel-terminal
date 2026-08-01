package com.tunnel.terminal

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Wave-17: Shared AI chat bubble / progress chrome for comfortable UX.
 */
@Composable
fun AiChatEmptyState(
    theme: TerminalTheme,
    needsApiKey: Boolean,
    onOpenSettings: () -> Unit,
    onSuggestion: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Text(
            "Tunnel AI Copilot",
            color = theme.uiText,
            fontSize = 16.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "Tanya apa saja tentang terminal, file, atau tugas coding di perangkat ini.",
            color = theme.uiTextMuted,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
        if (needsApiKey) {
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                color = Color(0x33FFC107),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenSettings() }
            ) {
                Text(
                    "⚠ API key belum diisi — ketuk untuk buka Settings",
                    color = Color(0xFFFFD54F),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text("Coba mulai dengan:", color = theme.uiTextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(6.dp))
        val chips = listOf(
            "Jelaskan output terminal terakhir",
            "Perbaiki error di terminal",
            "Buat script bash backup folder home",
            "Ringkas status sistem (disk, memori, proses)"
        )
        chips.forEach { tip ->
            Surface(
                color = theme.uiSurface,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .padding(vertical = 3.dp)
                    .fillMaxWidth()
                    .clickable { onSuggestion(tip) }
            ) {
                Text(
                    "✦  $tip",
                    color = theme.uiAccent,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
fun AiMessageBubble(
    msg: ChatMessage,
    theme: TerminalTheme,
    cursorBlink: Boolean,
    isProcessingAI: Boolean,
    onRunCommand: (String) -> Unit,
    onRunAutoPilot: (List<String>) -> Unit,
    onSaveSnippet: (String) -> Unit,
    onCopy: (String) -> Unit,
    onRerun: (String) -> Unit,
    onRetry: () -> Unit
) {
    val isUser = msg.role == "user"
    val bubbleBg = when {
        msg.isError -> Color(0x33FF5252)
        isUser -> theme.uiSurface
        else -> theme.uiSurface.copy(alpha = 0.65f)
    }
    val nameColor = when {
        msg.isError -> Color(0xFFFF5252)
        isUser -> theme.ansi.getOrElse(2) { Color(0xFF4CAF50) }
        else -> theme.uiAccent
    }
    val displayName = when {
        msg.isError -> "Error"
        isUser -> "Anda"
        else -> "AI"
    }
    val displayContent = when {
        msg.isStreaming && cursorBlink -> msg.content + "▋"
        msg.isStreaming -> msg.content + " "
        else -> msg.content
    }

    Surface(
        color = bubbleBg,
        shape = RoundedCornerShape(
            topStart = 10.dp,
            topEnd = 10.dp,
            bottomStart = if (isUser) 10.dp else 2.dp,
            bottomEnd = if (isUser) 2.dp else 10.dp
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    displayName + if (msg.isStreaming) "  · streaming" else "",
                    color = nameColor,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                if (!msg.isStreaming && msg.content.isNotBlank()) {
                    Text(
                        "Copy",
                        color = theme.uiTextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .clickable { onCopy(msg.content) }
                            .padding(horizontal = 6.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            if (msg.role == "assistant" && !msg.isStreaming && !msg.isError) {
                MarkdownText(
                    markdown = displayContent,
                    theme = theme,
                    fontSize = 13,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                /* v8.6.0 fix (UX): AI thinking indicator dengan elapsed time counter.
                 * Sebelumnya: static "● berpikir…" text tanpa feedback progress.
                 * Sekarang: animated "● berpikir… 3.2s" supaya user tahu AI masih kerja. */
                if (msg.isStreaming && displayContent.isBlank()) {
                    AiThinkingIndicator(theme = theme)
                } else {
                    Text(
                        displayContent.ifBlank { if (msg.isStreaming) "● berpikir…" else "" },
                        color = if (msg.isError) Color(0xFFFF8A80) else theme.uiText,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            /* Actions */
            if (!msg.isStreaming) {
                Spacer(modifier = Modifier.height(6.dp))
                when {
                    msg.isError -> {
                        TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) {
                            Text("↻ Coba lagi", color = theme.uiAccent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                    isUser && msg.content.isNotBlank() -> {
                        TextButton(
                            onClick = { onRerun(msg.content) },
                            enabled = !isProcessingAI,
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("↻ Kirim ulang", color = theme.uiTextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                    msg.commands.size > 1 -> {
                        Text(
                            "🚀 Auto-Pilot · ${msg.commands.size} langkah",
                            color = theme.ansi.getOrElse(3) { Color(0xFFFFEB3B) },
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        msg.commands.take(6).forEachIndexed { i, c ->
                            Text(
                                "  ${i + 1}. $c",
                                color = theme.ansi.getOrElse(6) { Color(0xFF00BCD4) },
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        if (msg.commands.size > 6) {
                            Text("  … +${msg.commands.size - 6} lagi", color = theme.uiTextMuted, fontSize = 10.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = { onRunAutoPilot(msg.commands) },
                                enabled = !isProcessingAI,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = theme.ansi.getOrElse(6) { Color(0xFF00BCD4) }
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) { Text("▶ Jalankan semua", fontSize = 11.sp) }
                            Button(
                                onClick = { onRunCommand(msg.commands.first()) },
                                enabled = !isProcessingAI,
                                colors = ButtonDefaults.buttonColors(containerColor = theme.uiSurface),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) { Text("Langkah 1", fontSize = 11.sp, color = theme.uiText) }
                        }
                    }
                    msg.isCommand || msg.commands.size == 1 -> {
                        val cmdToRun = msg.commands.firstOrNull() ?: msg.content
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = { onRunCommand(cmdToRun) },
                                enabled = !isProcessingAI,
                                colors = ButtonDefaults.buttonColors(containerColor = theme.uiAccent),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) { Text("▶ Jalankan", fontSize = 11.sp) }
                            Button(
                                onClick = { onSaveSnippet(cmdToRun) },
                                colors = ButtonDefaults.buttonColors(containerColor = theme.uiSurface),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) { Text("💾 Simpan", fontSize = 11.sp, color = theme.uiText) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AutoPilotProgressBar(
    current: Int,
    total: Int,
    command: String,
    theme: TerminalTheme,
    onStop: () -> Unit
) {
    Surface(
        color = theme.uiSurface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Auto-Pilot  $current / $total",
                    color = theme.uiAccent,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onStop, contentPadding = PaddingValues(0.dp)) {
                    Text("⏹ Stop", color = Color(0xFFFF5252), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
            LinearProgressIndicator(
                progress = if (total <= 0) 0f else (current.toFloat() / total.toFloat()).coerceIn(0f, 1f),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                color = theme.uiAccent,
                trackColor = theme.background
            )
            Text(
                command,
                color = theme.uiTextMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2
            )
        }
    }
}

@Composable
fun rememberCopyToClipboard(): (String) -> Unit {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    return { text ->
        if (text.isNotBlank()) {
            clipboard.setText(AnnotatedString(text))
            Toast.makeText(context, "Disalin (${text.length} karakter)", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Wave-24: Read clipboard text for pasting into AI chat / Agent fields.
 * Returns null if empty; shows toast with reason.
 */
@Composable
fun rememberPasteFromClipboard(): () -> String? {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    return {
        val text = clipboard.getText()?.text
        when {
            text.isNullOrEmpty() -> {
                Toast.makeText(context, "Clipboard kosong", Toast.LENGTH_SHORT).show()
                null
            }
            else -> {
                Toast.makeText(
                    context,
                    "Ditempel ${text.length} karakter",
                    Toast.LENGTH_SHORT
                ).show()
                text
            }
        }
    }
}

/**
 * Wave-24: Compact paste control for AI chat / agent inputs.
 * Native long-press paste often fails in nested side panels — explicit button is reliable.
 */
@Composable
fun AiPasteButton(
    theme: TerminalTheme,
    enabled: Boolean = true,
    onPaste: (String) -> Unit
) {
    val paste = rememberPasteFromClipboard()
    Button(
        onClick = {
            paste()?.let { onPaste(it) }
        },
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = theme.uiSurface,
            disabledContainerColor = theme.uiSurface.copy(alpha = 0.4f)
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text("📋", fontSize = 14.sp)
    }
}

/**
 * v8.6.0 fix (UX): AI thinking indicator dengan elapsed time counter.
 * Shows "● berpikir… 3.2s" dengan animated dots + elapsed seconds.
 * Update setiap 100ms untuk smooth counter.
 */
@Composable
fun AiThinkingIndicator(theme: TerminalTheme) {
    val elapsedMs = remember { mutableStateOf(0L) }
    val dotCount = remember { mutableStateOf(0) }
    val startTimeMs = remember { System.currentTimeMillis() }

    LaunchedEffect(Unit) {
        while (true) {
            elapsedMs.value = System.currentTimeMillis() - startTimeMs
            dotCount.value = (dotCount.value + 1) % 4
            delay(100)
        }
    }

    val seconds = (elapsedMs.value / 1000.0)
    val dots = ".".repeat(dotCount.value)
    val text = String.format("● berpikir%s %.1fs", dots, seconds)

    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 1.5.dp,
            color = theme.uiAccent
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text,
            color = theme.uiAccent,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
