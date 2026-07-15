package com.tunnel.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
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

/**
 * Wave-10: Tab entry with custom label (id, display index, label).
 */
data class TabUiItem(val id: Int, val index: Int, val label: String)

@Composable
fun TabBar(
    tabs: List<TabUiItem>, activeTabId: Int,
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
    /* Phase 41 fix (CRIT-04): Flag untuk sembunyikan tombol Ubuntu di playstore flavor. */
    ubuntuEnabled: Boolean = true,
    /* Wave-10: Long-press tab to rename. */
    onTabRename: (Int) -> Unit = {},
    theme: TerminalTheme = ThemeManager.defaultTheme
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().background(theme.uiBg).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        items(tabs, key = { it.id }) { tab ->
            val isActive = tab.id == activeTabId
            Row(
                modifier = Modifier.background(if (isActive) theme.uiSurface else theme.uiBg, RoundedCornerShape(4.dp))
                    .pointerInput(tab.id) {
                        detectTapGestures(
                            onTap = { onTabSelected(tab.id) },
                            onLongPress = { onTabRename(tab.id) }
                        )
                    }
                    .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    tab.label.take(16),
                    color = if (isActive) theme.uiText else theme.uiTextMuted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.width(6.dp))
                Box(modifier = Modifier.clickable { onTabClosed(tab.id) }.padding(4.dp)) {
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
         * Icon pakai penguin 🐧 + dot indikator install state.
         * Phase 41 fix (CRIT-04): Disembunyikan di playstore flavor (ubuntuEnabled=false). */
        if (ubuntuEnabled) {
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
    onKeyPressed: (String) -> Unit,
    /* Wave-7: Theme-aware colors (was hardcoded dark grays). */
    theme: TerminalTheme = ThemeManager.defaultTheme,
    /* Wave-15: Compact mode hides symbols + F-row to free vertical space. */
    expanded: Boolean = false,
    onToggleExpanded: () -> Unit = {}
) {
    /* Dua/tiga baris: simbol + kontrol + one-shot Ctrl / F-keys (Wave-12). */
    /* Phase 34 (A4): Tambah "PASTE" key untuk paste dari clipboard. */
    val controlKeys = listOf(
        "ESC", "TAB", "CTRL", "ALT", "↑", "↓", "←", "→",
        "HOME", "END", "PGUP", "PGDN", "BKSP", "DEL", "PASTE",
        /* Wave-16: Discrete font zoom (pinch still works on the screen). */
        "A−", "A+"
    )
    /* Wave-12/14: One-shot control + F1–F12 + readline chips for mobile TUI. */
    val quickCtrlKeys = listOf(
        "^C", "^D", "^Z", "^L", "^U", "^W", "^A", "^E",
        "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12"
    )
    val symbolKeys = listOf("~", "*", "$", "\"", "'", ";", "&", "|", "-", "/", "(", ")", "<", ">", "=", "{", "}", "[", "]", "#", "!", "?", "\\", "@", "`")
    /* Wave-13: Keys that repeat while held (arrows, backspace, page). */
    val repeatableKeys = setOf("↑", "↓", "←", "→", "BKSP", "DEL", "PGUP", "PGDN")

    val barBg = theme.uiBg
    val keyBg = theme.uiSurface
    val accent = theme.uiAccent
    val symbolColor = theme.ansi.getOrElse(6) { Color(0xFF00BCD4) }
    val textColor = theme.uiText
    val ctrlChipColor = Color(0xFFFFAB00)

    Column(modifier = Modifier.fillMaxWidth().background(barBg)) {
        /* Wave-15: Always show essential control row + expand toggle. */
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(horizontal = 4.dp)
        ) {
            items(controlKeys) { key ->
                val bgColor = when {
                    (key == "CTRL" && isCtrlActive) -> accent
                    (key == "ALT" && isAltActive) -> accent
                    else -> keyBg
                }
                ExtraKeyChip(
                    label = key,
                    color = textColor,
                    bg = bgColor,
                    onPress = { onKeyPressed(key) },
                    repeat = key in repeatableKeys,
                    compact = false
                )
            }
            /* Wave-15/build-fix: use items(1) — singular lazy.item is not a top-level import. */
            items(1) {
                ExtraKeyChip(
                    label = if (expanded) "▾" else "▴",
                    color = accent,
                    bg = keyBg,
                    onPress = onToggleExpanded,
                    repeat = false,
                    compact = false
                )
            }
        }

        /* Compact: only essential ^C/^D/^Z + PASTE path already above. */
        if (!expanded) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                items(listOf("^C", "^D", "^Z", "^L", "^U", "^W", "^A", "^E")) { key ->
                    ExtraKeyChip(
                        label = key,
                        color = ctrlChipColor,
                        bg = accent.copy(alpha = 0.35f),
                        onPress = { onKeyPressed(key) },
                        repeat = false
                    )
                }
            }
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                items(symbolKeys) { key ->
                    ExtraKeyChip(
                        label = key,
                        color = symbolColor,
                        bg = keyBg,
                        onPress = { onKeyPressed(key) },
                        repeat = false
                    )
                }
            }
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                items(quickCtrlKeys) { key ->
                    val isCtrlChip = key.startsWith("^")
                    ExtraKeyChip(
                        label = key,
                        color = if (isCtrlChip) ctrlChipColor else textColor,
                        bg = if (isCtrlChip) accent.copy(alpha = 0.35f) else keyBg,
                        onPress = { onKeyPressed(key) },
                        repeat = false
                    )
                }
            }
        }
    }
}

/**
 * Wave-13: Extra key chip with optional long-press key-repeat (Termux-style).
 */
