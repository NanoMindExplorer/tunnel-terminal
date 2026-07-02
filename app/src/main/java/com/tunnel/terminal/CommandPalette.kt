package com.tunnel.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * PaletteItem - Satu item di command palette.
 * Phase 22: AI command palette (Ctrl+K / Cmd+K) — like VS Code / Warp.
 */
data class PaletteItem(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val icon: ImageVector = Icons.Default.PlayArrow,
    val category: PaletteCategory,
    val action: () -> Unit
)

enum class PaletteCategory { COMMAND, AI, NAVIGATION, SETTING, RECENT }

/**
 * CommandPalette - Modal overlay untuk quick access ke semua fitur.
 *
 * Phase 22: Command palette revolution (Ctrl+K).
 * Terinspirasi VS Code Command Palette + Warp.
 *
 * Features:
 * - Fuzzy search across commands, AI actions, settings, recent
 * - Keyboard navigation (Up/Down/Enter)
 * - Categories with icons
 * - Recent commands auto-suggested
 *
 * Usage:
 * - Ctrl+K (physical keyboard) atau button untuk open
 * - Type to search
 * - Up/Down untuk navigate
 * - Enter untuk execute
 * - Esc untuk close
 */
@Composable
fun CommandPalette(
    theme: TerminalTheme,
    items: List<PaletteItem>,
    recentCommands: List<String> = emptyList(),
    onExecute: (PaletteItem) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selectedIndex by remember { mutableStateOf(0) }
    /* Phase 26 hotfix: Use rememberCoroutineScope for suspend calls. */
    val paletteScope = rememberCoroutineScope()

    /* Build full item list dengan recent commands. */
    val allItems = remember(items, recentCommands) {
        val recents = recentCommands.take(5).mapIndexed { idx, cmd ->
            PaletteItem(
                id = "recent_$idx",
                title = cmd,
                subtitle = "recent command",
                icon = Icons.Default.History,
                category = PaletteCategory.RECENT,
                action = { /* action akan di-handle via onExecute callback */ }
            )
        }
        recents + items
    }

    /* Filter by query (fuzzy: substring match case-insensitive). */
    val filtered = remember(query, allItems) {
        if (query.isBlank()) allItems
        else {
            val q = query.lowercase()
            allItems.filter { item ->
                item.title.lowercase().contains(q) ||
                item.subtitle.lowercase().contains(q) ||
                item.category.name.lowercase().contains(q)
            }
        }
    }

    /* Reset selected index saat filter change. */
    LaunchedEffect(filtered.size) {
        selectedIndex = 0
    }

    /* Phase 26: Keyboard navigation — Up/Down/Enter via onPreviewKeyEvent. */
    val listState = rememberLazyListState()

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.95f).background(theme.uiBg, RoundedCornerShape(12.dp)),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = theme.uiAccent,
                    modifier = Modifier.size(20.dp).padding(end = 8.dp)
                )
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyUp) {
                            when (event.key) {
                                Key.DirectionDown -> {
                                    if (filtered.isNotEmpty()) {
                                        selectedIndex = (selectedIndex + 1) % filtered.size
                                        paletteScope.launch {
                                            listState.scrollToItem(selectedIndex)
                                        }
                                    }
                                    true
                                }
                                Key.DirectionUp -> {
                                    if (filtered.isNotEmpty()) {
                                        selectedIndex = if (selectedIndex == 0) filtered.size - 1 else selectedIndex - 1
                                        paletteScope.launch {
                                            listState.scrollToItem(selectedIndex)
                                        }
                                    }
                                    true
                                }
                                Key.Enter -> {
                                    if (filtered.isNotEmpty() && selectedIndex < filtered.size) {
                                        onExecute(filtered[selectedIndex])
                                        onDismiss()
                                    }
                                    true
                                }
                                Key.Escape -> {
                                    onDismiss()
                                    true
                                }
                                else -> false
                            }
                        } else false
                    },
                    textStyle = TextStyle(
                        color = theme.uiText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(theme.uiAccent),
                    singleLine = true,
                    decorationBox = { innerTextField ->
                        if (query.isEmpty()) {
                            Text(
                                "Type command or search... (Ctrl+K)",
                                color = theme.uiTextMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp
                            )
                        }
                        innerTextField()
                    }
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
            ) {
                if (filtered.isEmpty()) {
                    Text(
                        "No results for \"$query\"",
                        color = theme.uiTextMuted,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(filtered) { item ->
                            val isSelected = filtered[selectedIndex.coerceAtMost(filtered.lastIndex)] == item
                            PaletteItemRow(
                                item = item,
                                theme = theme,
                                isSelected = isSelected,
                                onClick = {
                                    onExecute(item)
                                    onDismiss()
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Esc to close", color = theme.uiTextMuted, fontSize = 10.sp)
            }
        }
    )
}

@Composable
private fun PaletteItemRow(
    item: PaletteItem,
    theme: TerminalTheme,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (isSelected) theme.uiAccent.copy(alpha = 0.2f) else Color.Transparent
    val iconColor = when (item.category) {
        PaletteCategory.COMMAND -> theme.ansi.getOrElse(6) { Color(0xFF00BCD4) }
        PaletteCategory.AI -> theme.ansi.getOrElse(5) { Color(0xFFE040FB) }
        PaletteCategory.NAVIGATION -> theme.ansi.getOrElse(4) { Color(0xFF2196F3) }
        PaletteCategory.SETTING -> theme.ansi.getOrElse(3) { Color(0xFFFFC107) }
        PaletteCategory.RECENT -> theme.ansi.getOrElse(8) { Color(0xFF757575) }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            item.icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(18.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.title,
                color = if (isSelected) theme.uiAccent else theme.uiText,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
            if (item.subtitle.isNotBlank()) {
                Text(
                    item.subtitle,
                    color = theme.uiTextMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        /* Category badge. */
        Text(
            item.category.name.lowercase(),
            color = theme.uiTextMuted,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.background(theme.uiSurface, RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
