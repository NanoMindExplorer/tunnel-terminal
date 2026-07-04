package com.tunnel.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * TerminalUI - Semua Composable UI terminal.
 *
 * Phase 17 (Major Bug Fix):
 * - TerminalScreenView: render cursor block di (cursorRow, cursorCol)
 * - ExtraKeysBar: tambah HOME, END, PGUP, PGDN keys
 * - AIChatPanel: pakai Snippet ID (bukan index) untuk delete (anti bug urutan)
 * - Auto-scroll ke bawah saat output baru
 * - Style attributes (bold/italic/underline) dirender
 * - Debounce resize saat zoom untuk hindari ioctl spam
 */

@Composable
fun TabBar(
    tabs: List<Pair<Int, Int>>, activeTabId: Int,
    onTabSelected: (Int) -> Unit, onNewTab: () -> Unit,
    onTabClosed: (Int) -> Unit, onOpenAI: () -> Unit,
    onOpenFileExplorer: () -> Unit = {},
    onOpenWorkspace: () -> Unit = {},
    onOpenSsh: () -> Unit = {},
    onToggleSplit: () -> Unit = {},
    isSplitMode: Boolean = false,
    onOpenPalette: () -> Unit = {},
    onToggleBlockMode: () -> Unit = {},
    isBlockMode: Boolean = false,
    onOpenUbuntu: () -> Unit = {},
    ubuntuInstalled: Boolean = true,
    theme: TerminalTheme = ThemeManager.defaultTheme
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().background(theme.uiBg).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        items(tabs) { tab ->
            val isActive = tab.first == activeTabId
            Row(
                modifier = Modifier.background(if (isActive) theme.uiSurface else theme.uiBg, RoundedCornerShape(4.dp))
                    .clickable { onTabSelected(tab.first) }
                    .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Tab ${tab.second}  ", color = if (isActive) theme.uiText else theme.uiTextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Box(modifier = Modifier.clickable { onTabClosed(tab.first) }.padding(4.dp)) {
                    Text("X", color = Color(0xFFFF5252), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        item {
            Box(modifier = Modifier.background(theme.uiSurface, RoundedCornerShape(4.dp)).clickable { onNewTab() }.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text("+", color = theme.uiText, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            }
        }
        /* Phase 19: File Explorer button. */
        item {
            Box(modifier = Modifier.background(theme.uiSurface, RoundedCornerShape(4.dp)).clickable { onOpenFileExplorer() }.padding(horizontal = 10.dp, vertical = 10.dp)) {
                Text("📁", color = theme.uiText, fontSize = 12.sp)
            }
        }
        /* Phase 19: Workspace Sessions button. */
        item {
            Box(modifier = Modifier.background(theme.uiSurface, RoundedCornerShape(4.dp)).clickable { onOpenWorkspace() }.padding(horizontal = 10.dp, vertical = 10.dp)) {
                Text("💾", color = theme.uiText, fontSize = 12.sp)
            }
        }
        /* Phase 21: SSH Connect button. */
        item {
            Box(modifier = Modifier.background(theme.uiSurface, RoundedCornerShape(4.dp)).clickable { onOpenSsh() }.padding(horizontal = 10.dp, vertical = 10.dp)) {
                Text("🔌", color = theme.uiText, fontSize = 12.sp)
            }
        }
        /* Phase 38 (proot/Ubuntu): Ubuntu (Linux Environment) button.
         * Icon pakai penguin 🐧 + dot indikator install state. */
        item {
            Box(
                modifier = Modifier
                    .background(
                        if (ubuntuInstalled) theme.uiSurface else theme.uiBg,
                        RoundedCornerShape(4.dp)
                    )
                    .clickable { onOpenUbuntu() }
                    .padding(horizontal = 10.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🐧", color = theme.uiText, fontSize = 12.sp)
                    if (!ubuntuInstalled) {
                        Spacer(modifier = Modifier.width(3.dp))
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(theme.uiAccent, CircleShape)
                        )
                    }
                }
            }
        }
        /* Phase 21: Split Pane toggle button. */
        item {
            Box(modifier = Modifier.background(if (isSplitMode) theme.uiAccent else theme.uiSurface, RoundedCornerShape(4.dp)).clickable { onToggleSplit() }.padding(horizontal = 10.dp, vertical = 10.dp)) {
                Text("⬡", color = theme.uiText, fontSize = 12.sp)
            }
        }
        /* Phase 22: Block mode toggle (Warp-style block terminal). */
        item {
            Box(modifier = Modifier.background(if (isBlockMode) theme.uiAccent else theme.uiSurface, RoundedCornerShape(4.dp)).clickable { onToggleBlockMode() }.padding(horizontal = 10.dp, vertical = 10.dp)) {
                Text("⊞", color = theme.uiText, fontSize = 12.sp)
            }
        }
        /* Phase 22: Command palette (Ctrl+K). */
        item {
            Box(modifier = Modifier.background(theme.uiSurface, RoundedCornerShape(4.dp)).clickable { onOpenPalette() }.padding(horizontal = 10.dp, vertical = 10.dp)) {
                Text("⌘K", color = theme.uiAccent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
        item {
            Box(modifier = Modifier.background(theme.uiAccent, RoundedCornerShape(4.dp)).clickable { onOpenAI() }.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text("AI", color = theme.uiText, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun ExtraKeysBar(
    isCtrlActive: Boolean,
    isAltActive: Boolean,
    onKeyPressed: (String) -> Unit
) {
    /* Dua baris: simbol + kontrol. Tambah HOME, END, PGUP, PGDN. */
    /* Phase 34 (A4): Tambah "PASTE" key untuk paste dari clipboard. */
    val controlKeys = listOf("ESC", "TAB", "CTRL", "ALT", "↑", "↓", "←", "→", "HOME", "END", "PGUP", "PGDN", "BKSP", "DEL", "PASTE")
    val symbolKeys = listOf("~", "*", "$", "\"", "'", ";", "&", "|", "-", "/", "(", ")", "<", ">", "=", "{", "}", "[", "]", "#", "!", "?", "\\", "@", "`")

    Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF2B2B2B))) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(horizontal = 4.dp)
        ) {
            items(symbolKeys) { key ->
                Box(
                    modifier = Modifier.background(Color(0xFF2A2A2A), RoundedCornerShape(4.dp)).clickable { onKeyPressed(key) }.padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) { Text(key, color = Color(0xFF00BCD4), fontSize = 14.sp, fontFamily = FontFamily.Monospace) }
            }
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(horizontal = 4.dp)
        ) {
            items(controlKeys) { key ->
                val bgColor = when {
                    (key == "CTRL" && isCtrlActive) -> Color(0xFF6200EE)
                    (key == "ALT" && isAltActive) -> Color(0xFF6200EE)
                    else -> Color(0xFF3A3A3A)
                }
                Box(
                    modifier = Modifier.background(bgColor, RoundedCornerShape(4.dp)).clickable { onKeyPressed(key) }.padding(horizontal = 10.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) { Text(key, color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}

@Composable
fun TerminalScreenView(
    emulator: TerminalEmulator,
    screenDirty: Int,
    isAlive: Boolean,
    onRestartSession: () -> Unit,
    onResize: (rows: Int, cols: Int, fontSize: Float) -> Unit,
    theme: TerminalTheme = ThemeManager.defaultTheme,
    /* Phase 19.5: Tap-to-focus + mouse scroll support. */
    onTap: () -> Unit = {},
    onScroll: (Float) -> Unit = {},
    /* Phase 24: External fontSize control (untuk persist + split pane sync). */
    fontSizeState: Float = 12f,
    onFontSizeChange: (Float) -> Unit = {}
) {
    /* Phase 24: fontSize dari external state (persist antar recompose + tab switch). */
    val fontSize = fontSizeState
    var lastResizeTime by remember { mutableStateOf(0L) }
    val scrollState = rememberScrollState()

    /* BUG-05 fix: Simpan ukuran Box terakhir untuk dipakai saat pinch-zoom.
     * BUG-06 fix: onResize dipanggil langsung di LaunchedEffect(fontSize), bukan
     * mengandalkan onSizeChanged yang tidak terpicu saat hanya fontSize berubah. */
    var lastSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current

    /* Auto-scroll ke bawah saat output baru.
     * Phase 32: Juga scroll saat cursor berubah (user mengetik) agar tetap terlihat. */
    LaunchedEffect(screenDirty) {
        if (scrollState.maxValue > 0) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }
    /* Phase 32: Auto-scroll saat cursor bergerak (user mengetik dengan keyboard fisik). */
    val cursorState = remember(screenDirty) { emulator.getCursorState() }
    LaunchedEffect(cursorState.row) {
        if (scrollState.maxValue > 0) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    /* BUG-05+06 fix: Panggil onResize LANGSUNG saat fontSize berubah.
     * Konversi sp→px dengan density yang benar. */
    LaunchedEffect(fontSize) {
        if (lastSize.width > 0 && lastSize.height > 0) {
            /* BUG-05 fix: Gunakan density untuk konversi sp→px. */
            val charWidthPx = with(density) { (fontSize.sp.toPx() * 0.6f) }
            val charHeightPx = with(density) { (fontSize.sp.toPx() * 1.2f) }
            if (charWidthPx > 0 && charHeightPx > 0) {
                val newCols = (lastSize.width / charWidthPx).toInt().coerceAtLeast(20)
                val newRows = (lastSize.height / charHeightPx).toInt().coerceAtLeast(10)
                onResize(newRows, newCols, fontSize)
            }
        }
    }

    /* Phase 35 (A3): Text selection state — declared BEFORE Box modifier chain. */
    var selectionStart by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var selectionEnd by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var isSelecting by remember { mutableStateOf(false) }
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    /* Snapshot + dims juga before Box (used in pointerInput modifiers). */
    val screenSnapshot = remember(screenDirty) { emulator.getScreenSnapshot() }
    val renderRows = emulator.snapshotRows()
    val renderCols = emulator.snapshotCols()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.background)
            .onSizeChanged { size ->
                lastSize = size
                val now = System.currentTimeMillis()
                if (now - lastResizeTime < 100) return@onSizeChanged
                lastResizeTime = now

                /* BUG-05 fix: Gunakan density untuk konversi sp→px. */
                val charWidthPx = with(density) { (fontSize.sp.toPx() * 0.6f) }
                val charHeightPx = with(density) { (fontSize.sp.toPx() * 1.2f) }
                if (charWidthPx > 0 && charHeightPx > 0 && size.width > 0 && size.height > 0) {
                    val newCols = (size.width / charWidthPx).toInt().coerceAtLeast(20)
                    val newRows = (size.height / charHeightPx).toInt().coerceAtLeast(10)
                    onResize(newRows, newCols, fontSize)
                }
            }
            /* Phase 40 fix (A3): Unified gesture handler — tap + long-press + drag
             * dalam SATU pointerInput block. Old code pakai 3 pointerInput terpisah
             * (detectTapGestures + detectDragGestures + detectTransformGestures) yang
             * saling block gesture detection → long-press tidak start selection, drag
             * tidak extend selection.
             *
             * FIX: awaitEachGesture handle seluruh gesture lifecycle dalam satu detector:
             * 1. awaitFirstDown → catat posisi + waktu
             * 2. Loop awaitPointerEvent → cek duration (long-press) + distance (drag)
             * 3. Up event → jika selecting, copy ke clipboard; jika tap, focus
             *
             * Pinch-zoom tetap di pointerInput terpisah (detectTransformGestures)
             * karena pinch butuh 2 pointer — tidak conflict dengan single-pointer gestures. */
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val downTime = System.currentTimeMillis()
                    val downPos = down.position
                    var isLongPress = false
                    var selectionStarted = false
                    val touchSlop = with(density) { 8.dp.toPx() }
                    val longPressTimeout = 500L

                    /* Helper: convert pixel position → (row, col) */
                    fun posToCell(pos: androidx.compose.ui.geometry.Offset): Pair<Int, Int> {
                        val col = (pos.x / (fontSize * 0.6f * density.density)).toInt().coerceIn(0, renderCols - 1)
                        val row = (pos.y / (fontSize * 1.2f * density.density)).toInt().coerceIn(0, renderRows - 1)
                        return Pair(row, col)
                    }

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull() ?: break

                        val duration = System.currentTimeMillis() - downTime
                        val distance = (change.position - downPos).getDistance()

                        when {
                            /* Finger lifted → gesture selesai */
                            !change.pressed -> {
                                if (selectionStarted) {
                                    /* Selection complete → copy to clipboard */
                                    val text = getSelectedText(screenSnapshot, selectionStart, selectionEnd, renderCols)
                                    if (text.isNotEmpty()) {
                                        clipboardManager.setText(AnnotatedString(text))
                                    }
                                    isSelecting = false
                                } else if (!isLongPress && distance < touchSlop) {
                                    /* Tap → focus keyboard */
                                    onTap()
                                }
                                change.consume()
                                break
                            }
                            /* Long-press detected → start selection */
                            !selectionStarted && !isLongPress && duration > longPressTimeout && distance < touchSlop -> {
                                isLongPress = true
                                selectionStarted = true
                                isSelecting = true
                                val (row, col) = posToCell(downPos)
                                selectionStart = Pair(row, col)
                                selectionEnd = Pair(row, col)
                                change.consume()
                            }
                            /* Drag while selecting → extend selection */
                            selectionStarted && distance > touchSlop -> {
                                val (row, col) = posToCell(change.position)
                                selectionEnd = Pair(row, col)
                                change.consume()
                            }
                        }
                    }
                }
            }
            /* Phase 24: Pinch-to-zoom — pakai external state via onFontSizeChange.
             * Old code: fontSize local, tidak persist, tidak trigger resize.
             * Fix: onFontSizeChange(newFont) → parent update state → re-render + resize. */
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    val newFont = (fontSize * zoom).coerceIn(8f, 24f)
                    if (newFont != fontSize) {
                        onFontSizeChange(newFont)
                        lastResizeTime = 0L  /* Force onSizeChanged to re-trigger onResize */
                    }
                }
            }
    ) {
        /* Phase 35: Selection state + snapshot + dims already declared before Box. */

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
                .verticalScroll(scrollState)
        ) {
            for (row in 0 until renderRows) {
                val annotatedString = buildAnnotatedString {
                    for (col in 0 until renderCols) {
                        val cell = screenSnapshot.getOrElse(row) { arrayOf() }.getOrElse(col) { TerminalCell() }
                        val isCursor = cursorState.visible && row == cursorState.row && col == cursorState.col

                        /* Phase 35 (A3): Selection highlight. */
                        val isInSelection = isSelecting && selectionStart != null && selectionEnd != null &&
                            isCellInSelection(row, col, selectionStart!!, selectionEnd!!)

                        /* Phase 20: Fix cursor double-render. */
                        if (isInSelection) {
                            /* Selected cell: highlight background. */
                            withStyle(SpanStyle(
                                background = theme.uiAccent.copy(alpha = 0.4f),
                                color = theme.uiText,
                                fontFamily = FontFamily.Monospace
                            )) { append(cell.char) }
                        } else if (isCursor) {
                            withStyle(SpanStyle(
                                background = theme.cursor,
                                color = theme.background,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (cell.bold) FontWeight.Bold else FontWeight.Normal,
                                fontStyle = if (cell.italic) FontStyle.Italic else FontStyle.Normal
                            )) { append(cell.char) }
                        } else {
                            val bgColor = if (cell.reverse) cell.fgColor else cell.bgColor
                            val fgColor = if (cell.reverse) cell.bgColor else cell.fgColor
                            withStyle(SpanStyle(
                                color = fgColor,
                                background = bgColor,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (cell.bold) FontWeight.Bold else FontWeight.Normal,
                                fontStyle = if (cell.italic) FontStyle.Italic else FontStyle.Normal,
                                textDecoration = if (cell.underline) androidx.compose.ui.text.style.TextDecoration.Underline else androidx.compose.ui.text.style.TextDecoration.None
                            )) { append(cell.char) }
                        }
                    }
                }
                Text(
                    text = annotatedString,
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSize.sp,
                    softWrap = false,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (!isAlive) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)).clickable { onRestartSession() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Session Exited.\nTap anywhere to restart.",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp
                )
            }
        }
    }
}

/* Phase 35 (A3): Helper functions untuk text selection. */

/** Check apakah cell (row, col) berada dalam selection range. */
private fun isCellInSelection(row: Int, col: Int, start: Pair<Int, Int>, end: Pair<Int, Int>): Boolean {
    /* Normalize: start harus selalu <= end. */
    val (startRow, startCol) = if (start.first < end.first || (start.first == end.first && start.second <= end.second)) start else end
    val (endRow, endCol) = if (start.first < end.first || (start.first == end.first && start.second <= end.second)) end else start
    return when {
        row < startRow || row > endRow -> false
        row == startRow && row == endRow -> col in startCol..endCol
        row == startRow -> col >= startCol
        row == endRow -> col <= endCol
        else -> true
    }
}

/** Extract text dari selection range di screen snapshot. */
private fun getSelectedText(
    screen: Array<Array<TerminalCell>>,
    start: Pair<Int, Int>?,
    end: Pair<Int, Int>?,
    cols: Int
): String {
    if (start == null || end == null) return ""
    val sb = StringBuilder()
    val (startRow, startCol) = if (start.first < end.first || (start.first == end.first && start.second <= end.second)) start else end
    val (endRow, endCol) = if (start.first < end.first || (start.first == end.first && start.second <= end.second)) end else start
    for (row in startRow..endRow) {
        if (row >= screen.size) break
        val rowStart = if (row == startRow) startCol else 0
        val rowEnd = if (row == endRow) endCol else cols - 1
        for (col in rowStart..rowEnd) {
            if (col < screen[row].size) {
                sb.append(screen[row][col].char)
            }
        }
        if (row < endRow) sb.append('\n')
    }
    return sb.toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIChatPanel(
    messages: List<ChatMessage>,
    settings: AISettings,
    snippets: List<Snippet>,
    theme: TerminalTheme,
    themes: List<TerminalTheme>,
    isProcessingAI: Boolean,
    onSettingsChanged: (AISettings) -> Unit,
    onSendPrompt: (String) -> Unit,
    onRunCommand: (String) -> Unit,
    onRunAutoPilot: (List<String>) -> Unit,
    onSaveSnippet: (String, String) -> Unit,
    onRunSnippet: (String) -> Unit,
    onDeleteSnippet: (Long) -> Unit,
    onThemeChanged: (TerminalTheme) -> Unit,
    onClearChat: () -> Unit,
    onClose: () -> Unit,
    /* Phase 19: Image Vision. */
    pendingImages: List<String> = emptyList(),
    onAttachImage: () -> Unit = {},
    onRemoveImage: (Int) -> Unit = {},
    /* Phase 19: Model fetcher. */
    availableModels: List<ModelInfo> = emptyList(),
    isLoadingModels: Boolean = false,
    modelsFetchError: String? = null,
    onFetchModels: () -> Unit = {},
    onSelectModel: (ModelInfo) -> Unit = {}
) {
    var inputText by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(0) }
    /* Settings sub-tab: 0=AI Provider, 1=Theme, 2=About. */
    var settingsSubTab by remember { mutableStateOf(0) }
    val scrollState = rememberScrollState()
    var expandedProvider by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf<String?>(null) }
    var snippetTitle by remember { mutableStateOf("") }
    var settingsDraft by remember { mutableStateOf(settings) }
    var showSaved by remember { mutableStateOf(false) }
    /* Streaming cursor blink state. */
    var cursorBlink by remember { mutableStateOf(true) }

    /* Sync settingsDraft ketika settings prop berubah. */
    LaunchedEffect(settings) {
        settingsDraft = settings
    }

    /* Debounce save settings. */
    LaunchedEffect(settingsDraft) {
        if (settingsDraft != settings) {
            kotlinx.coroutines.delay(800)
            onSettingsChanged(settingsDraft)
            showSaved = true
            kotlinx.coroutines.delay(1500)
            showSaved = false
        }
    }

    /* Auto-scroll ke bawah saat ada message baru atau streaming update.
     * Auto-scroll to bottom on new messages or streaming updates. */
    LaunchedEffect(messages.size, messages.lastOrNull()?.content, messages.lastOrNull()?.isStreaming) {
        if (scrollState.maxValue > 0) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    /* Cursor blink animation saat streaming aktif. */
    LaunchedEffect(isProcessingAI) {
        while (isProcessingAI) {
            cursorBlink = !cursorBlink
            kotlinx.coroutines.delay(500)
        }
    }

    if (showSaveDialog != null) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = null },
            title = { Text("Simpan ke Workflow") },
            text = {
                Column {
                    Text("Perintah: ${showSaveDialog}")
                    OutlinedTextField(
                        value = snippetTitle,
                        onValueChange = { snippetTitle = it },
                        label = { Text("Nama Workflow") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (snippetTitle.isNotEmpty()) onSaveSnippet(snippetTitle, showSaveDialog!!)
                    snippetTitle = ""
                    showSaveDialog = null
                }) { Text("Simpan") }
            },
            dismissButton = { Button(onClick = { showSaveDialog = null }) { Text("Batal") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(theme.uiBg)) {
        /* Header dengan title + clear chat + close. */
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Tunnel Auto-Pilot", color = theme.uiText, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
                Text(
                    if (isProcessingAI) "● Streaming..." else "${messages.size} pesan",
                    color = if (isProcessingAI) theme.uiAccent else theme.uiTextMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                /* Clear chat button. */
                Button(
                    onClick = { onClearChat() },
                    enabled = messages.isNotEmpty() && !isProcessingAI,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = theme.uiSurface,
                        disabledContainerColor = theme.uiSurface.copy(alpha = 0.5f)
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("🗑", color = theme.uiText, fontSize = 14.sp)
                }
                Button(
                    onClick = onClose,
                    colors = ButtonDefaults.buttonColors(containerColor = theme.uiSurface)
                ) {
                    Text("X", color = theme.uiText)
                }
            }
        }
        /* Tab selector. */
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Chat", "Workflows", "Settings").forEachIndexed { idx, label ->
                Button(
                    onClick = { selectedTab = idx },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectedTab == idx) theme.uiAccent else theme.uiSurface
                    )
                ) {
                    Text(label, color = theme.uiText)
                }
            }
        }

        if (selectedTab == 0) {
            /* ─── Chat Tab ─── */
            Column(modifier = Modifier.weight(1f).padding(16.dp).verticalScroll(scrollState)) {
                if (messages.isEmpty()) {
                    Text(
                        "Selamat datang di Tunnel Auto-Pilot!\n\n" +
                        "Ketik permintaan Anda, contoh:\n" +
                        "• \"Tampilkan 5 proses termahal\"\n" +
                        "• \"Setup server Python http di port 8080\"\n" +
                        "• \"Cari file .log ukuran > 10MB\"\n\n" +
                        "AI akan streaming response token-by-token dan ingat seluruh percakapan.",
                        color = theme.uiTextMuted,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                messages.forEach { msg ->
                    val nameColor = when {
                        msg.isError -> Color(0xFFFF5252)
                        msg.role == "user" -> theme.ansi.getOrElse(2) { Color(0xFF4CAF50) }
                        else -> theme.uiAccent
                    }
                    val displayName = if (msg.role == "user") "Anda" else "AI"
                    val suffix = if (msg.isStreaming) " (streaming...)" else ""
                    Text(
                        "$displayName:$suffix",
                        color = nameColor,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    /* Content - tambahkan cursor blink jika streaming.
                     * Phase 22: AI messages render as markdown (headers, code blocks, lists). */
                    val displayContent = if (msg.isStreaming && cursorBlink) {
                        msg.content + "▋"
                    } else if (msg.isStreaming) {
                        msg.content + " "
                    } else {
                        msg.content
                    }
                    if (msg.role == "assistant" && !msg.isStreaming && !msg.isError) {
                        /* Phase 22: Markdown rendering untuk AI responses. */
                        MarkdownText(
                            markdown = displayContent,
                            theme = theme,
                            fontSize = 13,
                            modifier = Modifier.padding(bottom = 8.dp).fillMaxWidth()
                        )
                    } else {
                        Text(
                            displayContent,
                            color = if (msg.isError) Color(0xFFFF8A80) else theme.uiText,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    /* Command buttons. */
                    if (msg.commands.size > 1 && !msg.isStreaming) {
                        Text(
                            "🚀 Rangkaian Auto-Pilot (${msg.commands.size} langkah):",
                            color = theme.ansi.getOrElse(3) { Color(0xFFFFEB3B) },
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        msg.commands.forEachIndexed { i, c ->
                            Text(
                                "  ${i + 1}. $c",
                                color = theme.ansi.getOrElse(6) { Color(0xFF00BCD4) },
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Row(modifier = Modifier.padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onRunAutoPilot(msg.commands) },
                                enabled = !isProcessingAI,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = theme.ansi.getOrElse(6) { Color(0xFF00BCD4) }
                                )
                            ) { Text("Run Auto-Pilot") }
                        }
                    } else if (msg.isCommand && !msg.isStreaming) {
                        Row(modifier = Modifier.padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            /* Untuk single command: pakai commands[0] (lebih akurat dari msg.content
                             * yang mungkin berisi explanation + command). */
                            val cmdToRun = msg.commands.firstOrNull() ?: msg.content
                            Button(
                                onClick = { onRunCommand(cmdToRun) },
                                enabled = !isProcessingAI,
                                colors = ButtonDefaults.buttonColors(containerColor = theme.uiAccent)
                            ) { Text("▶ Run") }
                            Button(
                                onClick = { showSaveDialog = cmdToRun },
                                colors = ButtonDefaults.buttonColors(containerColor = theme.uiSurface)
                            ) { Text("💾 Save") }
                        }
                    }
                }
            }
            /* Pending images preview (Phase 19). */
            if (pendingImages.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    pendingImages.forEachIndexed { idx, _ ->
                        Box(
                            modifier = Modifier
                                .background(theme.uiSurface, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("🖼", fontSize = 14.sp)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("img${idx + 1}", color = theme.uiText, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                Box(
                                    modifier = Modifier
                                        .padding(start = 4.dp)
                                        .clickable { onRemoveImage(idx) }
                                ) {
                                    Text("X", color = Color(0xFFFF5252), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
                Text(
                    "ℹ ${pendingImages.size} gambar akan dikirim dengan pesan ini. " +
                    if (settings.supportsVision) "Model vision OK." else "Pilih model vision di Settings (gpt-4o/gemini/claude-3).",
                    color = if (settings.supportsVision) theme.ansi.getOrElse(2) { Color(0xFF4CAF50) } else Color(0xFFFFC107),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            /* Input bar. */
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                /* Phase 19: Image attach button. */
                Button(
                    onClick = onAttachImage,
                    enabled = !isProcessingAI,
                    colors = ButtonDefaults.buttonColors(containerColor = theme.uiSurface),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text("📎", fontSize = 14.sp)
                }
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    enabled = !isProcessingAI,
                    textStyle = TextStyle(color = theme.uiText, fontFamily = FontFamily.Monospace),
                    placeholder = {
                        Text(
                            if (isProcessingAI) "AI sedang merespons..." else "Minta AI menyelesaikan tugas...",
                            color = theme.uiTextMuted
                        )
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        /* Phase 19: allow send with just images (no text) if vision model. */
                        if ((inputText.isNotEmpty() || pendingImages.isNotEmpty()) && !isProcessingAI) {
                            onSendPrompt(inputText)
                            inputText = ""
                        }
                    },
                    enabled = (inputText.isNotEmpty() || pendingImages.isNotEmpty()) && !isProcessingAI,
                    colors = ButtonDefaults.buttonColors(containerColor = theme.uiAccent)
                ) {
                    Text(if (isProcessingAI) "..." else "Kirim")
                }
            }
        } else if (selectedTab == 1) {
            /* ─── Workflows Tab ─── */
            Column(modifier = Modifier.weight(1f).padding(16.dp).verticalScroll(scrollState)) {
                if (snippets.isEmpty()) {
                    Text(
                        "Belum ada workflow tersimpan.\n\nKlik '💾 Save' di pesan AI bercommand untuk menyimpan.",
                        color = theme.uiTextMuted,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                } else {
                    snippets.forEach { snippet ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            colors = CardDefaults.cardColors(containerColor = theme.uiSurface)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    snippet.title,
                                    color = theme.ansi.getOrElse(2) { Color(0xFF4CAF50) },
                                    fontSize = 14.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    snippet.command,
                                    color = theme.uiText,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                                Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { onRunSnippet(snippet.command) },
                                        colors = ButtonDefaults.buttonColors(containerColor = theme.uiAccent)
                                    ) { Text("▶ Run") }
                                    Button(
                                        onClick = { onDeleteSnippet(snippet.id) },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
                                    ) { Text("Hapus") }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            /* ─── Settings Tab ─── */
            /* Sub-tab selector: AI Provider / Theme / About. */
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("AI", "Theme", "About").forEachIndexed { idx, label ->
                    Button(
                        onClick = { settingsSubTab = idx },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (settingsSubTab == idx) theme.uiAccent else theme.uiSurface
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(label, color = theme.uiText, fontSize = 12.sp)
                    }
                }
            }
            when (settingsSubTab) {
                0 -> {
                    /* AI Provider settings. */
                    Column(modifier = Modifier.weight(1f).padding(16.dp).verticalScroll(scrollState)) {
                        Text("Provider:", color = theme.uiTextMuted, fontSize = 12.sp)
                        /* Phase 33 (A5 fix): Ganti OutlinedTextField dengan Row+Text non-interactive.
                         * Old code: OutlinedTextField enabled=true/readOnly=true → field interaktif
                         * berebut event dengan Box.clickable → mouse click tidak sampai.
                         * Fix: Hanya satu lapis penerima klik (Box), tidak ada TextField. */
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, theme.uiTextMuted, RoundedCornerShape(4.dp))
                                .clickable { expandedProvider = true }
                                .padding(horizontal = 12.dp, vertical = 14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(settingsDraft.providerName, color = theme.uiText, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                Text("▼", color = theme.uiText)
                            }
                            DropdownMenu(expanded = expandedProvider, onDismissRequest = { expandedProvider = false }) {
                                AIProviders.presets.forEach { preset ->
                                    DropdownMenuItem(
                                        text = { Text(preset.providerName) },
                                        onClick = {
                                            settingsDraft = preset.copy(apiKey = settingsDraft.apiKey)
                                            expandedProvider = false
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Base URL:", color = theme.uiTextMuted, fontSize = 12.sp)
                        OutlinedTextField(
                            value = settingsDraft.baseUrl,
                            onValueChange = { settingsDraft = settingsDraft.copy(baseUrl = it) },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(color = theme.uiText, fontFamily = FontFamily.Monospace)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Model Name:", color = theme.uiTextMuted, fontSize = 12.sp)
                        OutlinedTextField(
                            value = settingsDraft.modelName,
                            onValueChange = { settingsDraft = settingsDraft.copy(modelName = it) },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(color = theme.uiText, fontFamily = FontFamily.Monospace)
                        )
                        /* Phase 19: Fetch Models button + dropdown. */
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onFetchModels,
                                enabled = !isLoadingModels && settingsDraft.baseUrl.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = theme.uiAccent),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    if (isLoadingModels) "Loading..." else "🔄 Fetch Models",
                                    color = theme.uiText,
                                    fontSize = 11.sp
                                )
                            }
                            /* Vision capability indicator. */
                            if (settingsDraft.supportsVision) {
                                Text(
                                    "👁 Vision",
                                    color = theme.ansi.getOrElse(2) { Color(0xFF4CAF50) },
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                        /* Error message. */
                        if (modelsFetchError != null) {
                            Text(
                                "⚠ $modelsFetchError",
                                color = Color(0xFFFF8A80),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        /* Model list dropdown. */
                        if (availableModels.isNotEmpty()) {
                            Text(
                                "${availableModels.size} model tersedia (tap untuk pilih):",
                                color = theme.uiTextMuted,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                            /* Scrollable list of models (max 5 visible). */
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(top = 4.dp)
                            ) {
                                availableModels.forEach { model ->
                                    val isCurrent = model.id == settingsDraft.modelName
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                if (isCurrent) theme.uiAccent.copy(alpha = 0.3f) else theme.uiSurface,
                                                RoundedCornerShape(4.dp)
                                            )
                                            .clickable { onSelectModel(model) }
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            model.id,
                                            color = if (isCurrent) theme.uiAccent else theme.uiText,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (model.supportsVision) {
                                            Text("👁", fontSize = 10.sp)
                                        }
                                        Text(
                                            " ${model.ownedBy}",
                                            color = theme.uiTextMuted,
                                            fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("API Key:", color = theme.uiTextMuted, fontSize = 12.sp)
                        OutlinedTextField(
                            value = settingsDraft.apiKey,
                            onValueChange = { settingsDraft = settingsDraft.copy(apiKey = it) },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(color = theme.uiText, fontFamily = FontFamily.Monospace),
                            placeholder = { Text("sk-...", color = theme.uiTextMuted) }
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Timeout (ms):", color = theme.uiTextMuted, fontSize = 12.sp)
                        OutlinedTextField(
                            value = settingsDraft.requestTimeoutMs.toString(),
                            onValueChange = { v -> v.toIntOrNull()?.let { settingsDraft = settingsDraft.copy(requestTimeoutMs = it.coerceIn(5000, 120000)) } },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(color = theme.uiText, fontFamily = FontFamily.Monospace)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Temperature:", color = theme.uiTextMuted, fontSize = 12.sp)
                        OutlinedTextField(
                            value = settingsDraft.temperature.toString(),
                            onValueChange = { v -> v.toDoubleOrNull()?.let { settingsDraft = settingsDraft.copy(temperature = it.coerceIn(0.0, 2.0)) } },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(color = theme.uiText, fontFamily = FontFamily.Monospace)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (showSaved) {
                            Text("✓ Saved", color = theme.ansi.getOrElse(2) { Color(0xFF4CAF50) }, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                1 -> {
                    /* Theme picker. */
                    Column(modifier = Modifier.weight(1f).padding(16.dp).verticalScroll(scrollState)) {
                        Text(
                            "Pilih tema terminal:",
                            color = theme.uiText,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        themes.forEach { t ->
                            val isActive = t.name == theme.name
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                                    .clickable { onThemeChanged(t) },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isActive) theme.uiAccent else theme.uiSurface
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    /* Color preview swatches - tampilkan 8 warna pertama dari palette. */
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        t.ansi.take(8).forEach { c ->
                                            Box(
                                                modifier = Modifier
                                                    .size(16.dp)
                                                    .background(c)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            t.name,
                                            color = theme.uiText,
                                            fontSize = 14.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            "BG: #${Integer.toHexString(t.background.toArgb()).substring(2).uppercase()}  " +
                                            "FG: #${Integer.toHexString(t.foreground.toArgb()).substring(2).uppercase()}",
                                            color = theme.uiTextMuted,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    if (isActive) {
                                        Text("✓", color = theme.uiText, fontSize = 18.sp)
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Catatan: tema diterapkan ke sel baru di terminal. " +
                            "Untuk refresh penuh, ketik 'clear' di terminal.\n\n" +
                            "Tema juga diterapkan ke UI drawer ini secara live.",
                            color = theme.uiTextMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                2 -> {
                    /* About tab. */
                    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                    val context = androidx.compose.ui.platform.LocalContext.current
                    Column(modifier = Modifier.weight(1f).padding(16.dp).verticalScroll(scrollState)) {
                        Text(
                            "Tunnel Terminal v5.4.0",
                            color = theme.uiText,
                            fontSize = 16.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            "AI-Native Terminal for Android",
                            color = theme.uiTextMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        /* Creator Credit. */
                        Text(
                            "─ Creator ────────────────────────────────────",
                            color = theme.ansi.getOrElse(6) { Color(0xFF00BCD4) },
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            "NanoMind",
                            color = theme.ansi.getOrElse(2) { Color(0xFF4CAF50) },
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "github.com/NanoMindExplorer",
                            color = theme.uiAccent,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        /* Crypto addresses — tap to copy. */
                        Text(
                            "─ Support Creator (Crypto) ───────────────────",
                            color = theme.ansi.getOrElse(3) { Color(0xFFFFC107) },
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        /* Bitcoin. */
                        CryptoAddressRow("Bitcoin", "TDzaGUA7YgQEaB1RfnBgWWn9QzJ8QFCVmt", theme, clipboard, context)
                        Spacer(modifier = Modifier.height(6.dp))
                        /* EVM. */
                        CryptoAddressRow("EVM", "0x96e49c673252bb0a2253418417cf1db000fec6ef", theme, clipboard, context)
                        Spacer(modifier = Modifier.height(6.dp))
                        /* Solana. */
                        CryptoAddressRow("Solana", "4B4wprDDz3pnd6EUumwAKf4LNzRHK5pH4qbustsLcLuR", theme, clipboard, context)
                        Spacer(modifier = Modifier.height(6.dp))
                        /* Tron. */
                        CryptoAddressRow("Tron", "TDzaGUA7YgQEaB1RfnBgWWn9QzJ8QFCVmt", theme, clipboard, context)

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "─ Open Source ────────────────────────────────",
                            color = theme.ansi.getOrElse(6) { Color(0xFF00BCD4) },
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            "github.com/NanoMindExplorer/tunnel-terminal",
                            color = theme.uiAccent,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

/**
 * Phase 35: CryptoAddressRow — baris alamat crypto dengan label + address + tap to copy.
 */
@Composable
fun CryptoAddressRow(
    label: String,
    address: String,
    theme: TerminalTheme,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    context: android.content.Context
) {
    var copied by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.uiSurface, RoundedCornerShape(4.dp))
            .clickable {
                clipboard.setText(AnnotatedString(address))
                copied = true
                android.widget.Toast.makeText(context, "$label address copied!", android.widget.Toast.LENGTH_SHORT).show()
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$label: ",
            color = theme.ansi.getOrElse(3) { Color(0xFFFFC107) },
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        Text(
            address,
            color = theme.uiText,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
        Text(
            if (copied) "✓" else "📋",
            color = if (copied) Color(0xFF4CAF50) else theme.uiTextMuted,
            fontSize = 12.sp
        )
    }
}

/**
 * Phase 39 (proot/Ubuntu): Dialog untuk install / uninstall Linux environment.
 *
 * State yang ditampilkan:
 *  - Idle (belum install): tampilkan info ukuran + tombol Install + tombol Uninstall (disabled).
 *  - Installing: tampilkan progress bar + stage label.
 *  - Error: tampilkan pesan error + tombol Retry.
 *  - Installed: tampilkan info size + tombol Open + tombol Uninstall.
 *
 * Catatan UI:
 *  - Pakai AlertDialog Material3 supaya konsisten dengan dialog lain di app.
 *  - Tombol Uninstall disabled saat installing supaya user tidak merusak state.
 *  - Tombol dismiss (Cancel) disabled saat installing (harus tunggu selesai atau kill app).
 */
@Composable
fun UbuntuInstallDialog(
    theme: TerminalTheme,
    bootstrap: ProotBootstrap,
    installing: Boolean,
    stage: String,
    percent: Int,
    error: String?,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onDismiss: () -> Unit
) {
    val installed = bootstrap.isInstalled
    val freeMb = remember(installing, installed, error) { bootstrap.getFreeSpaceMb() }
    val rootfsMb = remember(installed) {
        if (installed) bootstrap.getRootfsSizeMb() else 0
    }

    AlertDialog(
        onDismissRequest = { if (!installing) onDismiss() },
        modifier = Modifier.background(theme.uiBg),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🐧 ", color = theme.uiAccent, fontSize = 20.sp)
                Text(
                    "Ubuntu (Linux Environment)",
                    color = theme.uiText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Jalankan Ubuntu asli di dalam Tunnel Terminal lewat proot — tanpa root. " +
                    "Memungkinkan apt, git, python, nodejs, dan tool Linux lainnya.",
                    color = theme.uiTextMuted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(12.dp))

                /* Info storage. */
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Free storage:", color = theme.uiTextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("$freeMb MB", color = if (freeMb < 1500) Color(0xFFFF5252) else theme.uiText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                if (installed) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Rootfs size:", color = theme.uiTextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text("$rootfsMb MB", color = theme.uiText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Status:", color = theme.uiTextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text("✓ Installed", color = Color(0xFF4CAF50), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Required:", color = theme.uiTextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text("≥ 1500 MB", color = theme.uiText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Status:", color = theme.uiTextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text("Not installed", color = Color(0xFFFFAB00), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }

                /* Progress UI saat installing. */
                if (installing) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stage.ifEmpty { "Memulai..." },
                        color = theme.uiAccent,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = (percent / 100f).coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth(),
                        color = theme.uiAccent,
                        trackColor = theme.uiSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "$percent%",
                        color = theme.uiTextMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                /* Error display. */
                if (error != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0x33FF5252),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                "❌ Install gagal:",
                                color = Color(0xFFFF5252),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                error,
                                color = Color(0xFFFFAB00),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Catatan: Fitur ini download+mengeksekusi binary native saat runtime → tidak kompatibel dengan kebijakan Play Store. Distribusikan via GitHub Releases.",
                    color = theme.uiTextMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (installed && !installing) {
                    TextButton(onClick = onDismiss) {
                        Text("Open", color = theme.uiAccent, fontFamily = FontFamily.Monospace)
                    }
                } else if (!installing && error == null) {
                    TextButton(onClick = onInstall, enabled = !installed) {
                        Text(if (installed) "Installed" else "Install", color = if (installed) theme.uiTextMuted else theme.uiAccent, fontFamily = FontFamily.Monospace)
                    }
                } else if (!installing && error != null) {
                    TextButton(onClick = onInstall) {
                        Text("Retry", color = theme.uiAccent, fontFamily = FontFamily.Monospace)
                    }
                }
                if (installed && !installing) {
                    TextButton(onClick = onUninstall) {
                        Text("Uninstall", color = Color(0xFFFF5252), fontFamily = FontFamily.Monospace)
                    }
                }
            }
        },
        dismissButton = {
            if (!installing) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = theme.uiTextMuted, fontFamily = FontFamily.Monospace)
                }
            }
        }
    )
}