@Composable
private fun ExtraKeyChip(
    label: String,
    color: Color,
    bg: Color,
    onPress: () -> Unit,
    repeat: Boolean,
    compact: Boolean = true
) {
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(4.dp))
            .pointerInput(label, repeat) {
                if (!repeat) {
                    detectTapGestures(onTap = { onPress() })
                } else {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        onPress()
                        val job = scope.launch {
                            kotlinx.coroutines.delay(400)
                            while (true) {
                                onPress()
                                kotlinx.coroutines.delay(50)
                            }
                        }
                        try {
                            waitForUpOrCancellation()
                        } finally {
                            job.cancel()
                        }
                    }
                }
            }
            .padding(
                horizontal = if (compact) 10.dp else 10.dp,
                vertical = if (compact) 6.dp else 8.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = color, fontSize = if (compact) 14.sp else 11.sp, fontFamily = FontFamily.Monospace)
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
    onFontSizeChange: (Float) -> Unit = {},
    /* Phase 53: Paste callback for floating toolbar. */
    onPasteRequested: () -> Unit = {},
    /* Wave-14: Open URL from selection (http/https). */
    onOpenUrl: (String) -> Unit = {},
    /* Wave-14: Dead-session overlay label override. */
    deadSessionMessage: String = "Session exited.\nTap anywhere to restart\n(history preserved)."
) {
    /* Phase 24: fontSize dari external state (persist antar recompose + tab switch). */
    val fontSize = fontSizeState
    /**
     * Wave-16: Gesture-local font size. Pinch multiplies THIS every frame so zoom
     * is not stuck on a stale composition capture (pointerInput(Unit) bug).
     * Synced from parent whenever external fontSizeState changes (buttons / palette).
     */
    var gestureFontSp by remember { mutableFloatStateOf(fontSize) }
    LaunchedEffect(fontSize) {
        if (abs(gestureFontSp - fontSize) > 0.01f) {
            gestureFontSp = fontSize
        }
    }
    var lastResizeTime by remember { mutableStateOf(0L) }
    /* Wave-15: LazyListState for virtualized scrollback (only compose visible rows). */
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    /* BUG-05 fix: Simpan ukuran Box terakhir untuk dipakai saat pinch-zoom.
     * BUG-06 fix: onResize dipanggil langsung di LaunchedEffect(fontSize), bukan
     * mengandalkan onSizeChanged yang tidak terpicu saat hanya fontSize berubah. */
    var lastSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current

    /* Phase 35 (A3): Text selection state — declared BEFORE Box modifier chain
     * and before auto-scroll LaunchedEffect (uses isSelecting). */
    var selectionStart by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var selectionEnd by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var isSelecting by remember { mutableStateOf(false) }

    /* Wave-7 + Wave-15: Auto-scroll when near bottom (LazyColumn). */
    var showJumpToBottom by remember { mutableStateOf(false) }

    /* BUG-05+06 + Wave-18/20: Resize PTY with shared metrics (pad + line box + bottom margin). */
    fun emitResize(fontSp: Float) {
        if (lastSize.width <= 0 || lastSize.height <= 0) return
        val grid = TerminalLayoutMetrics.computeGrid(
            widthPx = lastSize.width,
            heightPx = lastSize.height,
            fontSp = fontSp,
            density = density
        )
        onResize(grid.rows, grid.cols, fontSp)
    }
    /* Wave-20: Follow gesture-local size during pinch, not only committed parent state. */
    LaunchedEffect(gestureFontSp, lastSize.width, lastSize.height) {
        emitResize(gestureFontSp)
    }
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    /* Phase 53: Text toolbar for COPY/PASTE floating menu. */
    val textToolbar = androidx.compose.ui.platform.LocalTextToolbar.current
    val context = androidx.compose.ui.platform.LocalContext.current
    /* Phase 48 fix (F-1): Atomic render state — screen+cursor+rows+cols dalam satu snapshot.
     * OLD BUG: 4 pemanggilan terpisah → renderRows/renderCols bisa beda dari screenSnapshot
     * saat resize → "layar menghilang/bergeser".
     * FIX: Satu panggilan getRenderState() dalam satu synchronized block. */
    val renderState = remember(screenDirty) { emulator.getRenderState() }
    val screenSnapshot = renderState.screen
    val cursorState = renderState.cursor
    val renderRows = renderState.rows
    val renderCols = renderState.cols
    /* Wave-1: Actually render scrollback history above the live screen.
     * getScrollbackLines(0, n) returns newest-first; reverse for oldest-at-top. */
    val scrollbackSnapshot = remember(screenDirty) {
        /* Wave-15: Full ring (2000) is safe with LazyColumn virtualization. */
        val count = emulator.getScrollbackCount().coerceAtMost(2000)
        if (count <= 0) emptyList()
        else emulator.getScrollbackLines(0, count).asReversed()
    }

    /* Wave-13: Content grid = scrollback (top) + live screen (bottom).
     * Selection coordinates are content-row based (0 = oldest visible scrollback). */
    val sbCount = scrollbackSnapshot.size
    val totalContentRows = (sbCount + renderRows).coerceAtLeast(1)

    /* Wave-15: Auto-scroll only when user is already near the bottom.
     * Wave-20b: Never auto-scroll while selecting — content would slide under the finger. */
    LaunchedEffect(screenDirty, isSelecting, totalContentRows) {
        val lastIndex = (totalContentRows - 1).coerceAtLeast(0)
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        val nearBottom = lastVisible >= lastIndex - 2 || lastIndex <= 0
        showJumpToBottom = !nearBottom && totalContentRows > renderRows
        if (nearBottom && !isSelecting) {
            listState.scrollToItem(lastIndex)
        }
    }
    /* Wave-18/20: After zoom, pin to bottom so last rows are not cut off. */
    LaunchedEffect(gestureFontSp) {
        if (isSelecting) return@LaunchedEffect
        val lastIndex = (totalContentRows - 1).coerceAtLeast(0)
        listState.scrollToItem(lastIndex)
        showJumpToBottom = false
    }
    LaunchedEffect(listState.firstVisibleItemIndex, listState.layoutInfo.visibleItemsInfo.size, totalContentRows) {
        val lastIndex = (totalContentRows - 1).coerceAtLeast(0)
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        showJumpToBottom = lastVisible < lastIndex - 2 && totalContentRows > renderRows
    }

    /* Wave-20b: Map selection cells → screen rect via real LazyList item geometry. */
    fun selectionBoundsToRect(start: Pair<Int, Int>, end: Pair<Int, Int>): androidx.compose.ui.geometry.Rect {
        val fallbackCharH = TerminalLayoutMetrics.lineHeightPx(gestureFontSp, density)
        val fallbackCharW = TerminalLayoutMetrics.charWidthPx(gestureFontSp, density)
        val (sRow, sCol) = if (start.first < end.first || (start.first == end.first && start.second <= end.second)) start else end
        val (eRow, eCol) = if (start.first < end.first || (start.first == end.first && start.second <= end.second)) end else start
        val paddingPx = TerminalLayoutMetrics.padPx(density)
        val layoutInfo = listState.layoutInfo
        val visible = layoutInfo.visibleItemsInfo.map {
            TerminalSelectionHitTest.VisibleItem(it.index, it.offset, it.size)
        }
        val charW = TerminalSelectionHitTest.cellWidthPx(
            layoutInfo.viewportSize.width, renderCols, fallbackCharW
        )
        val topInner = TerminalSelectionHitTest.rowTopInViewport(sRow, visible, fallbackCharH)
            ?: ((sRow - listState.firstVisibleItemIndex) * fallbackCharH - listState.firstVisibleItemScrollOffset)
        val bottomInner = TerminalSelectionHitTest.rowBottomInViewport(eRow, visible, fallbackCharH)
            ?: (topInner + fallbackCharH)
        val left = sCol * charW + paddingPx
        val right = (eCol + 1) * charW + paddingPx
        val top = topInner + paddingPx
        val bottom = bottomInner + paddingPx
        return androidx.compose.ui.geometry.Rect(left, top, right, bottom)
    }

    /* Wave-14: URL under selection (for Open chip). */
    var selectedUrl by remember { mutableStateOf<String?>(null) }

    fun showSelectionToolbar() {
        val start = selectionStart ?: return
        val end = selectionEnd ?: return
        val rect = selectionBoundsToRect(start, end)
        val selectedText = getSelectedTextFromContent(
            scrollbackSnapshot, screenSnapshot,
            selectionStart, selectionEnd, renderCols, sbCount
        )
        selectedUrl = UrlOpenUtils.firstUrl(selectedText)
        textToolbar.showMenu(
            rect = rect,
            onCopyRequested = {
                if (selectedText.isNotEmpty()) {
                    clipboardManager.setText(AnnotatedString(selectedText))
                    android.widget.Toast.makeText(context, "Copied ${selectedText.length} chars", android.widget.Toast.LENGTH_SHORT).show()
                }
                textToolbar.hide()
                isSelecting = false
                selectionStart = null
                selectionEnd = null
                selectedUrl = null
            },
            onPasteRequested = {
                onPasteRequested()
                textToolbar.hide()
                isSelecting = false
                selectionStart = null
                selectionEnd = null
                selectedUrl = null
            }
        )
    }

    fun hideSelectionToolbar() {
        textToolbar.hide()
        isSelecting = false
        selectionStart = null
        selectionEnd = null
        selectedUrl = null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.background)
            .onSizeChanged { size ->
                lastSize = size
                val now = System.currentTimeMillis()
                if (now - lastResizeTime < 80) return@onSizeChanged
                lastResizeTime = now
                /* Wave-18/20: Same metrics as paint path (gesture-local font). */
                emitResize(gestureFontSp)
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
            .pointerInput(renderCols, totalContentRows) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val downTime = System.currentTimeMillis()
                    val downPos = down.position
                    val isMouse = down.type == PointerType.Mouse
                    var isLongPress = false
                    var selectionStarted = false
                    val touchSlop = with(density) { 8.dp.toPx() }
                    val longPressTimeout = 500L

                    /**
                     * Wave-20b: Convert Box-local pointer → (contentRow, col) using
                     * LazyListLayoutInfo item offsets (not assumed charH arithmetic).
                     * Fixes selection landing on the line above the touch.
                     */
                    fun posToCell(pos: androidx.compose.ui.geometry.Offset): Pair<Int, Int> {
                        val paddingPx = TerminalLayoutMetrics.padPx(density)
                        val localX = pos.x - paddingPx
                        val localY = pos.y - paddingPx
                        val layoutInfo = listState.layoutInfo
                        val visible = layoutInfo.visibleItemsInfo.map {
                            TerminalSelectionHitTest.VisibleItem(it.index, it.offset, it.size)
                        }
                        return TerminalSelectionHitTest.posToCell(
                            localX = localX,
                            localY = localY,
                            visibleItems = visible,
                            viewportWidthPx = layoutInfo.viewportSize.width,
                            cols = renderCols,
                            totalRows = totalContentRows,
                            fallbackCharW = TerminalLayoutMetrics.charWidthPx(gestureFontSp, density),
                            fallbackCharH = TerminalLayoutMetrics.lineHeightPx(gestureFontSp, density),
                            firstVisibleIndex = listState.firstVisibleItemIndex,
                            firstVisibleScrollOffset = listState.firstVisibleItemScrollOffset
                        )
                    }

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull() ?: break

                        val duration = System.currentTimeMillis() - downTime
                        val distance = (change.position - downPos).getDistance()

                        when {
                            /* Finger / button lifted → gesture selesai */
                            !change.pressed -> {
                                if (selectionStarted) {
                                    /* Phase 53: persistent selection + COPY/PASTE toolbar. */
                                    showSelectionToolbar()
                                } else if (!isLongPress && distance < touchSlop) {
                                    if (isSelecting) {
                                        hideSelectionToolbar()
                                    } else {
                                        onTap()
                                    }
                                }
                                change.consume()
                                break
                            }
                            /* Mouse drag: start selection without long-press (desktop UX). */
                            !selectionStarted && isMouse && distance > touchSlop -> {
                                selectionStarted = true
                                isSelecting = true
                                val start = posToCell(downPos)
                                selectionStart = start
                                selectionEnd = posToCell(change.position)
                                change.consume()
                            }
                            /* Touch long-press → start selection (doesn't fight scroll). */
                            !selectionStarted && !isMouse && !isLongPress &&
                                duration > longPressTimeout && distance < touchSlop -> {
                                isLongPress = true
                                selectionStarted = true
                                isSelecting = true
                                val cell = posToCell(downPos)
                                selectionStart = cell
                                selectionEnd = cell
                                change.consume()
                            }
                            /* Drag while selecting → extend selection (live hit-test). */
                            selectionStarted -> {
                                selectionEnd = posToCell(change.position)
                                change.consume()
                            }
                        }
                    }
                }
            }
            /* Phase 24 + Wave-16: Pinch-to-zoom.
             * OLD BUG: pointerInput(Unit) captured stale fontSize → zoom stuck / jumped.
             * FIX: multiply gesture-local [gestureFontSp] every frame, then notify parent. */
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    val next = TerminalFontZoom.applyPinch(gestureFontSp, zoom)
                    if (next != gestureFontSp) {
                        gestureFontSp = next
                        onFontSizeChange(next)
                        lastResizeTime = 0L
                    }
                }
            }
            /* Wave-14: Mouse wheel / trackpad scroll → history scroll, or mouse report if app mode.
             * Uses Initial pass + only reacts to non-zero scrollDelta so tap/selection still work. */
            .pointerInput(renderCols, renderRows, isSelecting) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { ch ->
                            ch.scrollDelta.y != 0f || ch.scrollDelta.x != 0f
                        } ?: continue
                        val scroll = change.scrollDelta
                        change.consume()
                        if (isSelecting) continue
                        val mouseOn = emulator.mouseTracking || emulator.mouseSgr
                        if (mouseOn) {
                            val btn = if (scroll.y < 0) 64 else 65
                            val charW = TerminalLayoutMetrics.charWidthPx(gestureFontSp, density)
                            val charH = TerminalLayoutMetrics.lineHeightPx(gestureFontSp, density)
                            val col = (change.position.x / charW).toInt().coerceIn(1, renderCols.coerceAtLeast(1))
                            val row = (change.position.y / charH).toInt().coerceIn(1, renderRows.coerceAtLeast(1))
                            emulator.encodeMouseEvent(btn, col, row, press = true)?.let { seq ->
                                emulator.writeCallback?.invoke(seq)
                            }
                        } else {
                            /* Wave-15: scroll LazyColumn by item steps when possible. */
                            val dy = scroll.y
                            scope.launch {
                                val step = if (dy < 0) -3 else 3
                                val target = (listState.firstVisibleItemIndex + step)
                                    .coerceIn(0, (totalContentRows - 1).coerceAtLeast(0))
                                listState.scrollToItem(target)
                            }
                        }
                        onScroll(scroll.y)
                    }
                }
            }
    ) {
        /* Wave-15/16/18: Virtualized rows with shared line metrics (no bottom clip). */
        val displaySp = gestureFontSp
        val rowHeightPx = TerminalLayoutMetrics.lineHeightPx(displaySp, density)
        val rowHeightDp = with(density) { rowHeightPx.toDp() }
        val cellTextStyle = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = displaySp.sp,
            lineHeight = (displaySp * TerminalLayoutMetrics.LINE_HEIGHT_EM).sp,
            /* Wave-18: Disable Android font padding — was the main cause of clipped bottoms. */
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.Both
            )
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(TerminalLayoutMetrics.PAD_DP.dp),
            userScrollEnabled = !isSelecting
        ) {
            items(
                count = totalContentRows,
                /* Wave-20: Key from bottom so live screen rows keep identity when scrollback grows
                 * (avoids LazyColumn recycling the wrong line briefly after each prompt). */
                key = { contentRow ->
                    val fromEnd = totalContentRows - 1 - contentRow
                    if (contentRow >= sbCount) "live-$fromEnd"
                    else "sb-$fromEnd"
                }
            ) { contentRow ->
                val rowCells: Array<TerminalCell> = when {
                    contentRow < sbCount -> scrollbackSnapshot.getOrElse(contentRow) { emptyArray() }
                    else -> screenSnapshot.getOrElse(contentRow - sbCount) { emptyArray() }
                }
                val liveRow = contentRow - sbCount
                val annotatedString = buildTerminalRowAnnotated(
                    rowCells = rowCells,
                    cols = renderCols,
                    cursorCol = cursorState.col,
                    cursorVisible = cursorState.visible && liveRow == cursorState.row && contentRow >= sbCount,
                    isSelecting = isSelecting,
                    selectionStart = selectionStart,
                    selectionEnd = selectionEnd,
                    rowIndex = contentRow,
                    theme = theme
                )
                Text(
                    text = annotatedString,
                    style = cellTextStyle,
                    softWrap = false,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeightDp)
                )
            }
        }

        /* Wave-12: Jump-to-bottom when browsing scrollback. */
        if (showJumpToBottom) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .background(theme.uiAccent.copy(alpha = 0.9f), RoundedCornerShape(20.dp))
                    .clickable {
                        scope.launch {
                            listState.scrollToItem((totalContentRows - 1).coerceAtLeast(0))
                        }
                        showJumpToBottom = false
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("↓", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            }
        }

        /* Wave-14: Open URL chip when selection contains http(s). */
        selectedUrl?.let { url ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .background(Color(0xFF1565C0).copy(alpha = 0.95f), RoundedCornerShape(8.dp))
                    .clickable {
                        onOpenUrl(url)
                        hideSelectionToolbar()
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    "Open URL ↗",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
        }

        if (!isAlive) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)).clickable { onRestartSession() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    deadSessionMessage,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
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

/** Extract text dari selection range di screen snapshot (live only). */
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
        val lineBuilder = StringBuilder()
        for (col in rowStart..rowEnd) {
            if (col < screen[row].size) {
                val cell = screen[row][col]
                /* Wave-12: Skip wide-char continuation cells (avoid double-width garbage). */
                if (!cell.wideContinuation) {
                    lineBuilder.append(cell.displayText())
                }
            }
        }
        /* Phase 53 fix: trimEnd whitespace — terminal cells are space-padded,
         * copy should not include trailing spaces (like Termux does). */
        sb.append(lineBuilder.toString().trimEnd())
        if (row < endRow) sb.append('\n')
    }
    return sb.toString()
}

