package com.tunnel.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * DiffView - Render diff antara original dan modified text.
 *
 * Phase 23: Inline diff view (Warp/Cursor style).
 * Saat AI edit file, user lihat diff sebelum apply.
 *
 * Format: unified diff dengan + (added) dan - (removed) lines.
 *
 * Inline diff view — show file changes before applying.
 */
object DiffCalculator {
    /** Compute diff antara original dan modified text.
     * Returns list of DiffLine.
     */
    fun computeDiff(original: String, modified: String): List<DiffLine> {
        val origLines = original.lines()
        val modLines = modified.lines()

        /* Simple line-by-line diff (LCS-based). */
        val m = origLines.size
        val n = modLines.size
        val lcs = Array(m + 1) { IntArray(n + 1) }

        for (i in m - 1 downTo 0) {
            for (j in n - 1 downTo 0) {
                lcs[i][j] = if (origLines[i] == modLines[j]) {
                    lcs[i + 1][j + 1] + 1
                } else {
                    maxOf(lcs[i + 1][j], lcs[i][j + 1])
                }
            }
        }

        val diff = mutableListOf<DiffLine>()
        var i = 0
        var j = 0
        while (i < m && j < n) {
            if (origLines[i] == modLines[j]) {
                diff.add(DiffLine(origLines[i], DiffType.CONTEXT))
                i++; j++
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                diff.add(DiffLine(origLines[i], DiffType.REMOVED))
                i++
            } else {
                diff.add(DiffLine(modLines[j], DiffType.ADDED))
                j++
            }
        }
        while (i < m) {
            diff.add(DiffLine(origLines[i], DiffType.REMOVED))
            i++
        }
        while (j < n) {
            diff.add(DiffLine(modLines[j], DiffType.ADDED))
            j++
        }

        return diff
    }

    enum class DiffType { CONTEXT, ADDED, REMOVED }
    data class DiffLine(val text: String, val type: DiffType)

    /** Check if diff has any changes. */
    fun hasChanges(diff: List<DiffLine>): Boolean {
        return diff.any { it.type == DiffType.ADDED || it.type == DiffType.REMOVED }
    }
}

/**
 * DiffViewDialog - Dialog untuk show diff + apply/reject buttons.
 *
 * Phase 23: Diff dialog for AI file edits.
 */
@Composable
fun DiffViewDialog(
    fileName: String,
    originalContent: String,
    modifiedContent: String,
    theme: TerminalTheme,
    onApply: () -> Unit,
    onReject: () -> Unit
) {
    val diff = remember(originalContent, modifiedContent) {
        DiffCalculator.computeDiff(originalContent, modifiedContent)
    }

    AlertDialog(
        onDismissRequest = onReject,
        modifier = Modifier.fillMaxWidth(0.95f).background(theme.uiBg, RoundedCornerShape(8.dp)),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📝 ", fontSize = 16.sp)
                Text(
                    "Diff: $fileName",
                    color = theme.uiText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
                /* Stats. */
                val added = diff.count { it.type == DiffCalculator.DiffType.ADDED }
                val removed = diff.count { it.type == DiffCalculator.DiffType.REMOVED }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("+$added", color = Color(0xFF4CAF50), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Text("-$removed", color = Color(0xFFFF5252), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    if (!DiffCalculator.hasChanges(diff)) {
                        Text("(no changes)", color = theme.uiTextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }

                /* Diff content. */
                val scrollState = androidx.compose.foundation.rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .background(theme.background, RoundedCornerShape(4.dp))
                        .padding(8.dp)
                        .verticalScroll(scrollState)
                ) {
                    Column {
                        diff.forEach { line ->
                            DiffLineView(line, theme)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = onReject,
                    colors = ButtonDefaults.buttonColors(containerColor = theme.uiSurface)
                ) { Text("Reject", color = theme.uiText, fontSize = 11.sp) }
                Button(
                    onClick = onApply,
                    enabled = DiffCalculator.hasChanges(diff),
                    colors = ButtonDefaults.buttonColors(containerColor = theme.ansi.getOrElse(2) { Color(0xFF4CAF50) })
                ) { Text("Apply", color = Color.White, fontSize = 11.sp) }
            }
        }
    )
}

@Composable
private fun DiffLineView(line: DiffCalculator.DiffLine, theme: TerminalTheme) {
    val (prefix, color, bgColor) = when (line.type) {
        DiffCalculator.DiffType.ADDED -> Triple("+", Color(0xFF4CAF50), Color(0xFF4CAF50).copy(alpha = 0.1f))
        DiffCalculator.DiffType.REMOVED -> Triple("-", Color(0xFFFF5252), Color(0xFFFF5252).copy(alpha = 0.1f))
        DiffCalculator.DiffType.CONTEXT -> Triple(" ", theme.foreground, Color.Transparent)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(
            prefix,
            color = color,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(12.dp)
        )
        Text(
            line.text,
            color = color,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
    }
}
