package com.tunnel.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Phase 47 (Bagian 2): Agent Screen — UI untuk AgentTaskRunner.
 *
 * User input goal → pilih environment → Start → real-time event log.
 * Tombol Pause/Stop untuk kontrol selama task berjalan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    theme: TerminalTheme,
    isRunning: Boolean,
    isPaused: Boolean = false,
    events: List<AgentTaskRunner.AgentEvent>,
    onStart: (goal: String, useUbuntu: Boolean) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit
) {
    var goalText by remember { mutableStateOf("") }
    var useUbuntu by remember { mutableStateOf(true) }
    val scrollState = rememberScrollState()

    // Auto-scroll ke bawah saat event baru
    LaunchedEffect(events.size) {
        if (scrollState.maxValue > 0) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isRunning) onDismiss() },
        modifier = Modifier.fillMaxSize(0.95f).background(theme.uiBg),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🤖 ", color = theme.uiAccent, fontSize = 20.sp)
                Text(
                    "Agent Mode — Autonomous Task Runner",
                    color = theme.uiText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxSize()) {
                /* Goal input */
                if (!isRunning) {
                    OutlinedTextField(
                        value = goalText,
                        onValueChange = { goalText = it },
                        label = { Text("Goal — deskripsikan tugas yang ingin diselesaikan", color = theme.uiTextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                        placeholder = { Text("Mis: Buat CLI tool Python yang hitung factorial, compile dan test", color = theme.uiTextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                        modifier = Modifier.fillMaxWidth().height(80.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(color = theme.uiText, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = theme.uiAccent,
                            unfocusedBorderColor = theme.uiSurface,
                            cursorColor = theme.uiAccent
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    /* Environment picker */
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Environment: ", color = theme.uiTextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        FilterChip(
                            selected = !useUbuntu,
                            onClick = { useUbuntu = false },
                            label = { Text("Local", fontSize = 10.sp) }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        FilterChip(
                            selected = useUbuntu,
                            onClick = { useUbuntu = true },
                            label = { Text("🐧 Ubuntu", fontSize = 10.sp) }
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        if (useUbuntu) "Ubuntu via proot — sandbox aman, apt/gcc/python/node tersedia"
                        else "Android shell lokal — terbatas, tidak ada package manager",
                        color = theme.uiTextMuted,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                /* Event log */
                Box(modifier = Modifier.weight(1f).fillMaxWidth().background(theme.background, RoundedCornerShape(4.dp))) {
                    if (events.isEmpty()) {
                        Text(
                            "Belum ada aktivitas.\n\n" +
                            "Cara pakai:\n" +
                            "1. Tulis goal di atas (mis. 'Buat web scraper Python')\n" +
                            "2. Pilih environment (Ubuntu recommended)\n" +
                            "3. Tap Start\n" +
                            "4. AI akan bekerja otonom — tulis file, jalankan command, fix error\n" +
                            "5. Tap Stop kalau mau hentikan\n\n" +
                            "Agent akan berhenti otomatis kalau:\n" +
                            "- AI bilang selesai (<agent_done>)\n" +
                            "- Mencapai 40 iterasi\n" +
                            "- AI stuck 3 iterasi tanpa aksi\n" +
                            "- Aksi berisiko butuh approval",
                            color = theme.uiTextMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(12.dp).verticalScroll(scrollState)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(events) { event ->
                                AgentEventItem(event, theme)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isRunning) {
                    /* Wave-1: Pause is no longer a one-way trap — show Resume when paused. */
                    if (isPaused) {
                        TextButton(onClick = onResume) {
                            Text("▶ Resume", color = Color(0xFF4CAF50), fontFamily = FontFamily.Monospace)
                        }
                    } else {
                        TextButton(onClick = onPause) {
                            Text("⏸ Pause", color = Color(0xFFFFAB00), fontFamily = FontFamily.Monospace)
                        }
                    }
                    TextButton(onClick = onStop) {
                        Text("⏹ Stop", color = Color(0xFFFF5252), fontFamily = FontFamily.Monospace)
                    }
                } else {
                    TextButton(
                        onClick = { onStart(goalText.trim(), useUbuntu) },
                        enabled = goalText.isNotBlank()
                    ) {
                        Text("▶ Start", color = if (goalText.isNotBlank()) theme.uiAccent else theme.uiTextMuted, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        },
        dismissButton = {
            if (!isRunning) {
                TextButton(onClick = onDismiss) {
                    Text("Close", color = theme.uiTextMuted, fontFamily = FontFamily.Monospace)
                }
            }
        }
    )
}

@Composable
private fun AgentEventItem(event: AgentTaskRunner.AgentEvent, theme: TerminalTheme) {
    val (icon, color, text) = when (event) {
        is AgentTaskRunner.AgentEvent.Status -> Triple("▶", theme.uiTextMuted, event.message)
        is AgentTaskRunner.AgentEvent.ToolResult -> {
            val ic = if (event.success) "✓" else "✗"
            val col = if (event.success) Color(0xFF4CAF50) else Color(0xFFFF5252)
            Triple(ic, col, "${event.tool}: ${event.argsSummary}\n${event.resultSummary}")
        }
        is AgentTaskRunner.AgentEvent.NeedsApproval -> Triple("🔐", Color(0xFFFFAB00), "Need approval: ${event.reason}\nCall: ${event.call.displayText}")
        is AgentTaskRunner.AgentEvent.Done -> Triple("✅", Color(0xFF4CAF50), "SELESAI: ${event.summary}")
        is AgentTaskRunner.AgentEvent.StoppedForSafety -> Triple("⚠️", Color(0xFFFF5252), "Dihentikan: ${event.reason}")
        is AgentTaskRunner.AgentEvent.NeedsClarification -> Triple("❓", Color(0xFFFFAB00), "Butuh klarifikasi: ${event.question}")
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = theme.uiBg,
        shape = RoundedCornerShape(4.dp)
    ) {
        Row(modifier = Modifier.padding(8.dp)) {
            Text(icon, color = color, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(end = 6.dp))
            Text(text, color = theme.uiText, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
        }
    }
}
