package com.tunnel.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CommandBlock - Representasi satu command + output sebagai block diskret.
 *
 * Phase 22: Block-based terminal (Warp-style revolution).
 * Setiap command yang dijalankan user menjadi block terpisah dengan:
 * - Command text (highlighted)
 * - Output (collapsible)
 * - Status (running/success/error)
 * - Timestamp
 * - Actions (copy, rerun, AI explain)
 *
 * Block-based terminal: each command+output is a discrete block.
 */
data class CommandBlock(
    val id: Long = globalIdCounter.incrementAndGet(),
    val command: String,
    val output: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val status: BlockStatus = BlockStatus.RUNNING,
    val exitCode: Int? = null,
    val isCollapsed: Boolean = false
) {
    enum class BlockStatus {
        RUNNING, SUCCESS, ERROR, CANCELLED
    }

    val statusIcon: String get() = when (status) {
        BlockStatus.RUNNING -> "⏳"
        BlockStatus.SUCCESS -> "✓"
        BlockStatus.ERROR -> "✗"
        BlockStatus.CANCELLED -> "⊘"
    }

    val statusColor: Color get() = when (status) {
        BlockStatus.RUNNING -> Color(0xFFFFC107)
        BlockStatus.SUCCESS -> Color(0xFF4CAF50)
        BlockStatus.ERROR -> Color(0xFFFF5252)
        BlockStatus.CANCELLED -> Color(0xFF9E9E9E)
    }

    fun formattedTime(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }
    companion object {
        private val globalIdCounter = java.util.concurrent.atomic.AtomicLong(0)
    }
}

/**
 * BlockManager - Kelola list dari CommandBlock.
 * Detect command boundaries dari terminal output (prompt → command → output → prompt).
 *
 * Phase 22: Parse raw terminal output into discrete blocks.
 */
class BlockManager {
    private val _blocks = mutableStateListOf<CommandBlock>()
    val blocks: List<CommandBlock> get() = _blocks.toList()

    /** Prompt regex untuk detect command boundaries. */
    private val promptRegex = Regex("""(?:^|\n)(?:tunnel@android|[a-zA-Z_][a-zA-Z0-9_]*@[a-zA-Z0-9.-]+):[^\$#]*[\$#]\s*""")

    /** Track current block (yang sedang running). */
    private var currentBlock: CommandBlock? = null

    /**
     * Phase 51 fix (F-4): Track jumlah prompt yang sudah di-parse supaya
     * parseFromOutput bisa incremental — hanya parse prompt baru, bukan
     * re-parse semua histori dari nol setiap toggle.
     *
     * OLD BUG: parseFromOutput re-parse semua histori setiap toggle →
     * potensi divergen dari buffer raw (karakter mirip prompt, ANSI yang
     * belum sepenuhnya di-strip, dst). Plus performance issue untuk output panjang.
     */
    private var lastParsedPromptCount = 0
    private var lastParsedRawLength = 0

