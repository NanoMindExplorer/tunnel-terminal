package com.tunnel.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Phase 47 + Wave-17: Agent Screen — goal, live log, pause/resume, clarification.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    theme: TerminalTheme,
    isRunning: Boolean,
    isPaused: Boolean = false,
    events: List<AgentTaskRunner.AgentEvent>,
    pendingClarification: String? = null,
    lastGoal: String = "",
    onStart: (goal: String, useUbuntu: Boolean) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
    onAnswerClarification: ((answer: String) -> Unit)? = null
) {
    var goalValue by remember { mutableStateOf(TextFieldValue("")) }
    var useUbuntu by remember { mutableStateOf(true) }
    var clarifyValue by remember { mutableStateOf(TextFieldValue("")) }
    val emptyScroll = rememberScrollState()
    val listState = rememberLazyListState()
    val goalFocus = remember { FocusRequester() }

    fun insertInto(field: TextFieldValue, chunk: String): TextFieldValue {
        val start = field.selection.min.coerceIn(0, field.text.length)
        val end = field.selection.max.coerceIn(0, field.text.length)
        val newText = field.text.replaceRange(start, end, chunk)
        val caret = (start + chunk.length).coerceAtMost(newText.length)
        return TextFieldValue(text = newText, selection = TextRange(caret))
    }

    /* Wave-17: Auto-scroll event LazyColumn when near bottom. */
    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) {
            val last = events.lastIndex
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastVisible >= last - 2) {
                listState.animateScrollToItem(last)
            }
        }
    }

    val iterationHint = events.asReversed().firstOrNull { ev ->
        ev is AgentTaskRunner.AgentEvent.Status && ev.message.contains("Iterasi")
    }?.let { (it as AgentTaskRunner.AgentEvent.Status).message }

    AlertDialog(
        onDismissRequest = { if (!isRunning) onDismiss() },
        modifier = Modifier.fillMaxSize(0.95f).background(theme.uiBg),
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🤖 ", color = theme.uiAccent, fontSize = 20.sp)
                    Text(
                        "Agent Mode",
                        color = theme.uiText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (isRunning) {
                        Text(
                            if (isPaused) "PAUSED" else "RUNNING",
                            color = if (isPaused) Color(0xFFFFAB00) else Color(0xFF4CAF50),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                if (isRunning && lastGoal.isNotBlank()) {
                    Text(
                        "Goal: ${lastGoal.take(80)}${if (lastGoal.length > 80) "…" else ""}",
                        color = theme.uiTextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (isRunning && !iterationHint.isNullOrBlank()) {
                    Text(
                        iterationHint,
                        color = theme.uiAccent,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        color = theme.uiAccent,
                        trackColor = theme.background
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxSize()) {
                if (!isRunning) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            value = goalValue,
                            onValueChange = { goalValue = it },
                            label = {
                                Text(
                                    "Goal — ketik / tempel tugas",
                                    color = theme.uiTextMuted,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                            },
                            placeholder = {
                                Text(
                                    "Mis: Buat CLI Python factorial, compile dan test",
                                    color = theme.uiTextMuted,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 88.dp)
                                .focusRequester(goalFocus),
                            minLines = 3,
                            maxLines = 8,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = theme.uiText,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = theme.uiAccent,
                                unfocusedBorderColor = theme.uiSurface,
                                cursorColor = theme.uiAccent
                            )
                        )
                        AiPasteButton(theme = theme) { pasted ->
                            goalValue = insertInto(goalValue, pasted)
                            try { goalFocus.requestFocus() } catch (_: Exception) {}
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
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
                        if (useUbuntu) "Ubuntu via proot — apt/gcc/python tersedia"
                        else "Shell Android lokal — tanpa package manager",
                        color = theme.uiTextMuted,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                if (!isRunning && !pendingClarification.isNullOrBlank()) {
                    Surface(
                        color = Color(0x33FFAB00),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                "Agent bertanya:",
                                color = Color(0xFFFFAB00),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                pendingClarification,
                                color = theme.uiText,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            value = clarifyValue,
                            onValueChange = { clarifyValue = it },
                            label = {
                                Text(
                                    "Jawaban Anda (bisa tempel 📋)",
                                    color = theme.uiTextMuted,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                            minLines = 2,
                            maxLines = 5,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = theme.uiText,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = theme.uiAccent,
                                unfocusedBorderColor = theme.uiSurface,
                                cursorColor = theme.uiAccent
                            )
                        )
                        AiPasteButton(theme = theme) { pasted ->
                            clarifyValue = insertInto(clarifyValue, pasted)
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    TextButton(
                        onClick = {
                            val a = clarifyValue.text.trim()
                            if (a.isNotBlank()) {
                                onAnswerClarification?.invoke(a)
                                clarifyValue = TextFieldValue("")
                            }
                        },
                        enabled = clarifyValue.text.isNotBlank() && onAnswerClarification != null
                    ) {
                        Text(
                            "▶ Lanjut dengan jawaban",
                            color = if (clarifyValue.text.isNotBlank()) theme.uiAccent else theme.uiTextMuted,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(theme.background, RoundedCornerShape(4.dp))
                ) {
                    if (events.isEmpty()) {
                        Text(
                            "Belum ada aktivitas.\n\n" +
                                "1. Tulis goal\n" +
                                "2. Pilih environment (Ubuntu disarankan)\n" +
                                "3. Ketuk Start\n" +
                                "4. AI bekerja otonom (file, command, fix)\n" +
                                "5. Stop kapan saja\n\n" +
                                "Agent berhenti jika: selesai, 40 iterasi, stuck, atau butuh approval.",
                            color = theme.uiTextMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(12.dp).verticalScroll(emptyScroll)
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(events.size) { idx ->
                                AgentEventItem(events[idx], theme)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isRunning) {
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
                    /* Wave-17: Allow hiding dialog while agent keeps running. */
                    TextButton(onClick = onDismiss) {
                        Text("Sembunyikan", color = theme.uiTextMuted, fontFamily = FontFamily.Monospace)
                    }
                } else {
                    TextButton(
                        onClick = { onStart(goalValue.text.trim(), useUbuntu) },
                        enabled = goalValue.text.isNotBlank()
                    ) {
                        Text(
                            "▶ Start",
                            color = if (goalValue.text.isNotBlank()) theme.uiAccent else theme.uiTextMuted,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        },
        dismissButton = {
            if (!isRunning) {
                TextButton(onClick = onDismiss) {
                    Text("Tutup", color = theme.uiTextMuted, fontFamily = FontFamily.Monospace)
                }
            }
        }
    )
}

@Composable
private fun AgentEventItem(event: AgentTaskRunner.AgentEvent, theme: TerminalTheme) {
    var expanded by remember { mutableStateOf(false) }
    val (icon, color, text) = when (event) {
        is AgentTaskRunner.AgentEvent.Status -> Triple("▶", theme.uiTextMuted, event.message)
        is AgentTaskRunner.AgentEvent.ToolResult -> {
            val ic = if (event.success) "✓" else "✗"
            val col = if (event.success) Color(0xFF4CAF50) else Color(0xFFFF5252)
            Triple(ic, col, "${event.tool}: ${event.argsSummary}\n${event.resultSummary}")
        }
        is AgentTaskRunner.AgentEvent.NeedsApproval ->
            Triple("🔐", Color(0xFFFFAB00), "Butuh approval: ${event.reason}\n${event.call.displayText}")
        is AgentTaskRunner.AgentEvent.Done -> Triple("✅", Color(0xFF4CAF50), "SELESAI: ${event.summary}")
        is AgentTaskRunner.AgentEvent.StoppedForSafety -> Triple("⚠️", Color(0xFFFF5252), "Dihentikan: ${event.reason}")
        is AgentTaskRunner.AgentEvent.NeedsClarification ->
            Triple("❓", Color(0xFFFFAB00), "Butuh klarifikasi: ${event.question}")
    }
    val long = text.length > 160
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = theme.uiBg,
        shape = RoundedCornerShape(4.dp)
    ) {
        Row(modifier = Modifier.padding(8.dp)) {
            Text(icon, color = color, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(end = 6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (!expanded && long) text.take(160) + "…" else text,
                    color = theme.uiText,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                if (long) {
                    Text(
                        if (expanded) "Sembunyikan" else "Tampilkan lebih",
                        color = theme.uiAccent,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clickable { expanded = !expanded }
                    )
                }
            }
        }
    }
}