/**
 * Wave-13: Copy selection across scrollback + live content rows.
 * [start]/[end] use content coordinates (scrollback first, then live).
 */
internal fun getSelectedTextFromContent(
    scrollback: List<Array<TerminalCell>>,
    live: Array<Array<TerminalCell>>,
    start: Pair<Int, Int>?,
    end: Pair<Int, Int>?,
    cols: Int,
    sbCount: Int
): String {
    if (start == null || end == null) return ""
    val (startRow, startCol) = if (start.first < end.first || (start.first == end.first && start.second <= end.second)) start else end
    val (endRow, endCol) = if (start.first < end.first || (start.first == end.first && start.second <= end.second)) end else start
    val sbOut = StringBuilder()
    for (row in startRow..endRow) {
        val rowCells: Array<TerminalCell> = when {
            row < sbCount -> scrollback.getOrNull(row) ?: emptyArray()
            else -> live.getOrElse(row - sbCount) { emptyArray() }
        }
        val rowStart = if (row == startRow) startCol else 0
        val rowEnd = if (row == endRow) endCol else cols - 1
        val lineBuilder = StringBuilder()
        for (col in rowStart..rowEnd) {
            if (col < rowCells.size) {
                val cell = rowCells[col]
                if (!cell.wideContinuation) lineBuilder.append(cell.displayText())
            }
        }
        sbOut.append(lineBuilder.toString().trimEnd())
        if (row < endRow) sbOut.append('\n')
    }
    return sbOut.toString()
}

