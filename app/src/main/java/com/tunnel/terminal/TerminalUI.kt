package com.tunnel.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.awaitPointerEvent
import androidx.compose.ui.input.pointer.awaitPointerEventScope
import androidx.compose.ui.input.pointer.pointerInput
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
    val controlKeys = listOf("ESC", "TAB", "CTRL", "ALT", "↑", "↓", "←", "→", "HOME", "END", "PGUP", "PGDN", "BKSP", "DEL")
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
    onScroll: (Float) -> Unit = {}
) {
    var fontSize by remember { mutableStateOf(12f) }
    var lastResizeTime by remember { mutableStateOf(0L) }
    val scrollState = rememberScrollState()

    /* Auto-scroll ke bawah saat output baru. Auto-scroll to bottom on new output. */
    LaunchedEffect(screenDirty) {
        if (scrollState.maxValue > 0) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.background)
            .onSizeChanged { size ->
                /* Debounce resize: skip jika < 100ms sejak resize terakhir.
                 * Debounce: skip if < 100ms since last resize. */
                val now = System.currentTimeMillis()
                if (now - lastResizeTime < 100) return@onSizeChanged
                lastResizeTime = now

                val charWidthPx = (fontSize * 0.6).roundToInt()
                val charHeightPx = (fontSize * 1.2).roundToInt()
                if (charWidthPx > 0 && charHeightPx > 0 && size.width > 0 && size.height > 0) {
                    val newCols = (size.width / charWidthPx).coerceAtLeast(20)
                    val newRows = (size.height / charHeightPx).coerceAtLeast(10)
                    onResize(newRows, newCols, fontSize)
                }
            }
            /* Phase 19.5: Tap-to-focus (untuk show soft keyboard). */
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { _ -> onTap() }
                )
            }
            /* Phase 19.5: Mouse scroll wheel support. */
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val scrollDelta = event.changes
                            .filter { it.scrollDelta.y != 0.0f }
                            .sumOf { it.scrollDelta.y.toDouble() }
                            .toFloat()
                        if (scrollDelta != 0f) {
                            onScroll(scrollDelta)
                            /* Scroll internal state (mouse wheel = 20px per notch). */
                            scrollState.dispatchRawDelta(scrollDelta * 20)
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
            /* Phase 19.5: Pinch-to-zoom (tetap dipertahankan). */
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    val newFont = (fontSize * zoom).coerceIn(8f, 24f)
                    if (newFont != fontSize) {
                        fontSize = newFont
                        lastResizeTime = System.currentTimeMillis()
                    }
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
                .verticalScroll(scrollState)
        ) {
            for (row in 0 until emulator.rows) {
                val annotatedString = buildAnnotatedString {
                    for (col in 0 until emulator.cols) {
                        val cell = emulator.getScreen()[row][col]
                        val bgColor = if (cell.reverse) cell.fgColor else cell.bgColor
                        val fgColor = if (cell.reverse) cell.bgColor else cell.fgColor

                        val style = SpanStyle(
                            color = fgColor,
                            background = bgColor,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (cell.bold) FontWeight.Bold else FontWeight.Normal,
                            fontStyle = if (cell.italic) FontStyle.Italic else FontStyle.Normal,
                            textDecoration = if (cell.underline) androidx.compose.ui.text.style.TextDecoration.Underline else androidx.compose.ui.text.style.TextDecoration.None
                        )
                        withStyle(style) { append(cell.char) }

                        /* Render cursor block pada posisi cursor. Cursor block. */
                        if (emulator.isCursorVisible && row == emulator.cursorRow && col == emulator.cursorCol) {
                            withStyle(SpanStyle(background = Color.White, color = Color.Black)) {
                                append(cell.char)
                            }
                        }
                    }
                }
                Text(
                    text = annotatedString,
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSize.sp,
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
                    /* Content - tambahkan cursor blink jika streaming. */
                    val displayContent = if (msg.isStreaming && cursorBlink) {
                        msg.content + "▋"
                    } else if (msg.isStreaming) {
                        msg.content + " "
                    } else {
                        msg.content
                    }
                    Text(
                        displayContent,
                        color = if (msg.isError) Color(0xFFFF8A80) else theme.uiText,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
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
                        Box {
                            OutlinedTextField(
                                value = settingsDraft.providerName,
                                onValueChange = {},
                                readOnly = true,
                                modifier = Modifier.fillMaxWidth().clickable { expandedProvider = true },
                                textStyle = TextStyle(color = theme.uiText, fontFamily = FontFamily.Monospace),
                                trailingIcon = { Text("▼", color = theme.uiText) }
                            )
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
                    Column(modifier = Modifier.weight(1f).padding(16.dp).verticalScroll(scrollState)) {
                        Text(
                            "Tunnel Terminal v3.2.0",
                            color = theme.uiText,
                            fontSize = 16.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            "Phase 19: Free AI Provider + Image Vision + File Explorer + Workspace Sessions",
                            color = theme.uiTextMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Fitur Phase 19:",
                            color = theme.ansi.getOrElse(6) { Color(0xFF00BCD4) },
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            "• 13 AI Provider presets + Custom (bebas masukin provider apapun)\n" +
                            "• Fetch Models dari /models endpoint (semua model tersedia)\n" +
                            "• AI Image Vision (gpt-4o, gemini-1.5, claude-3, llama-3.2-vision)\n" +
                            "• File Explorer Drawer (browse tanpa cd)\n" +
                            "• Workspace Sessions (save/restore tab sets)\n" +
                            "• Launcher icon redesign (terminal + AI nodes)\n" +
                            "• Vision capability detection per model\n" +
                            "• Image auto-compress (max 1024px, JPEG 85)\n\n" +
                            "Fitur Phase 18 (masih aktif):\n" +
                            "• AI Streaming SSE - response token-by-token\n" +
                            "• Multi-turn conversation memory (max 20 pesan)\n" +
                            "• 6 theme presets: Matrix, Dracula, Solarized, Monokai, Nord, Tokyo Night\n" +
                            "• Theme-aware UI (drawer, buttons, text)\n" +
                            "• Streaming cursor indicator (▋ blink)\n" +
                            "• Auto-scroll selama streaming",
                            color = theme.uiText,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Open source:",
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