    /**
     * Parse raw terminal output menjadi blocks.
     * Dipanggil saat user toggle ke block mode.
     *
     * Phase 51 fix (F-4): Sekarang incremental — kalau rawOutput sudah pernah
     * di-parse (lastParsedRawLength > 0), hanya parse prompt baru yang muncul
     * setelah posisi terakhir yang di-parse. Hindari re-parse semua histori.
     */
    fun parseFromOutput(rawOutput: String) {
        // Kalau ini parse pertama kali, atau rawOutput lebih pendek dari sebelumnya
        // (mis. user clear screen), lakukan full parse
        if (lastParsedRawLength == 0 || rawOutput.length < lastParsedRawLength) {
            _blocks.clear()
            currentBlock = null
            lastParsedPromptCount = 0
        }

        val matches = promptRegex.findAll(rawOutput).toList()
        if (matches.isEmpty()) {
            /* No prompts found — single block with all output (hanya kalau belum ada blocks). */
            if (rawOutput.isNotBlank() && _blocks.isEmpty()) {
                _blocks.add(CommandBlock(
                    command = "(output)",
                    output = rawOutput.trim(),
                    status = CommandBlock.BlockStatus.SUCCESS
                ))
            }
            lastParsedRawLength = rawOutput.length
            return
        }

        // Phase 51 fix (F-4): Hanya parse prompt yang belum di-parse
        val startIdx = lastParsedPromptCount
        if (startIdx >= matches.size) {
            // Tidak ada prompt baru — update output block terakhir saja
            lastParsedRawLength = rawOutput.length
            return
        }

        for (i in startIdx until matches.size) {
            val promptMatch = matches[i]
            val promptEnd = promptMatch.range.last + 1
            val nextPromptStart = if (i + 1 < matches.size) matches[i + 1].range.first else rawOutput.length

            /* Command = text setelah prompt sampai newline pertama. */
            val afterPrompt = rawOutput.substring(promptEnd, nextPromptStart)
            val newlineIdx = afterPrompt.indexOf('\n')
            val command = if (newlineIdx >= 0) afterPrompt.substring(0, newlineIdx).trim() else afterPrompt.trim()
            val output = if (newlineIdx >= 0) afterPrompt.substring(newlineIdx + 1).trim() else ""

            if (command.isNotEmpty()) {
                /* BUG-19 fix: Histori command sudah selesai — default SUCCESS, bukan RUNNING.
                 * RUNNING hanya untuk command live via addBlock(). */
                val status = when {
                    output.lowercase().contains("error") ||
                    output.lowercase().contains("not found") ||
                    output.lowercase().contains("no such file") ||
                    output.lowercase().contains("permission denied") -> CommandBlock.BlockStatus.ERROR
                    else -> CommandBlock.BlockStatus.SUCCESS
                }
                _blocks.add(CommandBlock(
                    command = command,
                    output = output,
                    status = status,
                    timestamp = System.currentTimeMillis() - (matches.size - i) * 1000L
                ))
            }
        }

        lastParsedPromptCount = matches.size
        lastParsedRawLength = rawOutput.length
    }

    /** Add new block (saat user run command di block mode). */
    fun addBlock(command: String): CommandBlock {
        val block = CommandBlock(command = command, status = CommandBlock.BlockStatus.RUNNING)
        _blocks.add(block)
        currentBlock = block
        return block
    }

    /** Update block output (saat output diterima). */
    fun updateBlockOutput(id: Long, output: String, status: CommandBlock.BlockStatus) {
        val idx = _blocks.indexOfFirst { it.id == id }
        if (idx >= 0) {
            _blocks[idx] = _blocks[idx].copy(output = output, status = status)
            if (status != CommandBlock.BlockStatus.RUNNING && currentBlock?.id == id) {
                currentBlock = null
            }
        }
    }

    /** Wave-3: Live-update the current RUNNING block from terminal output. */
    fun updateRunningOutput(output: String) {
        val block = currentBlock ?: return
        updateBlockOutput(block.id, output, CommandBlock.BlockStatus.RUNNING)
    }

    /** Wave-3: Mark current RUNNING block finished. */
    fun completeRunning(output: String, status: CommandBlock.BlockStatus) {
        val block = currentBlock ?: _blocks.lastOrNull { it.status == CommandBlock.BlockStatus.RUNNING } ?: return
        updateBlockOutput(block.id, output, status)
        currentBlock = null
    }

    fun hasRunningBlock(): Boolean =
        currentBlock != null || _blocks.any { it.status == CommandBlock.BlockStatus.RUNNING }

    /** Clear all blocks. */
    fun clear() {
        _blocks.clear()
        currentBlock = null
        /* Phase 51 fix (F-4): Reset incremental parse tracker. */
        lastParsedPromptCount = 0
        lastParsedRawLength = 0
    }

