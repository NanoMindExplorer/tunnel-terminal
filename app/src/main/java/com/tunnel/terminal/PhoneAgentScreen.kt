package com.tunnel.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * v9.5.2 Phase 3: PhoneAgentScreen — Compose UI for Agent Mode (phone automation).
 *
 * This is a SEPARATE screen from the existing AgentScreen (which is for terminal AI agent).
 * PhoneAgentScreen is specifically for the AccessibilityService-based phone UI automation.
 *
 * Features:
 * - Goal input (natural language: "Open WhatsApp and send message to Budi")
 * - Live step log (action + reasoning + success/fail)
 * - Pause/Resume/Stop controls
 * - Privacy disclosure dialog (first launch)
 * - Accessibility service enable prompt
 * - Task history summary (success rate)
 * - Skill list viewer
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneAgentScreen(
    theme: TerminalTheme,
    executor: AgentActionExecutor?,
    skillMemoryStore: SkillMemoryStore?,
    taskHistoryLogger: TaskHistoryLogger?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // State
    var goalText by remember { mutableStateOf(TextFieldValue("")) }
    var isRunning by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    val logEntries = remember { mutableStateListOf<Pair<String, Color>>() }
    val listState = rememberLazyListState()

    // Privacy disclosure
    val prefs = remember { context.getSharedPreferences("TunnelAgent", android.content.Context.MODE_PRIVATE) }
    var showPrivacyDialog by remember { mutableStateOf(!prefs.getBoolean("privacy_acknowledged", false)) }

    // Accessibility service status
    var a11yEnabled by remember { mutableStateOf(AgentAccessibilityService.isRunning()) }
    LaunchedEffect(Unit) {
        while (true) {
            a11yEnabled = AgentAccessibilityService.isRunning()
            kotlinx.coroutines.delay(3000)
        }
    }

    // Collect events from executor
    LaunchedEffect(executor) {
        executor?.events?.collect { event ->
            if (event != null) {
                when (event) {
                    is AgentActionExecutor.AgentEvent.Step -> {
                        logEntries.add(0, "📋 Step ${event.stepNum}/${event.totalSteps}: ${event.action} — ${event.reasoning}" to theme.uiText)
                    }
                    is AgentActionExecutor.AgentEvent.ScreenRead -> {
                        logEntries.add(0, "👁 Screen read (${event.content.length} chars)" to theme.uiTextMuted)
                    }
                    is AgentActionExecutor.AgentEvent.ActionResult -> {
                        val color = if (event.success) Color(0xFF4CAF50) else Color(0xFFFF5252)
                        val icon = if (event.success) "✅" else "❌"
                        logEntries.add(0, "$icon ${event.action}: ${event.details}" to color)
                    }
                    is AgentActionExecutor.AgentEvent.Recovery -> {
                        logEntries.add(0, "🔧 Recovery: ${event.description}" to Color(0xFFFFC107))
                    }
                    is AgentActionExecutor.AgentEvent.SkillReplay -> {
                        logEntries.add(0, "🔁 Skill replay: ${event.skillName} (${event.step}/${event.total})" to Color(0xFF2196F3))
                    }
                    is AgentActionExecutor.AgentEvent.Complete -> {
                        val color = if (event.success) Color(0xFF4CAF50) else Color(0xFFFF5252)
                        val icon = if (event.success) "🎉" else "⚠"
                        logEntries.add(0, "$icon ${event.summary}" to color)
                        isRunning = false
                        isPaused = false
                    }
                    is AgentActionExecutor.AgentEvent.Error -> {
                        logEntries.add(0, "❌ ${event.message}" to Color(0xFFFF5252))
                        isRunning = false
                        isPaused = false
                    }
                }
                // Auto-scroll to top (newest)
                listState.animateScrollToItem(0)
            }
        }
    }

    // Auto-scroll when new entries added
    LaunchedEffect(logEntries.size) {
        if (logEntries.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    AlertDialog(
        onDismissRequest = {
            executor?.cancel()
            onDismiss()
        },
        modifier = Modifier.fillMaxSize(0.95f).background(theme.uiBg, RoundedCornerShape(8.dp)),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SmartToy, contentDescription = null, tint = theme.uiAccent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("🤖 Phone Agent", color = theme.uiAccent, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxSize().padding(vertical = 4.dp)) {
                // Accessibility service status
                if (!a11yEnabled) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0x33FF5252),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                "⚠ Accessibility Service not enabled",
                                color = Color(0xFFFF5252),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Phone Agent needs Accessibility Service to read screens and perform actions.",
                                color = theme.uiTextMuted,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(Modifier.height(6.dp))
                            TextButton(onClick = {
                                context.startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                            }) {
                                Text("Open Settings", color = theme.uiAccent, fontSize = 11.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // Goal input
                OutlinedTextField(
                    value = goalText,
                    onValueChange = { goalText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g. Open WhatsApp and send 'Hello' to Budi", fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = theme.uiText,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    enabled = !isRunning,
                    singleLine = false,
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (goalText.text.isNotBlank() && !isRunning && a11yEnabled) {
                            startTask(scope, executor, goalText.text, logEntries, isRunning = { isRunning = it }, isPaused = { isPaused = it })
                        }
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = theme.uiAccent,
                        unfocusedBorderColor = theme.uiSurface,
                        cursorColor = theme.uiAccent
                    )
                )

                Spacer(Modifier.height(8.dp))

                // Control buttons
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isRunning) {
                        Button(
                            onClick = {
                                if (goalText.text.isNotBlank() && a11yEnabled) {
                                    startTask(scope, executor, goalText.text, logEntries, isRunning = { isRunning = it }, isPaused = { isPaused = it })
                                }
                            },
                            enabled = goalText.text.isNotBlank() && a11yEnabled,
                            colors = ButtonDefaults.buttonColors(containerColor = theme.uiAccent),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Start", color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                    } else {
                        if (!isPaused) {
                            Button(
                                onClick = { executor?.pause(); isPaused = true },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFC107)),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Pause", color = Color.Black, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            }
                        } else {
                            Button(
                                onClick = { executor?.resume(); isPaused = false },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Resume", color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                        Button(
                            onClick = {
                                executor?.cancel()
                                isRunning = false
                                isPaused = false
                                logEntries.add(0, "⏹ Task cancelled by user" to Color(0xFFFF5252))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Stop", color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Task history summary
                taskHistoryLogger?.let { logger ->
                    val analytics = remember { logger.getAnalytics() }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Tasks: ${analytics["total"] ?: 0} | Success: ${analytics["success"] ?: 0} | Failed: ${analytics["failed"] ?: 0}",
                            color = theme.uiTextMuted,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        val rate = analytics["successRate"] ?: 0
                        Text(
                            "Rate: $rate%",
                            color = if (rate >= 70) Color(0xFF4CAF50) else if (rate >= 40) Color(0xFFFFC107) else Color(0xFFFF5252),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                // Divider
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(theme.uiSurface))

                Spacer(Modifier.height(6.dp))

                // Live log
                if (logEntries.isEmpty()) {
                    Text(
                        "No tasks yet. Enter a goal above and tap Start.",
                        color = theme.uiTextMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        reverseLayout = true
                    ) {
                        items(logEntries) { (text, color) ->
                            Text(
                                text,
                                color = color,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                executor?.cancel()
                onDismiss()
            }) {
                Text("Close", color = theme.uiAccent, fontFamily = FontFamily.Monospace)
            }
        }
    )

    // Privacy disclosure dialog
    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { /* cannot dismiss without accepting */ },
            modifier = Modifier.background(theme.uiBg, RoundedCornerShape(8.dp)),
            title = {
                Text("🔒 Privacy Disclosure", color = theme.uiAccent, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            },
            text = {
                Column {
                    Text(
                        "Phone Agent Mode reads the contents of your screen and sends a text description to your AI provider (${"your configured LLM"}).\n\n" +
                        "⚠ Do NOT use it on screens containing:\n" +
                        "• Banking or financial apps\n" +
                        "• Password fields or password managers\n" +
                        "• Private messages or emails\n" +
                        "• Personal photos or medical info\n\n" +
                        "The AI receives a TEXT DUMP of UI elements (not screenshots). " +
                        "Screen content is sent only when you explicitly start a task.",
                        color = theme.uiText,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        prefs.edit().putBoolean("privacy_acknowledged", true).apply()
                        showPrivacyDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = theme.uiAccent)
                ) {
                    Text("I Understand", color = Color.White, fontSize = 12.sp)
                }
            }
        )
    }
}

/** Start a phone agent task. */
private fun startTask(
    scope: kotlinx.coroutines.CoroutineScope,
    executor: AgentActionExecutor?,
    goal: String,
    logEntries: MutableList<Pair<String, Color>>,
    isRunning: (Boolean) -> Unit,
    isPaused: (Boolean) -> Unit
) {
    if (executor == null) return
    logEntries.clear()
    logEntries.add(0, "🚀 Starting: $goal" to Color(0xFF2196F3))
    isRunning(true)
    isPaused(false)
    scope.launch {
        try {
            val result = executor.executeTask(goal)
            logEntries.add(0, "📋 Result: $result" to Color(0xFF2196F3))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logEntries.add(0, "❌ ${e.message ?: e.javaClass.simpleName}" to Color(0xFFFF5252))
        } finally {
            isRunning(false)
            isPaused(false)
        }
    }
}