/**
 * Wave-12: Build a terminal row as AnnotatedString with run-length style merges.
 * Adjacent cells with the same style share one SpanStyle (far fewer Compose spans).
 */
internal fun buildTerminalRowAnnotated(
    rowCells: Array<TerminalCell>,
    cols: Int,
    cursorCol: Int,
    cursorVisible: Boolean,
    isSelecting: Boolean,
    selectionStart: Pair<Int, Int>?,
    selectionEnd: Pair<Int, Int>?,
    rowIndex: Int,
    theme: TerminalTheme
): AnnotatedString {
    return buildAnnotatedString {
        var col = 0
        while (col < cols) {
            val cell = rowCells.getOrElse(col) { TerminalCell() }
            if (cell.wideContinuation) {
                col++
                continue
            }
            val isCursor = cursorVisible && col == cursorCol
            val isInSelection = isSelecting && selectionStart != null && selectionEnd != null &&
                isCellInSelection(rowIndex, col, selectionStart, selectionEnd)

            val bgColor: Color
            val fgColor: Color
            val bold: Boolean
            val italic: Boolean
            val underline: Boolean
            when {
                isInSelection -> {
                    bgColor = theme.uiAccent.copy(alpha = 0.4f)
                    fgColor = theme.uiText
                    bold = false
                    italic = false
                    underline = false
                }
                isCursor -> {
                    bgColor = theme.cursor
                    fgColor = theme.background
                    bold = cell.bold
                    italic = cell.italic
                    underline = false
                }
                else -> {
                    bgColor = if (cell.reverse) cell.fgColor else cell.bgColor
                    fgColor = if (cell.reverse) cell.bgColor else cell.fgColor
                    bold = cell.bold
                    italic = cell.italic
                    underline = cell.underline
                }
            }

            /* Merge adjacent same-style cells into one span. */
            val run = StringBuilder().append(cell.displayText())
            var next = col + 1
            while (next < cols) {
                val nCell = rowCells.getOrElse(next) { TerminalCell() }
                if (nCell.wideContinuation) {
                    next++
                    continue
                }
                val nCursor = cursorVisible && next == cursorCol
                val nSel = isSelecting && selectionStart != null && selectionEnd != null &&
                    isCellInSelection(rowIndex, next, selectionStart, selectionEnd)
                if (nCursor || nSel || isCursor || isInSelection) break
                val nBg = if (nCell.reverse) nCell.fgColor else nCell.bgColor
                val nFg = if (nCell.reverse) nCell.bgColor else nCell.fgColor
                if (nBg != bgColor || nFg != fgColor || nCell.bold != bold ||
                    nCell.italic != italic || nCell.underline != underline
                ) break
                run.append(nCell.displayText())
                next++
            }
            withStyle(
                SpanStyle(
                    color = fgColor,
                    background = bgColor,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                    textDecoration = if (underline) {
                        androidx.compose.ui.text.style.TextDecoration.Underline
                    } else {
                        androidx.compose.ui.text.style.TextDecoration.None
                    }
                )
            ) { append(run.toString()) }
            col = next
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
    /* Wave-9: Export chat transcript. */
    onExportChat: () -> Unit = {},
    /* Wave-9: Type snippet into terminal buffer without executing. */
    onInsertSnippet: (String) -> Unit = {},
    /* Phase 19: Image Vision. */
    pendingImages: List<String> = emptyList(),
    onAttachImage: () -> Unit = {},
    onRemoveImage: (Int) -> Unit = {},
    /* Phase 19: Model fetcher. */
    availableModels: List<ModelInfo> = emptyList(),
    isLoadingModels: Boolean = false,
    modelsFetchError: String? = null,
    onFetchModels: () -> Unit = {},
    onSelectModel: (ModelInfo) -> Unit = {},
    /* Wave-17: Stop streaming / autopilot + deep-link tab. */
    onStopAI: () -> Unit = {},
    onRetryLastPrompt: () -> Unit = {},
    autoPilotRunning: Boolean = false,
    autoPilotStep: Int = 0,
    autoPilotTotal: Int = 0,
    autoPilotCommand: String = "",
    onStopAutoPilot: () -> Unit = {},
    initialTab: Int = 0
) {
    var inputText by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(initialTab.coerceIn(0, 2)) }
    /* Settings sub-tab: 0=AI Provider, 1=Theme, 2=About. */
    var settingsSubTab by remember { mutableStateOf(0) }
    /* Wave-17: Separate scroll per tab (was one shared state). */
    val chatListState = rememberLazyListState()
    val workflowsScroll = rememberScrollState()
    val settingsScroll = rememberScrollState()
    var expandedProvider by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf<String?>(null) }
    var snippetTitle by remember { mutableStateOf("") }
    var settingsDraft by remember { mutableStateOf(settings) }
    var showSaved by remember { mutableStateOf(false) }
    var showApiKey by remember { mutableStateOf(false) }
    /* Streaming cursor blink state. */
    var cursorBlink by remember { mutableStateOf(true) }
    var showJumpToLatest by remember { mutableStateOf(false) }
    val copyText = rememberCopyToClipboard()
    val scope = rememberCoroutineScope()

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

    /* Wave-17: Stick-to-bottom only when already near end. */
    LaunchedEffect(messages.size, messages.lastOrNull()?.content, messages.lastOrNull()?.isStreaming) {
        if (messages.isEmpty()) return@LaunchedEffect
        val last = messages.lastIndex
        val info = chatListState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
        val nearBottom = lastVisible >= last - 1
        showJumpToLatest = !nearBottom && last > 2
        if (nearBottom) {
            chatListState.animateScrollToItem(last)
        }
    }
    LaunchedEffect(chatListState.firstVisibleItemIndex, chatListState.layoutInfo.visibleItemsInfo.size, messages.size) {
        if (messages.isEmpty()) {
            showJumpToLatest = false
            return@LaunchedEffect
        }
        val last = messages.lastIndex
        val lastVisible = chatListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        showJumpToLatest = lastVisible < last - 1 && last > 2
    }

    /* Cursor blink animation saat streaming aktif. */
    LaunchedEffect(isProcessingAI) {
        while (isProcessingAI) {
            cursorBlink = !cursorBlink
            kotlinx.coroutines.delay(500)
        }
        cursorBlink = true
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("AI Copilot", color = theme.uiText, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
                Text(
                    when {
                        autoPilotRunning -> "● Auto-Pilot $autoPilotStep/$autoPilotTotal"
                        isProcessingAI -> "● Streaming…"
                        else -> "${messages.size} pesan · ${settings.modelName.ifBlank { settings.providerName }}"
                    },
                    color = when {
                        autoPilotRunning || isProcessingAI -> theme.uiAccent
                        else -> theme.uiTextMuted
                    },
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                /* Wave-9: Export chat. */
                Button(
                    onClick = { onExportChat() },
                    enabled = messages.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = theme.uiSurface,
                        disabledContainerColor = theme.uiSurface.copy(alpha = 0.5f)
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("💾", color = theme.uiText, fontSize = 14.sp)
                }
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
            /* ─── Chat Tab (Wave-17) ─── */
            if (autoPilotRunning) {
                AutoPilotProgressBar(
                    current = autoPilotStep,
                    total = autoPilotTotal,
                    command = autoPilotCommand,
                    theme = theme,
                    onStop = onStopAutoPilot
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = chatListState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (messages.isEmpty()) {
                        items(1, key = { "empty" }) {
                            AiChatEmptyState(
                                theme = theme,
                                needsApiKey = settings.apiKey.isBlank() &&
                                    !settings.baseUrl.contains("localhost", ignoreCase = true) &&
                                    !settings.baseUrl.contains("127.0.0.1"),
                                onOpenSettings = { selectedTab = 2 },
                                onSuggestion = { tip ->
                                    inputText = tip
                                }
                            )
                        }
                    }
                    items(
                        count = messages.size,
                        key = { idx -> "m$idx-${messages[idx].role}-${messages[idx].content.length}" }
                    ) { idx ->
                        val msg = messages[idx]
                        AiMessageBubble(
                            msg = msg,
                            theme = theme,
                            cursorBlink = cursorBlink,
                            isProcessingAI = isProcessingAI || autoPilotRunning,
                            onRunCommand = onRunCommand,
                            onRunAutoPilot = onRunAutoPilot,
                            onSaveSnippet = { cmd -> showSaveDialog = cmd },
                            onCopy = copyText,
                            onRerun = { text -> onSendPrompt(text) },
                            onRetry = onRetryLastPrompt
                        )
                    }
                }
                if (showJumpToLatest) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                            .background(theme.uiAccent.copy(alpha = 0.92f), RoundedCornerShape(16.dp))
                            .clickable {
                                scope.launch {
                                    if (messages.isNotEmpty()) {
                                        chatListState.animateScrollToItem(messages.lastIndex)
                                    }
                                }
                                showJumpToLatest = false
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("↓ Pesan baru", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
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
            /* Wave-17: Multi-line input + Stop while streaming. */
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Button(
                    onClick = onAttachImage,
                    enabled = !isProcessingAI && !autoPilotRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = theme.uiSurface),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier.padding(end = 6.dp)
                ) {
                    Text("📎", fontSize = 14.sp)
                }
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    enabled = !isProcessingAI && !autoPilotRunning,
                    maxLines = 4,
                    textStyle = TextStyle(color = theme.uiText, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    placeholder = {
                        Text(
                            when {
                                autoPilotRunning -> "Auto-Pilot berjalan…"
                                isProcessingAI -> "AI merespons… (Stop untuk batalkan)"
                                else -> "Tanya AI atau minta tugas…"
                            },
                            color = theme.uiTextMuted,
                            fontSize = 12.sp
                        )
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if ((inputText.isNotEmpty() || pendingImages.isNotEmpty()) && !isProcessingAI && !autoPilotRunning) {
                                onSendPrompt(inputText)
                                inputText = ""
                            }
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = theme.uiAccent,
                        unfocusedBorderColor = theme.uiSurface,
                        cursorColor = theme.uiAccent,
                        focusedTextColor = theme.uiText,
                        unfocusedTextColor = theme.uiText
                    )
                )
                Spacer(modifier = Modifier.width(6.dp))
                if (isProcessingAI || autoPilotRunning) {
                    Button(
                        onClick = { if (autoPilotRunning) onStopAutoPilot() else onStopAI() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text("Stop", color = Color.White, fontSize = 12.sp)
                    }
                } else {
                    Button(
                        onClick = {
                            if (inputText.isNotEmpty() || pendingImages.isNotEmpty()) {
                                onSendPrompt(inputText)
                                inputText = ""
                            }
                        },
                        enabled = inputText.isNotEmpty() || pendingImages.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = theme.uiAccent),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text("Kirim", fontSize = 12.sp)
                    }
                }
            }
        } else if (selectedTab == 1) {
            /* ─── Workflows Tab ─── */
            Column(modifier = Modifier.weight(1f).padding(16.dp).verticalScroll(workflowsScroll)) {
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
                                    /* Wave-9: Insert into terminal line buffer without Enter. */
                                    Button(
                                        onClick = { onInsertSnippet(snippet.command) },
                                        colors = ButtonDefaults.buttonColors(containerColor = theme.uiSurface)
                                    ) { Text("⌨ Type") }
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
                    Column(modifier = Modifier.weight(1f).padding(16.dp).verticalScroll(settingsScroll)) {
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("API Key:", color = theme.uiTextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text(
                                if (showApiKey) "Sembunyikan" else "Tampilkan",
                                color = theme.uiAccent,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.clickable { showApiKey = !showApiKey }
                            )
                        }
                        OutlinedTextField(
                            value = settingsDraft.apiKey,
                            onValueChange = { settingsDraft = settingsDraft.copy(apiKey = it) },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(color = theme.uiText, fontFamily = FontFamily.Monospace),
                            placeholder = { Text("sk-...", color = theme.uiTextMuted) },
                            visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Max tokens:", color = theme.uiTextMuted, fontSize = 12.sp)
                        OutlinedTextField(
                            value = settingsDraft.maxTokens.toString(),
                            onValueChange = { v ->
                                v.toIntOrNull()?.let {
                                    settingsDraft = settingsDraft.copy(maxTokens = it.coerceIn(256, 32000))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(color = theme.uiText, fontFamily = FontFamily.Monospace),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Timeout (ms):", color = theme.uiTextMuted, fontSize = 12.sp)
                        OutlinedTextField(
                            value = settingsDraft.requestTimeoutMs.toString(),
                            onValueChange = { v -> v.toIntOrNull()?.let { settingsDraft = settingsDraft.copy(requestTimeoutMs = it.coerceIn(5000, 120000)) } },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(color = theme.uiText, fontFamily = FontFamily.Monospace),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Temperature:", color = theme.uiTextMuted, fontSize = 12.sp)
                        OutlinedTextField(
                            value = settingsDraft.temperature.toString(),
                            onValueChange = { v -> v.toDoubleOrNull()?.let { settingsDraft = settingsDraft.copy(temperature = it.coerceIn(0.0, 2.0)) } },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(color = theme.uiText, fontFamily = FontFamily.Monospace),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (settingsDraft.supportsVision) {
                                Text("👁 Vision", color = theme.uiAccent, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            }
                            if (settingsDraft.supportsToolCalling) {
                                Text("🔧 Tools", color = theme.uiAccent, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        if (showSaved) {
                            Text("✓ Tersimpan", color = theme.ansi.getOrElse(2) { Color(0xFF4CAF50) }, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                1 -> {
                    /* Theme picker. */
                    Column(modifier = Modifier.weight(1f).padding(16.dp).verticalScroll(settingsScroll)) {
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
                    Column(modifier = Modifier.weight(1f).padding(16.dp).verticalScroll(settingsScroll)) {
                        Text(
                            "Tunnel Terminal v${BuildConfig.VERSION_NAME}",
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
                        Spacer(modifier = Modifier.height(8.dp))
                        /* Wave-5: last AI request metrics. */
                        Text(
                            AiMetrics.summaryLine(),
                            color = theme.uiTextMuted,
                            fontSize = 10.sp,
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
    /* Phase 43 fix (LOW-03): Tampilkan status SECCOMP mode (normal vs fallback).
     * Phase 60 fix (audit #1d): Pakai method publik getSeccompFallbackEnabled()
     * instead of reflection ke field private 'context'. Reflection di build
     * minified (R8 enabled) akan fail dengan NoSuchFieldException. */
    val seccompPrefs = remember(installed) {
        bootstrap.getSeccompFallbackEnabled()
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
                    /* Phase 43 fix (LOW-03): Tampilkan SECCOMP mode untuk debugging. */
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("SECCOMP mode:", color = theme.uiTextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text(
                            if (seccompPrefs) "⚠ Fallback (PROOT_NO_SECCOMP=1)" else "Normal",
                            color = if (seccompPrefs) Color(0xFFFFAB00) else Color(0xFF4CAF50),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
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

/**
 * Phase 41 fix (CRIT-02): Dialog blocking untuk SSH host key change.
 *
 * Muncul saat fingerprint server SSH berubah sejak koneksi terakhir kali.
 * Potensi penyebab: server reinstall, server pindah IP, atau serangan MITM.
 *
 * User HARUS actively choose:
 *  - [Batalkan] (default, dismissible) → koneksi dibatalkan, fingerprint lama dipertahankan
 *  - [Tetap lanjutkan — tidak disarankan] → koneksi diteruskan, fingerprint diperbarui
 *
 * Tidak ada auto-approve. User awam yang tidak paham risiko akan default ke "Batalkan"
 * (paling aman). User yang tahu server mereka berubah bisa pilih lanjutkan.
 */
@Composable
fun SshHostKeyChangeDialog(
    state: SshHostKeyDialogState,
    theme: TerminalTheme,
    onDismiss: () -> Unit,
    onApprove: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,  /* dismiss = reject (default safe behavior) */
        modifier = Modifier.background(theme.uiBg),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⚠️ ", color = Color(0xFFFF5252), fontSize = 20.sp)
                Text(
                    "Peringatan Keamanan SSH",
                    color = theme.uiText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Host key untuk server ${state.host} telah BERUBAH sejak koneksi terakhir Anda.",
                    color = theme.uiText,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Ini bisa berarti:",
                    color = theme.uiTextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "• Server di-reinstall/diganti (aman)\n" +
                    "• Server pindah IP/host (perlu verifikasi)\n" +
                    "• Serangan Man-in-the-Middle (BERBAHAYA)",
                    color = theme.uiTextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(12.dp))

                /* Fingerprint comparison */
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0x33FF5252),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            "Fingerprint LAMA:",
                            color = theme.uiTextMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            state.oldFingerprint.take(80) + if (state.oldFingerprint.length > 80) "..." else "",
                            color = Color(0xFFFFAB00),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Fingerprint BARU:",
                            color = theme.uiTextMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            state.newFingerprint.take(80) + if (state.newFingerprint.length > 80) "..." else "",
                            color = Color(0xFFFF5252),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Jika Anda TIDAK yakin server Anda berubah, pilih BATALKAN untuk keamanan.",
                    color = Color(0xFFFF5252),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
            }
        },
        confirmButton = {
            /* Default (Enter) = Batalkan. Tombol "Lanjutkan" tidak default — user harus
             * actively click, mengakui risk. */
            TextButton(onClick = onDismiss) {
                Text("Batalkan", color = Color(0xFFFF5252), fontFamily = FontFamily.Monospace)
            }
        },
        dismissButton = {
            TextButton(onClick = onApprove) {
                Text("Lanjutkan (tidak disarankan)", color = theme.uiTextMuted, fontFamily = FontFamily.Monospace)
            }
        }
    )
}

/**
 * Phase 49 fix (D-4): MCP Server Management dialog.
 * User bisa add/remove MCP servers dari UI — sebelumnya hanya via kode.
 */
@Composable
fun McpServerManagementDialog(
    theme: TerminalTheme,
    servers: List<McpServerConfig>,
    onAddServer: (McpServerConfig) -> Unit,
    onRemoveServer: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf(McpTransport.SSE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.background(theme.uiBg),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔌 ", color = theme.uiAccent, fontSize = 18.sp)
                Text("MCP Server Management", color = theme.uiText, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                /* Existing servers */
                if (servers.isNotEmpty()) {
                    Text("Configured Servers:", color = theme.uiTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Spacer(modifier = Modifier.height(4.dp))
                    servers.forEach { server ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            color = theme.uiSurface,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${server.name} (${server.transport})", color = theme.uiText, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                    Text(server.url, color = theme.uiTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                }
                                TextButton(onClick = { onRemoveServer(server.name) }) {
                                    Text("Remove", color = Color(0xFFFF5252), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                /* Add new server form */
                Text("Add New Server:", color = theme.uiAccent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name", color = theme.uiTextMuted, fontSize = 9.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(color = theme.uiText, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = theme.uiAccent, unfocusedBorderColor = theme.uiSurface)
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("URL (https://...)", color = theme.uiTextMuted, fontSize = 9.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(color = theme.uiText, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = theme.uiAccent, unfocusedBorderColor = theme.uiSurface)
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it },
                    label = { Text("API Key (optional)", color = theme.uiTextMuted, fontSize = 9.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(color = theme.uiText, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = theme.uiAccent, unfocusedBorderColor = theme.uiSurface)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = transport == McpTransport.SSE, onClick = { transport = McpTransport.SSE }, label = { Text("SSE", fontSize = 9.sp) })
                    Spacer(modifier = Modifier.width(4.dp))
                    FilterChip(selected = transport == McpTransport.HTTP, onClick = { transport = McpTransport.HTTP }, label = { Text("HTTP", fontSize = 9.sp) })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank() && url.isNotBlank()) {
                        onAddServer(McpServerConfig(name = name.trim(), transport = transport, url = url.trim(), apiKey = apiKey.trim()))
                        name = ""; url = ""; apiKey = ""
                    }
                },
                enabled = name.isNotBlank() && url.isNotBlank()
            ) {
                Text("Add Server", color = if (name.isNotBlank() && url.isNotBlank()) theme.uiAccent else theme.uiTextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = theme.uiTextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    )
}