    /** Toggle collapse block. */
    fun toggleCollapse(id: Long) {
        val idx = _blocks.indexOfFirst { it.id == id }
        if (idx >= 0) {
            _blocks[idx] = _blocks[idx].copy(isCollapsed = !_blocks[idx].isCollapsed)
        }
    }
    companion object {
        private val globalIdCounter = java.util.concurrent.atomic.AtomicLong(0)
    }
}

/**
 * BlockTerminalView - Render terminal sebagai list of CommandBlocks.
 *
 * Phase 22: Block-based terminal view (alternative to raw TerminalScreenView).
 * User toggle antara raw mode (for TUI apps like vim/htop) dan block mode (for
 * command history review).
 *
 * Block-based terminal view (alternative to raw mode).
 */
@Composable
fun BlockTerminalView(
    blocks: List<CommandBlock>,
    theme: TerminalTheme,
    onBlockClick: (CommandBlock) -> Unit = {},
    onBlockRerun: (CommandBlock) -> Unit = {},
    onBlockExplain: (CommandBlock) -> Unit = {},
    onToggleCollapse: (Long) -> Unit = {},
    /* Phase 44 fix (MED-02): Pinch-to-zoom support di Block Mode.
     * Sebelumnya pinch-zoom hanya jalan di raw mode — inkonsistensi UX. */
    fontSizeState: Float = 12f,
    onFontSizeChange: (Float) -> Unit = {}
) {
    val scrollState = androidx.compose.foundation.rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.background)
            .verticalScroll(scrollState)
            .padding(8.dp)
            /* Phase 44 fix (MED-02): Tambah pinch-to-zoom gesture detector. */
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    val newFont = (fontSizeState * zoom).coerceIn(8f, 24f)
                    if (newFont != fontSizeState) {
                        onFontSizeChange(newFont)
                    }
                }
            }
    ) {
        if (blocks.isEmpty()) {
            Text(
                "No commands yet. Type a command below to start.\n\n" +
                "Block mode shows each command + output as a discrete card.\n" +
                "Toggle raw mode for TUI apps (vim, htop).",
                color = theme.uiTextMuted,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(16.dp)
            )
        }

        blocks.forEach { block ->
            CommandBlockCard(
                block = block,
                theme = theme,
                onClick = { onBlockClick(block) },
                onRerun = { onBlockRerun(block) },
                onExplain = { onBlockExplain(block) },
                onToggleCollapse = { onToggleCollapse(block.id) }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CommandBlockCard(
    block: CommandBlock,
    theme: TerminalTheme,
    onClick: () -> Unit,
    onRerun: () -> Unit,
    onExplain: () -> Unit,
    onToggleCollapse: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = theme.uiSurface.copy(alpha = 0.7f)
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            block.statusColor.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            /* Header: status icon + command + timestamp + actions. */
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    block.statusIcon,
                    fontSize = 14.sp,
                    color = block.statusColor,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    "$ ",
                    color = theme.uiTextMuted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    block.command,
                    color = theme.ansi.getOrElse(6) { Color(0xFF00BCD4) },
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    block.formattedTime(),
                    color = theme.uiTextMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            /* Output (collapsible). */
            if (!block.isCollapsed && block.output.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(theme.background.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .padding(8.dp)
                ) {
                    Text(
                        block.output,
                        color = theme.foreground,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            /* Actions row. */
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(
                    onClick = onToggleCollapse,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = theme.uiTextMuted)
                ) {
                    Text(if (block.isCollapsed) "Expand" else "Collapse", fontSize = 10.sp)
                }
                TextButton(
                    onClick = onRerun,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = theme.ansi.getOrElse(6) { Color(0xFF00BCD4) })
                ) {
                    Text("↻ Rerun", fontSize = 10.sp)
                }
                TextButton(
                    onClick = onExplain,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = theme.uiAccent)
                ) {
                    Text("🤖 Explain", fontSize = 10.sp)
                }
            }
        }
    }
}
