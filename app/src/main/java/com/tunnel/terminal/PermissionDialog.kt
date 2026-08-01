package com.tunnel.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * PermissionDialog - Dialog untuk ask user permission saat AI call destructive tool.
 *
 * Phase 22: Permission prompt UI (like Claude Code).
 *
 * v9.2.0 fix (H-1c): Extracted from AiToolCall.kt untuk modularitas.
 */
@Composable
fun PermissionDialog(
    call: AiToolCall,
    theme: TerminalTheme,
    onAllow: () -> Unit,
    onAlwaysAllow: () -> Unit,
    onDeny: () -> Unit,
    /* Wave-7: Optional Never allow → ALWAYS_DENY for this session. */
    onNeverAllow: (() -> Unit)? = null
) {
    /* BUG-01 fix: Sembunyikan "Always Allow" untuk run_command/delete_file. */
    val canAlwaysAllow = call.tool !in setOf("run_command", "delete_file")
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDeny,
        modifier = Modifier.background(theme.uiBg, RoundedCornerShape(8.dp)),
        title = {
            Text(
                "🔐 AI Permission Request",
                color = theme.ansi.getOrElse(3) { Color(0xFFFFC107) },
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp
            )
        },
        text = {
            Column {
                Text(
                    "AI wants to execute:",
                    color = theme.uiTextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(8.dp))
                /* BUG-04 fix: Tampilkan argumen penuh dengan scroll, bukan dipotong 50 char. */
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .background(theme.uiSurface, RoundedCornerShape(4.dp))
                        .padding(8.dp)
                        .verticalScroll(scrollState)
                ) {
                    Text(
                        call.displayTextFull,
                        color = theme.ansi.getOrElse(6) { Color(0xFF00BCD4) },
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (call.reasoning.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "AI reasoning: ${call.reasoning}",
                        color = theme.uiTextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    if (call.isDestructive) "⚠ This tool can modify your system."
                    else "ℹ This is a read-only tool.",
                    color = if (call.isDestructive) Color(0xFFFF8A80) else theme.uiTextMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        onClick = onAllow,
                        colors = ButtonDefaults.buttonColors(containerColor = theme.ansi.getOrElse(2) { Color(0xFF4CAF50) })
                    ) { Text("Allow once", color = Color.White, fontSize = 11.sp) }
                    /* BUG-01 fix: Hanya tampilkan "Always allow" untuk tool yang aman. */
                    if (canAlwaysAllow) {
                        Button(
                            onClick = onAlwaysAllow,
                            colors = ButtonDefaults.buttonColors(containerColor = theme.uiAccent)
                        ) { Text("Always allow", color = Color.White, fontSize = 11.sp) }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDeny) {
                        Text("Deny once", color = Color(0xFFFF5252), fontSize = 11.sp)
                    }
                    /* Wave-7: Never allow works for all destructive tools including run_command. */
                    if (onNeverAllow != null) {
                        TextButton(onClick = onNeverAllow) {
                            Text("Never allow", color = Color(0xFFFF8A80), fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    )
}
