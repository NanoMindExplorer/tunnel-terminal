package com.tunnel.terminal

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * MainActivity - Entry point aplikasi Tunnel Terminal.
 *
 * Phase 17 (Major Bug Fix) - Perubahan signifikan:
 *
 * 1. StorageManager integration: `setup-storage` sekarang benar-benar membuka SAF picker
 *    (sebelumnya phantom - dilempar ke /system/bin/sh yang tidak kenal perintah ini)
 * 2. `clear` command ditangani lokal (memanggil ShellExecutor.clearScreen)
 * 3. MOTD dinamis via SystemInfo (Android version, memory, disk, IP, uptime)
 * 4. Auto-Pilot cerdas: tunggu prompt shell muncul sebelum next command,
 *    deteksi error mid-sequence, log setiap step
 * 5. Per-executor command history (tidak shared antar tab)
 * 6. Strip ANSI dari terminal output sebelum dikirim ke AI context
 * 7. FAB contextual: deteksi error/no-error di terminal, prompt AI lebih cerdas
 * 8. Back button: tutup drawer/editor dulu sebelum exit app
 * 9. POST_NOTIFICATIONS permission request untuk Android 13+
 * 10. Hapus duplikat Enter handling (BasicTextField onValueChange + onPreviewKeyEvent)
 *
 * Entry point. Phase 17 fixes phantom commands, adds MOTD, smart Auto-Pilot,
 * per-tab history, contextual FAB, back button, POST_NOTIFICATIONS permission.
 */
class MainActivity : ComponentActivity() {
    /* Phase 21: Changed from ShellExecutor to TerminalSession interface
     * untuk support SSH sessions alongside local PTY. */
    /* Phase 49 fix (F-3): shellExecutors + activeExecutorId di-hold di Application scope
     * supaya survive Activity recreate (rotasi, low-memory kill). Screen buffer tidak hilang. */
    private lateinit var shellExecutors: MutableList<TerminalSession>
    private var activeExecutorId by mutableStateOf(0)
    /* Phase 60 fix (audit B-2): AIAgent sekarang butuh McpManager reference
     * supaya MCP tools bisa ditambahkan dinamis ke TOOL_SCHEMA. Inisialisasi
     * lazy — mcpManager di-set di onCreate() setelah McpManager dibuat. */
    private val aiAgent = AIAgent()

    private val chatMessages = mutableStateListOf<ChatMessage>()
    private var aiSettings by mutableStateOf(AISettings())
    private var isProcessingAI by mutableStateOf(false)
    /** Wave-17: Job for cancellable AI stream. */
    private var aiJob: kotlinx.coroutines.Job? = null
    /** Wave-17: Auto-Pilot running state + progress for chat chrome. */
    private var autoPilotRunning by mutableStateOf(false)
    private var autoPilotStep by mutableStateOf(0)
    private var autoPilotTotal by mutableStateOf(0)
    private var autoPilotCommand by mutableStateOf("")
    @Volatile private var autoPilotStopped = false
    private var autoPilotJob: kotlinx.coroutines.Job? = null
    /** Wave-17: Open drawer on settings tab when requested from palette. */
    private var chatInitialTab by mutableStateOf(0)
    private var editingFile by mutableStateOf<String?>(null)

    private lateinit var snippetManager: SnippetManager
    private val snippetsState = mutableStateListOf<Snippet>()

    /** Shared command buffer for the currently focused tab's input line.
     * Phase 19.5: Moved to ShellExecutor (per-tab). These kept for backward-compat
     * but no longer used — see activeExecutor.currentCommandBuffer. */

    /** Storage Access Framework manager. */
    private lateinit var storageManager: StorageManager

    /** Workspace sessions manager (Phase 19). */
    private lateinit var workspaceManager: WorkspaceManager
    private val workspaceSessions = mutableStateListOf<WorkspaceSession>()

    /** Theme holder - shared across all ShellExecutor instances.
     * Saat user ganti tema, semua tab langsung update (tanpa re-create emulator). */
    private val themeHolder = ThemeHolder()
    private var currentTheme by mutableStateOf(ThemeManager.defaultTheme)

    /** Phase 19: Pending image attachments (base64) untuk pesan AI berikutnya. */
    private val pendingImages = mutableStateListOf<String>()
    /** Phase 19: File explorer drawer visibility. */
    private var showFileExplorer by mutableStateOf(false)
    /** Phase 19: Workspace drawer visibility. */
    private var showWorkspaceDrawer by mutableStateOf(false)
    /** Phase 21: SSH connect dialog visibility. */
    private var showSshDialog by mutableStateOf(false)
    /** Wave-10: Custom tab labels (id → name). */
    private val tabLabels = mutableStateMapOf<Int, String>()
    /** Wave-10: Rename dialog target tab id. */
    private var renameTabId by mutableStateOf<Int?>(null)
    private var renameTabDraft by mutableStateOf("")
    /**
     * Wave-11/12: Transparent IME field state (Activity-scoped so paste/history/ExtraKeys can sync).
     * [imeFieldText] = BasicTextField value; [imeFieldLast] = last applied IME string for deltas.
     */
    private var imeFieldText by mutableStateOf("")
    private var imeFieldLast by mutableStateOf("")
    /** Wave-15: ExtraKeys expanded (symbols + F-row). Default compact for more terminal height. */
    private var extraKeysExpanded by mutableStateOf(false)
    /**
     * Wave-21: AI Copilot as a right-side panel (does NOT cover the terminal).
     * User can watch AI run commands while reading chat/settings.
     */
    private var aiPanelOpen by mutableStateOf(false)
    /** Phase 47 (Bagian 2): Agent Mode screen visibility. */
    private var showAgentScreen by mutableStateOf(false)
    /** Phase 49 (D-4): MCP server management dialog visibility. */
    private var showMcpServerDialog by mutableStateOf(false)
    /** Phase 47: Agent task runner instance. */
    private lateinit var agentTaskRunner: AgentTaskRunner
    /** Phase 50 fix (B-5): Project context for AI awareness. */
    private lateinit var projectContext: ProjectContext
    /** Phase 50 fix (B-4): Checkpoint manager for AI file edit undo. */
    private lateinit var checkpointManager: CheckpointManager
    /** Phase 58 fix (§4.6): Task plan manager for plan/act/observe/verify loop. */
    private lateinit var taskPlanManager: TaskPlanManager
    /** Phase 47: Agent event log (real-time). */
    private val agentEvents = mutableStateListOf<AgentTaskRunner.AgentEvent>()
    /** Phase 47: Is Agent task running? */
    private var agentRunning by mutableStateOf(false)
    /** Wave-1: Agent pause state for Resume button UI. */
    private var agentPaused by mutableStateOf(false)
    /** Wave-6: Last clarification question from agent (null if none). */
    private var agentPendingClarification by mutableStateOf<String?>(null)
    /** Wave-6: Goal + useUbuntu remembered so clarification can continue the task. */
    private var agentLastGoal by mutableStateOf("")
    private var agentLastUseUbuntu by mutableStateOf(true)
    /** Phase 52 fix (Bug #1): State untuk approval dialog (CompletableDeferred bridge). */
    private var pendingAgentApproval by mutableStateOf<Pair<AiToolCall, String>?>(null)
    private var agentApprovalDeferred: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
    /** Phase 52 fix (Bug #3): Job reference untuk Stop via cancel(). */
    private var agentJob: kotlinx.coroutines.Job? = null
    /** Phase 41 fix (CRIT-02): State untuk SSH host key change dialog (blocking).
     *  Non-null = dialog sedang visible, user harus pilih approve/reject. */
    private val _sshHostKeyDialogState = mutableStateOf<SshHostKeyDialogState?>(null)
    /** Phase 38 (proot/Ubuntu): Ubuntu install dialog visibility. */
    private var showUbuntuInstallDialog by mutableStateOf(false)
    /** Phase 38 (proot/Ubuntu): ProotBootstrap instance (lazy — butuh Context). */
    private lateinit var prootBootstrap: ProotBootstrap
    /** Phase 39 (proot/Ubuntu): Install progress state untuk dialog. */
    private var ubuntuInstallStage by mutableStateOf("")
    private var ubuntuInstallPercent by mutableStateOf(0)
    private var ubuntuInstallError by mutableStateOf<String?>(null)
    private var ubuntuInstalling by mutableStateOf(false)
    /** Phase 21: Split pane mode — 2 terminals side by side. */
    private var splitMode by mutableStateOf(false)
    /** Phase 21: Second pane session ID (for split mode). */
    private var splitPaneId by mutableStateOf(0)
    /** Phase 22: Command palette (Ctrl+K) visibility. */
    private var showCommandPalette by mutableStateOf(false)
    /** Phase 22: Block mode (Warp-style block terminal) toggle. */
    private var blockMode by mutableStateOf(false)
    /** Phase 22: Block manager per active session. */
    private val blockManager = BlockManager()
    /** Phase 22: AI tool call pending permission. */
    private var pendingToolCall by mutableStateOf<AiToolCall?>(null)
    /** Wave-2: Multi-turn tool loop depth (chat auto-continue after tool results). */
    private var toolLoopDepth = 0
    private val maxToolLoopDepth = 8
    /** Phase 22: Tool executor + permission manager. */
    private lateinit var toolExecutor: ToolExecutor
    private lateinit var permissionManager: PermissionManager
    /** Phase 23: Context manager (@mentions), MCP manager, Agent workflow manager, Voice input. */
    private lateinit var contextManager: ContextManager
    private lateinit var mcpManager: McpManager
    private lateinit var agentWorkflowManager: AgentWorkflowManager
    private lateinit var voiceInputManager: VoiceInputManager
    private val agentWorkflows = mutableStateListOf<AgentWorkflow>()
    /** Phase 23: Voice input launcher. */
    private val voiceInputLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spokenText = result.data
                ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                /* Phase 24.5: Set pending voice text, akan di-process di composable.
                 * Old code: set _pendingVoiceText tapi tidak pernah dirender.
                 * Fix: set flag, LaunchedEffect di TerminalApp() akan handle. */
                _pendingVoiceText.value = spokenText
            }
        }
    }
    private val _pendingVoiceText = mutableStateOf("")
    /** Phase 23: Pending diff untuk review (AI file edit). */
    private var pendingDiff by mutableStateOf<Triple<String, String, String>?>(null)
    /** Phase 23: MCP tools discovered. */
    private val mcpTools = mutableStateListOf<McpTool>()
    /** Phase 24: Global font size untuk terminal (persist pinch-to-zoom across tabs/modes).
     *  Phase 26: Persist ke SharedPreferences supaya tidak reset setelah app restart.
     *  C1 fix: Jangan panggil getSharedPreferences di property initializer (sebelum onCreate).
     *  Default 12f, load di onCreate(). */
    private var terminalFontSize by mutableStateOf(12f)
    /** Phase 19: Available models dari fetch /models. */
    private val availableModels = mutableStateListOf<ModelInfo>()
    private var isLoadingModels by mutableStateOf(false)
    private var modelsFetchError by mutableStateOf<String?>(null)

    /**
     * Wave-19: SAF tree picker with Downloads as suggested start folder.
     * Uses StartActivityForResult + StorageManager.createOpenTreeIntent() so
     * persistable read/write flags and EXTRA_INITIAL_URI (primary:Download) apply.
     */
    private val storageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK && uri != null) {
            val ok = storageManager.persistTreeUri(uri)
            val msg = if (ok) {
                storageManager.createStorageSymlink()
            } else {
                "Gagal mengambil persistable permission untuk URI: $uri"
            }
            termNotify("\n\u001B[32m$msg\u001B[0m\n")
        } else {
            termNotify("\n\u001B[33mSetup storage dibatalkan.\u001B[0m\n")
        }
    }

    /** Wave-19: Return from Settings "All files access" → rebuild bridge if SAF already set. */
    private val manageAllFilesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val msg = if (storageManager.hasAllFilesAccess()) {
            if (storageManager.isSetupDone()) {
                storageManager.createStorageSymlink()
            } else {
                "✓ Akses semua file AKTIF. Ketik setup-storage lalu pilih Download agar shell path & storage-* siap."
            }
        } else {
            "Akses semua file BELUM aktif. Anda masih bisa pakai storage-* (SAF) dan storage-save-download."
        }
        termNotify("\n\u001B[36m$msg\u001B[0m\n")
    }

    /** Print a message into the active terminal (Wave-19 storage helpers). */
    private fun termNotify(ansiOrText: String) {
        shellExecutors.find { it.id == activeExecutorId }?.let { exec ->
            exec.emulator.process(ansiOrText)
            exec.triggerScreenUpdate()
        }
    }

    /** Phase 19: Image picker launcher untuk AI vision. */
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            lifecycleScope.launch {
                /* BUG-12 fix: Image decode di IO thread, bukan main thread. */
                val base64List = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    uris.mapNotNull { uri ->
                        ImageHelper.uriToBase64(this@MainActivity, uri)
                    }
                }
                if (base64List.isNotEmpty()) {
                    pendingImages.addAll(base64List)
                    val totalKB = base64List.sumOf { it.length / 1024 }
                    Toast.makeText(
                        this@MainActivity,
                        "${base64List.size} gambar siap (${totalKB}KB). Ketik prompt lalu kirim.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        /* Wave-10: Keep screen on while using the terminal (optional, default on). */
        val keepOn = getSharedPreferences("TunnelUI", Context.MODE_PRIVATE)
            .getBoolean("keepScreenOn", true)
        if (keepOn) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        /* Phase 49 fix (F-3): Ambil shellExecutors dari Application scope.
         * Survive Activity recreate — screen buffer tidak hilang saat rotasi/low-memory. */
        val app = application as TunnelApp
        shellExecutors = app.shellExecutors
        if (app.activeExecutorId != 0) {
            activeExecutorId = app.activeExecutorId
        }

        snippetManager = SnippetManager(this)
        snippetsState.addAll(snippetManager.snippets)
        storageManager = StorageManager(this)
        workspaceManager = WorkspaceManager(this)
        workspaceSessions.addAll(workspaceManager.sessions)
        /* Phase 50 fix (B-4): Init CheckpointManager before ToolExecutor. */
        checkpointManager = CheckpointManager(this)
        /* Phase 58 fix (§4.6): Init TaskPlanManager. */
        taskPlanManager = TaskPlanManager()
        toolExecutor = ToolExecutor(this, storageManager, checkpointManager, null, taskPlanManager)
        /* Wave-4: Wire live terminal output for get_terminal_output tool. */
        toolExecutor.setTerminalOutputProvider {
            shellExecutors.find { it.id == activeExecutorId }?.getCleanOutput() ?: ""
        }
        permissionManager = PermissionManager(this)
        contextManager = ContextManager(this)
        /* Wave-5: Sandbox @file: mentions via ToolExecutor path resolver. */
        contextManager.setPathResolver { raw ->
            toolExecutor.resolvePathForAccess(raw)
        }
        mcpManager = McpManager(this)
        /* Phase 60 fix (audit B-2): Set mcpManager ke aiAgent supaya MCP tools
         * bisa di-inject dinamis ke TOOL_SCHEMA di setiap request AI. */
        aiAgent.setMcpManager(mcpManager)
        agentWorkflowManager = AgentWorkflowManager(this)
        /* Phase 47 (Bagian 2) + Wave-2: AgentTaskRunner with MCP support. */
        agentTaskRunner = AgentTaskRunner(aiAgent, toolExecutor, permissionManager, markerExecutor, mcpManager)
        /* Phase 50 fix (B-5): Init ProjectContext for AI awareness. */
        projectContext = ProjectContext(this)
        voiceInputManager = VoiceInputManager(this)
        /* Phase 38 (proot/Ubuntu): Bootstrap instance untuk download/extract rootfs. */
        prootBootstrap = ProotBootstrap(this)
        agentWorkflows.addAll(agentWorkflowManager.workflows)
        loadAISettings()
        loadTheme()
        /* C1 fix: Load fontSize di onCreate (bukan di property initializer). */
        try {
            val uiPrefs = getSharedPreferences("TunnelUI", Context.MODE_PRIVATE)
            terminalFontSize = TerminalFontZoom.snap(
                uiPrefs.getFloat("fontSize", TerminalFontZoom.DEFAULT_SP)
            )
            /* Wave-15: Compact ExtraKeys by default; expand state persisted. */
            extraKeysExpanded = uiPrefs.getBoolean("extraKeysExpanded", false)
            /* Wave-21: Side panel open state (default closed → max terminal width). */
            aiPanelOpen = uiPrefs.getBoolean("aiPanelOpen", false)
            aiDrawerOpen = aiPanelOpen
        } catch (_: Exception) {
            terminalFontSize = TerminalFontZoom.DEFAULT_SP
            extraKeysExpanded = false
            aiPanelOpen = false
            aiDrawerOpen = false
        }

        /* C2+H2 fix: Pindahkan startForegroundService SETELAH setContent.
         * Juga delay sedikit agar permission dialog (jika ada) tidak conflict. */
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = currentTheme.background) {
                    /* Phase 49 fix (F-3): Sync activeExecutorId ke Application scope
                     * supaya survive Activity recreate. */
                    LaunchedEffect(activeExecutorId) {
                        (application as? TunnelApp)?.activeExecutorId = activeExecutorId
                    }
                    TerminalApp()
                }
            }
        }

        /* H1 fix: Request notification permission SETELAH setContent.
         * Old code: requestNotificationPermission() di onCreate sebelum setContent
         * → permission dialog muncul sebelum window siap → crash di beberapa device. */
        requestNotificationPermission()

        /* C2+H2 fix: Start foreground service setelah UI siap + notification permission.
         * Di Android 13+, startForeground tanpa POST_NOTIFICATIONS permission granted
         * bisa crash di beberapa OEM. Delay 500ms agar permission dialog selesai. */
        lifecycleScope.launch {
            delay(500)
            try {
                val serviceIntent = Intent(this@MainActivity, TerminalForegroundService::class.java)
                startForegroundService(serviceIntent)
            } catch (_: Exception) {
                /* Jika gagal, tidak fatal — app tetap jalan tanpa foreground service. */
            }
        }

        /* Buat tab pertama — HANYA kalau belum ada (Phase 49 F-3: Activity recreate
         * tidak buat tab baru, pakai yang sudah ada di Application scope). */
        if (shellExecutors.isEmpty()) {
            lifecycleScope.launch { createNewTab() }
        }
    }

    /** Load saved theme from prefs and apply to themeHolder. */
    private fun loadTheme() {
        val theme = ThemeManager.getActiveTheme(this)
        themeHolder.theme = theme
        currentTheme = theme
    }

    /** Change theme at runtime: update holder + persist. */
    private fun changeTheme(newTheme: TerminalTheme) {
        /* Phase 44 fix (MED-01): Recolor sel yang ada supaya tema baru langsung apply
         * ke seluruh layar, bukan cuma sel baru. */
        val oldFg = themeHolder.theme.foreground
        val oldBg = themeHolder.theme.background
        themeHolder.theme = newTheme
        currentTheme = newTheme
        ThemeManager.setActiveTheme(this, newTheme)
        /* Phase 44 fix: Recolor semua sel di setiap tab. */
        shellExecutors.forEach { executor ->
            executor.emulator.recolorForTheme(oldFg, oldBg)
            executor.triggerScreenUpdate()
        }
    }

    /** Clear chat conversation history (multi-turn memory reset). */
    private fun clearChat() {
        chatMessages.clear()
        pendingImages.clear()
        Toast.makeText(this, "Chat cleared", Toast.LENGTH_SHORT).show()
    }

    /** Wave-9: Soft-cap chat list so UI stays responsive on long sessions. */
    private fun trimChatIfNeeded(maxMessages: Int = 80) {
        while (chatMessages.size > maxMessages) {
            chatMessages.removeAt(0)
        }
    }

    /** Wave-9: Export AI chat to filesDir/exports/. */
    private fun exportChat() {
        val r = ChatExporter.export(this, chatMessages.toList())
        Toast.makeText(this, if (r.ok) "Chat exported:\n${r.path}" else r.message, Toast.LENGTH_LONG).show()
    }

    /**
     * Wave-9: Type text into the active terminal buffer without executing (no Enter).
     * Replaces any in-progress line buffer.
     */
    private fun insertIntoTerminal(text: String) {
        val exec = shellExecutors.find { it.id == activeExecutorId } ?: return
        val old = exec.currentCommandBuffer
        if (old.isNotEmpty()) {
            repeat(old.length) { exec.writeRaw("\u007F") }
        }
        exec.currentCommandBuffer = text
        exec.writeRaw(text)
        /* Wave-12: Align IME tracker after programmatic insert. */
        syncImeLine(text)
        Toast.makeText(this, "Inserted into terminal (not executed)", Toast.LENGTH_SHORT).show()
    }

    /** Wave-9 + Wave-16: Reset font size to density-aware default and persist. */
    private fun resetFontSize() {
        val dm = resources.displayMetrics
        val defaultSp = TerminalFontZoom.defaultForDensity(dm.density)
        applyTerminalFontSize(defaultSp, persistImmediately = true, toast = true)
    }

    /**
     * Wave-16: Single path for font size changes (pinch / A+ A− / palette / reset).
     * Debounces SharedPreferences writes during continuous pinch.
     */
    private var fontPersistRunnable: Runnable? = null
    private val fontPersistHandler by lazy { android.os.Handler(mainLooper) }

    private fun applyTerminalFontSize(
        sp: Float,
        persistImmediately: Boolean = false,
        toast: Boolean = false
    ) {
        val next = TerminalFontZoom.snap(sp)
        if (next == terminalFontSize && !toast) return
        terminalFontSize = next
        val prefs = getSharedPreferences("TunnelUI", Context.MODE_PRIVATE)
        if (persistImmediately) {
            fontPersistRunnable?.let { fontPersistHandler.removeCallbacks(it) }
            prefs.edit().putFloat("fontSize", next).apply()
        } else {
            fontPersistRunnable?.let { fontPersistHandler.removeCallbacks(it) }
            val r = Runnable { prefs.edit().putFloat("fontSize", terminalFontSize).apply() }
            fontPersistRunnable = r
            fontPersistHandler.postDelayed(r, 350)
        }
        if (toast) {
            Toast.makeText(this, "Font ${TerminalFontZoom.formatLabel(next)}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stepTerminalFont(direction: Int) {
        applyTerminalFontSize(
            TerminalFontZoom.step(terminalFontSize, direction),
            persistImmediately = true,
            toast = true
        )
    }

    /* ─── Phase 22: AI Tool Call Execution ─── */

    /**
     * Process AI response untuk detect tool calls.
     * Jika ada destructive tool call → set pendingToolCall (trigger permission dialog).
     * Jika read-only → execute langsung.
     * Returns true jika ada tool call yang diproses.
     *
     * Detect tool calls in AI response, execute with permission flow.
     */
    private fun processToolCalls(response: String): Boolean {
        val calls = AiToolCall.parseFromResponse(response)
        if (calls.isEmpty()) return false

        for (call in calls) {
            if (permissionManager.isApproved(call)) {
                /* Read-only atau pre-approved — execute langsung. */
                executeToolCall(call, alwaysAllow = permissionManager.getPermission(call.tool) == PermissionManager.PermissionState.ALWAYS_ALLOW)
            } else if (permissionManager.needsPrompt(call)) {
                /* Destructive + needs permission — trigger dialog. */
                pendingToolCall = call
                return true  /* Stop processing, wait for user permission. */
            } else {
                /* Always deny. */
                chatMessages.add(ChatMessage("assistant", "Permission denied (always) for: ${call.displayText}", false, isError = true))
            }
        }
        return true
    }

    /** Wave-2: Coalesce multiple tool results from one AI turn into a single follow-up. */
    private var pendingToolLoopResults: StringBuilder? = null

    /**
     * Wave-2: Feed tool results back into the model for multi-turn tool use
     * (Cursor/Claude Code style), with a hard depth cap.
     * Multiple sync tool results in one turn are batched (~80ms) into one prompt.
     */
    private fun continueToolLoop(resultText: String) {
        if (toolLoopDepth >= maxToolLoopDepth) {
            chatMessages.add(
                ChatMessage(
                    "assistant",
                    "⚠ Tool loop limit ($maxToolLoopDepth) reached. Lanjutkan manual jika masih perlu.",
                    false,
                    isError = true
                )
            )
            toolLoopDepth = 0
            pendingToolLoopResults = null
            return
        }
        val batch = pendingToolLoopResults
        if (batch != null) {
            batch.append("\n---\n").append(resultText.take(2000))
            return
        }
        val newBatch = StringBuilder(resultText.take(2000))
        pendingToolLoopResults = newBatch
        lifecycleScope.launch {
            delay(80)
            val text = pendingToolLoopResults?.toString() ?: return@launch
            pendingToolLoopResults = null
            if (toolLoopDepth >= maxToolLoopDepth) return@launch
            toolLoopDepth++
            handleAIPrompt(
                "Hasil tool:\n${text.take(4000)}\n\n" +
                    "Lanjutkan tugas berdasarkan hasil ini. " +
                    "Jika sudah selesai, jawab singkat TANPA tool call lagi.",
                fromToolResult = true
            )
        }
    }

    /** Execute single tool call. */
    private fun executeToolCall(call: AiToolCall, alwaysAllow: Boolean) {
        chatMessages.add(ChatMessage("assistant", "🔧 Tool: ${call.displayText}", false))

        when {
            call.tool == "run_command" -> {
                /* Phase 37: run_command pakai MarkerExecutor — bukan fire-and-forget.
                 * Phase 46 (Pilar 1b): Handle ExecutionOutcome (3 kemungkinan).
                 * Wave-23: Ensure resolver matches active session (Ubuntu bash). */
                val cmd = call.args["cmd"] ?: return
                val session = shellExecutors.find { it.id == activeExecutorId }
                if (session != null) {
                    syncToolExecutorToSession(session)
                    chatMessages.add(ChatMessage("assistant", "🔧 Running [${session.sessionType}]: $cmd", false))
                    lifecycleScope.launch {
                        val outcome = markerExecutor.executeWithMarker(
                            session, cmd,
                            maxTimeoutMs = 300000,  // 5 min — apt install bisa lama
                            idleTimeoutMs = 15000   // 15s idle = curiga nunggu input
                        )
                        val outcomeText = markerExecutor.formatOutcomeForAI(outcome)
                        chatMessages.add(ChatMessage("assistant", "📋 Result:\n$outcomeText", false))
                        continueToolLoop(outcomeText)
                    }
                } else {
                    chatMessages.add(ChatMessage("assistant", "Error: no active terminal session", false, isError = true))
                    continueToolLoop("Error: no active terminal session")
                }
            }
            call.tool == "write_file" -> {
                /* Phase 23: Inline diff view untuk AI file edits.
                 * Wave-1/23: Resolve path via ToolExecutor sandbox (Ubuntu → rootfs /root). */
                val path = call.args["path"] ?: return
                val content = call.args["content"] ?: return
                try {
                    syncToolExecutorToSession(shellExecutors.find { it.id == activeExecutorId })
                    val file = toolExecutor.resolvePathForAccess(path)
                    val original = if (file.exists() && file.isFile) {
                        try { file.readText() } catch (e: Exception) { "" }
                    } else ""
                    if (original != content) {
                        /* Store resolved absolute path so Apply hits the same file. */
                        pendingDiff = Triple(file.absolutePath, original, content)
                    } else {
                        chatMessages.add(ChatMessage("assistant", "No changes needed for ${file.absolutePath}", false))
                        continueToolLoop("No changes needed for ${file.absolutePath}")
                    }
                } catch (e: SecurityException) {
                    chatMessages.add(ChatMessage("assistant", "Error: ${e.message}", false, isError = true))
                    continueToolLoop("Error: ${e.message}")
                } catch (e: Exception) {
                    chatMessages.add(ChatMessage("assistant", "Error resolving path: ${e.message}", false, isError = true))
                    continueToolLoop("Error resolving path: ${e.message}")
                }
            }
            call.tool.startsWith("mcp.") -> {
                /* Phase 23: MCP tool invocation. */
                val parts = call.tool.removePrefix("mcp.").split(".", limit = 2)
                if (parts.size == 2) {
                    val serverName = parts[0]
                    val toolName = parts[1]
                    /* Phase 24.5: Fix JSONObject(Map) crash — org.json doesn't have Map constructor.
                     * Build JSONObject manually dari call.args Map. */
                    val argsJson = org.json.JSONObject()
                    call.args.forEach { (k, v) -> argsJson.put(k, v) }
                    val args = argsJson.toString()
                    lifecycleScope.launch {
                        val result = mcpManager.invokeTool(serverName, toolName, args)
                        chatMessages.add(ChatMessage("assistant", "📋 MCP Result:\n$result", false))
                        continueToolLoop(result)
                    }
                }
            }
            else -> {
                val result = toolExecutor.execute(call)
                chatMessages.add(ChatMessage("assistant", "📋 Result:\n$result", false))
                continueToolLoop(result)
            }
        }
    }

    /* ─── Phase 23: Voice Input ─── */
    private fun startVoiceInput() {
        if (voiceInputManager.isAvailable()) {
            voiceInputLauncher.launch(voiceInputManager.createIntent())
        } else {
            Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_SHORT).show()
        }
    }

    /* ─── Phase 23: MCP Discovery ─── */
    private fun discoverMcpTools() {
        lifecycleScope.launch {
            val tools = mcpManager.discoverAllTools()
            mcpTools.clear()
            mcpTools.addAll(tools)
            if (tools.isNotEmpty()) {
                Toast.makeText(this@MainActivity, "Discovered ${tools.size} MCP tools", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /* ─── Phase 23: Agent Workflow Execution ─── */
    private suspend fun executeAgentWorkflow(workflow: AgentWorkflow) {
        val activeExecutor = shellExecutors.find { it.id == activeExecutorId } ?: return
        chatMessages.add(ChatMessage("assistant", "🤖 Starting workflow: ${workflow.name}", false))

        /* Phase 46 (Pilar 4): Track last step result untuk CONDITIONAL_STEP.
         * Upgrade dari string-matching di output (rapuh) ke exit-code (pasti). */
        var lastStepResult: MarkerExecutor.CommandResult? = null

        for (step in workflow.steps) {
            chatMessages.add(ChatMessage("assistant", "▶ Step: ${step.displayText}", false))
            when (step.type) {
                AgentStep.StepType.AI_STEP -> {
                    /* Phase 24: Tunggu AI selesai sebelum lanjut ke step berikutnya. */
                    while (isProcessingAI) { delay(100) }
                    handleAIPrompt(step.prompt)
                    /* Tunggu AI selesai sebelum next step. */
                    while (isProcessingAI) { delay(100) }
                }
                AgentStep.StepType.COMMAND_STEP -> {
                    /* Phase 46 (Pilar 4): Pakai MarkerExecutor dengan ExecutionOutcome.
                     * maxTimeoutMs dari step config (coerceAtLeast 60s), idleTimeoutMs 15s.
                     * Old code: executeCommand + delay — tidak tahu kapan command selesai,
                     * tidak tahu exit code, tidak tahu kalau command nunggu input. */
                    val outcome = markerExecutor.executeWithMarker(
                        activeExecutor, step.command,
                        maxTimeoutMs = step.timeoutMs.coerceAtLeast(60000),
                        idleTimeoutMs = 15000
                    )
                    when (outcome) {
                        is MarkerExecutor.ExecutionOutcome.Completed -> {
                            lastStepResult = outcome.result
                            val statusIcon = if (outcome.result.isSuccess) "✓" else "✗"
                            val outputDisplay = if (outcome.result.output.isBlank()) "(no output)" else outcome.result.output.take(300)
                            chatMessages.add(ChatMessage(
                                "assistant",
                                "$statusIcon Exit code: ${outcome.result.exitCode} (${outcome.result.executionTimeMs}ms)\n$outputDisplay",
                                false, isError = !outcome.result.isSuccess
                            ))
                            if (!outcome.result.isSuccess) {
                                chatMessages.add(ChatMessage(
                                    "assistant",
                                    "❌ Workflow dihentikan: Step gagal (exit code ${outcome.result.exitCode}).",
                                    false, isError = true
                                ))
                                return
                            }
                        }
                        is MarkerExecutor.ExecutionOutcome.PossiblyWaitingForInput -> {
                            val outputDisplay = if (outcome.partialOutput.isBlank()) "(no output)" else outcome.partialOutput.take(300)
                            chatMessages.add(ChatMessage(
                                "assistant",
                                "⚠️ Workflow dihentikan: Step kemungkinan menunggu input interaktif " +
                                "(idle 15s, elapsed ${outcome.elapsedMs}ms).\n$outputDisplay\n" +
                                "Periksa output dan beri arahan manual.",
                                false, isError = true
                            ))
                            return
                        }
                        is MarkerExecutor.ExecutionOutcome.TimedOut -> {
                            val outputDisplay = if (outcome.partialOutput.isBlank()) "(no output)" else outcome.partialOutput.take(300)
                            chatMessages.add(ChatMessage(
                                "assistant",
                                "⚠️ Workflow dihentikan: Step timeout (${step.timeoutMs}ms).\n$outputDisplay",
                                false, isError = true
                            ))
                            return
                        }
                    }
                }
                AgentStep.StepType.DELAY_STEP -> {
                    delay(step.timeoutMs)
                }
                AgentStep.StepType.CONDITIONAL_STEP -> {
                    /* Phase 46 (Pilar 4): Upgrade dari string-matching di output (rapuh)
                     * ke exit-code (pasti). CONDITIONAL_STEP jalan kalau step sebelumnya sukses.
                     *
                     * Old code: cari string step.prompt di output terminal → false positive
                     * (mis. "error" muncul di output command yang sebenarnya sukses).
                     * New code: cek lastStepResult?.isSuccess == true. */
                    val lastSucceeded = lastStepResult?.isSuccess == true
                    if (lastSucceeded) {
                        val outcome = markerExecutor.executeWithMarker(
                            activeExecutor, step.command,
                            maxTimeoutMs = step.timeoutMs.coerceAtLeast(60000),
                            idleTimeoutMs = 15000
                        )
                        when (outcome) {
                            is MarkerExecutor.ExecutionOutcome.Completed -> {
                                lastStepResult = outcome.result
                                val statusIcon = if (outcome.result.isSuccess) "✓" else "✗"
                                val outputDisplay = if (outcome.result.output.isBlank()) "(no output)" else outcome.result.output.take(300)
                                chatMessages.add(ChatMessage(
                                    "assistant",
                                    "$statusIcon Conditional step — Exit code: ${outcome.result.exitCode} (${outcome.result.executionTimeMs}ms)\n$outputDisplay",
                                    false, isError = !outcome.result.isSuccess
                                ))
                            }
                            is MarkerExecutor.ExecutionOutcome.PossiblyWaitingForInput -> {
                                chatMessages.add(ChatMessage(
                                    "assistant",
                                    "⚠️ Conditional step kemungkinan menunggu input. Workflow dihentikan.",
                                    false, isError = true
                                ))
                                return
                            }
                            is MarkerExecutor.ExecutionOutcome.TimedOut -> {
                                chatMessages.add(ChatMessage(
                                    "assistant",
                                    "⚠️ Conditional step timeout. Workflow dihentikan.",
                                    false, isError = true
                                ))
                                return
                            }
                        }
                    } else {
                        chatMessages.add(ChatMessage(
                            "assistant",
                            "⏭️ Conditional step di-skip (step sebelumnya tidak sukses).",
                            false
                        ))
                    }
                }
            }
        }
        chatMessages.add(ChatMessage("assistant", "✅ Workflow '${workflow.name}' completed", false))
    }

    /* ─── Phase 47 (Bagian 2): Agent Mode ─── */

    /**
     * Mulai Agent task otonom.
     * AI akan bekerja sampai selesai (atau macet) — user tidak perlu balas chat tiap langkah.
     */
    private fun startAgentTask(goal: String, useUbuntu: Boolean) {
        if (goal.isBlank()) return
        if (agentRunning) return

        agentEvents.clear()
        agentRunning = true
        agentPaused = false
        agentPendingClarification = null
        agentLastGoal = goal
        agentLastUseUbuntu = useUbuntu

        /* Phase 52 + Wave-1: Entire flow (incl. Ubuntu tab wait) runs on a coroutine —
         * never Thread.sleep on the main thread. */
        agentJob = lifecycleScope.launch {
            try {
                val session = if (useUbuntu) {
                    var s = shellExecutors.find { it.sessionType == "ubuntu" && it.isAlive }
                    if (s == null) {
                        createUbuntuTab()
                        /* Wait up to ~4s for proot session readiness without blocking UI. */
                        repeat(40) {
                            kotlinx.coroutines.delay(100)
                            s = shellExecutors.find { it.sessionType == "ubuntu" && it.isAlive }
                            if (s != null) return@repeat
                        }
                    }
                    s
                } else {
                    shellExecutors.find { it.id == activeExecutorId }
                }

                if (session == null) {
                    agentEvents.add(
                        AgentTaskRunner.AgentEvent.StoppedForSafety(
                            "Tidak ada session aktif. Buka tab terminal dulu" +
                                if (useUbuntu) " (atau install Ubuntu)." else "."
                        )
                    )
                    return@launch
                }

                /* Wave-23: Point AI file tools + active tab at the Agent session (esp. Ubuntu). */
                activeExecutorId = session.id
                syncToolExecutorToSession(session)

                agentTaskRunner.run(
                    goal = goal,
                    session = session,
                    settings = aiSettings,
                    maxIterations = 40,
                    approve = { call, reason ->
                        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
                        agentApprovalDeferred = deferred
                        pendingAgentApproval = call to reason
                        deferred.await()
                    },
                    events = { event ->
                        agentEvents.add(event)
                        /* Wave-6: Capture clarification for UI answer form. */
                        if (event is AgentTaskRunner.AgentEvent.NeedsClarification) {
                            agentPendingClarification = event.question
                        }
                    }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                agentEvents.add(AgentTaskRunner.AgentEvent.StoppedForSafety("Dihentikan oleh user"))
            } catch (e: Exception) {
                agentEvents.add(AgentTaskRunner.AgentEvent.StoppedForSafety("Exception: ${e.message}"))
            } finally {
                agentRunning = false
                agentPaused = false
            }
        }
    }

    /** Wave-6: Continue agent after user answers NeedsClarification. */
    private fun continueAgentWithClarification(answer: String) {
        val q = agentPendingClarification
        val baseGoal = agentLastGoal.ifBlank { "Continue previous task" }
        val enriched = buildString {
            append(baseGoal)
            append("\n\n[User clarification]\n")
            if (!q.isNullOrBlank()) append("Question was: $q\n")
            append("Answer: $answer")
        }
        agentPendingClarification = null
        startAgentTask(enriched, agentLastUseUbuntu)
    }

    /* ─── Phase 19: AI Provider Model Fetcher ─── */

    /** Fetch daftar model dari provider saat ini. */
    private fun fetchModels() {
        if (isLoadingModels) return
        if (aiSettings.baseUrl.isBlank()) {
            modelsFetchError = "Base URL kosong. Pilih provider dulu."
            return
        }
        isLoadingModels = true
        modelsFetchError = null
        availableModels.clear()
        lifecycleScope.launch {
            val result = ModelFetcher.fetchModels(aiSettings)
            isLoadingModels = false
            result.onSuccess { models ->
                availableModels.addAll(models)
                if (models.isEmpty()) {
                    modelsFetchError = "Tidak ada model di endpoint /models"
                }
            }.onFailure { e ->
                modelsFetchError = e.message ?: "Gagal fetch models"
            }
        }
    }

    /** Pilih model dari daftar, update settings. */
    private fun selectModel(model: ModelInfo) {
        val newSettings = aiSettings.copy(
            modelName = model.id,
            supportsVision = model.supportsVision
        )
        saveAISettings(newSettings)
    }

    /* ─── Phase 19: Image Vision ─── */

    /** Buka image picker untuk attach ke pesan AI berikutnya. */
    private fun attachImage() {
        if (!aiSettings.supportsVision) {
            Toast.makeText(
                this,
                "Model saat ini (${aiSettings.modelName}) mungkin tidak support image. " +
                "Pilih model vision (gpt-4o, gemini-1.5, claude-3) di Settings.",
                Toast.LENGTH_LONG
            ).show()
        }
        imagePickerLauncher.launch("image/*")
    }

    /** Hapus image attachment by index. */
    private fun removeImage(index: Int) {
        if (index in pendingImages.indices) {
            pendingImages.removeAt(index)
        }
    }

    /* ─── Phase 19: Workspace Sessions ─── */

    /**
     * Parse working dir dari prompt shell (best-effort).
     * Format prompt: "tunnel@android:/path/to/dir$ "
     */
    /**
     * Wave-7: Parse cwd from local, Ubuntu, or generic user@host prompts.
     * Formats: tunnel@android:/path$  |  root@ubuntu:~/proj#  |  user@host:/var$
     */
    private fun parseWorkingDir(prompt: String): String {
        val patterns = listOf(
            Regex("""tunnel@android:([^\s\$#]+)[\$#]\s*$"""),
            Regex("""[\w.-]+@[\w.-]+:([^\s\$#]+)[\$#]\s*$""")
        )
        for (regex in patterns) {
            val match = regex.find(prompt.trim()) ?: continue
            var path = match.groupValues[1].trim()
            if (path.startsWith("~")) {
                /* Best-effort: leave ~ for shell; restore uses cd as-is. */
            }
            return path
        }
        return ""
    }

    /** Save workspace session. */
    private fun saveWorkspace(name: String): Boolean {
        val workingDirs = shellExecutors.map { exec ->
            parseWorkingDir(exec.currentPrompt)
        }
        val ok = workspaceManager.saveSession(name, shellExecutors.size, workingDirs)
        if (ok) {
            workspaceSessions.clear()
            workspaceSessions.addAll(workspaceManager.sessions)
            Toast.makeText(this, "Session '$name' tersimpan (${shellExecutors.size} tab)", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Gagal simpan (nama duplikat atau max 20 session)", Toast.LENGTH_SHORT).show()
        }
        return ok
    }

    /** Restore workspace session: tutup semua tab tanpa auto-create, buat sesuai session, cd ke working dir. */
    private suspend fun restoreWorkspace(session: WorkspaceSession) {
        /* Tutup semua tab tanpa trigger auto-create (closeTab auto-create kalau list kosong).
         * Destroy semua executors dulu, baru clear list. */
        shellExecutors.toList().forEach { it.destroy() }
        shellExecutors.clear()
        activeExecutorId = 0
        /* Buat tab sesuai session. */
        for (i in 0 until session.tabCount) {
            createNewTab()
            /* Kirim cd ke working dir jika ada. */
            val dir = session.workingDirs.getOrNull(i)
            if (!dir.isNullOrBlank()) {
                delay(150) /* Tunggu shell siap. */
                /* BUG-17 fix: Quote path agar path dengan spasi tidak gagal. */
                shellExecutors.lastOrNull()?.executeCommand("cd \"${dir.replace("\"", "\\\"")}\"")
            }
        }
        Toast.makeText(this, "Session '${session.name}' restored (${session.tabCount} tab)", Toast.LENGTH_SHORT).show()
    }

    /** Delete workspace session. */
    private fun deleteWorkspace(name: String) {
        if (workspaceManager.deleteSession(name)) {
            workspaceSessions.clear()
            workspaceSessions.addAll(workspaceManager.sessions)
        }
    }

    /* ─── Phase 19: File Explorer ─── */

    /** Buka file dari explorer di TunnelEditor. */
    private fun openFileFromExplorer(file: File) {
        if (file.isFile) {
            editingFile = file.absolutePath
            shellExecutors.find { it.id == activeExecutorId }?.let { exec ->
                exec.emulator.process("\u001B[32m[Editor] Membuka ${file.name}...\u001B[0m\n")
                exec.triggerScreenUpdate()
            }
        }
    }

    /** Cd ke folder dari explorer di terminal aktif. */
    private fun cdFromExplorer(dir: File) {
        /* B-19 fix: Quote path agar path dengan spasi tidak gagal (sama dengan BUG-17 fix). */
        val safePath = dir.absolutePath.replace("\"", "\\\"")
        shellExecutors.find { it.id == activeExecutorId }?.executeCommand("cd \"$safePath\"")
    }

    /* BUG-22 fix: Move registerForActivityResult ke property kelas (bukan di method body).
     * Old code: registerForActivityResult di dalam if di method — IllegalStateException risk. */
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun loadAISettings() {
        /* Phase 41 fix (CRIT-01): Migrasi apiKey dari plaintext prefs lama ke encrypted prefs.
         * Setelah migrasi, apiKey disimpan di EncryptedSharedPreferences (AES256-GCM). */
        SecureStorage.migrateAICredentials(this)

        /* Non-sensitive settings (provider, baseUrl, model, dll) tetap di plaintext prefs
         * untuk kompatibilitas + performance. Hanya apiKey yang dipindah ke encrypted. */
        val prefs = getSharedPreferences("TunnelAIPrefs", Context.MODE_PRIVATE)
        /* C3 fix: Wrap getDouble dalam try-catch — ClassCastException jika preference corrupt
         * atau disimpan sebagai tipe lain oleh versi lama. */
        val temperature = try { prefs.getDouble("temperature", 0.2) } catch (_: Exception) { 0.2 }
        /* Wave-2: Secure storage fail-closed — load key only if encryption works. */
        val apiKey = try {
            SecureStorage.getAIPrefs(this).getString("apiKey", "") ?: ""
        } catch (e: Exception) {
            Log.e("MainActivity", "Secure storage unavailable on load: ${e.message}")
            Toast.makeText(
                this,
                "Secure storage unavailable — API key cannot be loaded. ${e.message}",
                Toast.LENGTH_LONG
            ).show()
            ""
        }
        aiSettings = AISettings(
            providerName = prefs.getString("providerName", "OpenAI") ?: "OpenAI",
            baseUrl = prefs.getString("baseUrl", "https://api.openai.com/v1") ?: "https://api.openai.com/v1",
            apiKey = apiKey,
            modelName = prefs.getString("modelName", "gpt-4o-mini") ?: "gpt-4o-mini",
            temperature = temperature,
            maxTokens = prefs.getInt("maxTokens", 2000),
            requestTimeoutMs = prefs.getInt("requestTimeoutMs", 30000),
            supportsVision = prefs.getBoolean("supportsVision", false),
            supportsToolCalling = prefs.getBoolean("supportsToolCalling", false),
            apiStyle = prefs.getString("apiStyle", "openai") ?: "openai"
        )
    }

    private fun saveAISettings(newSettings: AISettings) {
        /* Wave-8: Validate base URL before persisting (allow blank Custom draft). */
        var toSave = newSettings
        if (newSettings.baseUrl.isNotBlank()) {
            val v = UrlValidator.validateAiBaseUrl(newSettings.baseUrl)
            if (!v.ok) {
                Toast.makeText(this, "Base URL: ${v.message}", Toast.LENGTH_LONG).show()
                return
            }
            if (v.message.isNotBlank() && v.message != newSettings.baseUrl.trimEnd('/')) {
                toSave = newSettings.copy(baseUrl = v.message)
            }
        }
        aiSettings = toSave
        /* Phase 41 fix (CRIT-01): apiKey disimpan di encrypted prefs, sisanya plaintext. */
        val prefs = getSharedPreferences("TunnelAIPrefs", Context.MODE_PRIVATE).edit()
        prefs.putString("providerName", toSave.providerName)
        prefs.putString("baseUrl", toSave.baseUrl)
        prefs.putString("modelName", toSave.modelName)
        prefs.putDouble("temperature", toSave.temperature)
        prefs.putInt("maxTokens", toSave.maxTokens)
        prefs.putInt("requestTimeoutMs", toSave.requestTimeoutMs)
        prefs.putBoolean("supportsVision", toSave.supportsVision)
        prefs.putBoolean("supportsToolCalling", toSave.supportsToolCalling)
        prefs.putString("apiStyle", toSave.apiStyle)
        prefs.apply()

        try {
            val securePrefs = SecureStorage.getAIPrefs(this).edit()
            securePrefs.putString("apiKey", toSave.apiKey)
            securePrefs.apply()
        } catch (e: Exception) {
            Log.e("MainActivity", "Cannot save API key — secure storage unavailable: ${e.message}")
            Toast.makeText(
                this,
                "API key NOT saved: secure storage unavailable. ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /* SharedPreferences helper untuk double karena tidak ada putDouble bawaan.
     * Helper because SharedPreferences doesn't have putDouble natively. */
    private fun android.content.SharedPreferences.Editor.putDouble(key: String, value: Double): android.content.SharedPreferences.Editor {
        return putLong(key, java.lang.Double.doubleToRawLongBits(value))
    }
    private fun android.content.SharedPreferences.getDouble(key: String, default: Double): Double {
        return java.lang.Double.longBitsToDouble(getLong(key, java.lang.Double.doubleToRawLongBits(default)))
    }

    private fun saveSnippet(title: String, command: String) {
        val ok = snippetManager.add(title, command)
        if (!ok) {
            Toast.makeText(this, "Maksimal 100 workflow tercapai", Toast.LENGTH_SHORT).show()
            return
        }
        snippetsState.clear(); snippetsState.addAll(snippetManager.snippets)
    }

    private fun deleteSnippet(id: Long) {
        snippetManager.remove(id)
        snippetsState.clear(); snippetsState.addAll(snippetManager.snippets)
    }

    private suspend fun createNewTab() {
        val newExecutor = ShellExecutor(themeHolder, this)
        /* Phase 24.5: Add to list BEFORE start() agar Compose bisa observe.
         * Old code: start() sebelum add() — jika start() lambat, UI render null.
         * Fix: add first, then start, then set active. */
        shellExecutors.add(newExecutor)
        activeExecutorId = newExecutor.id
        /* Wave-8: Seed persisted command history into new tab. */
        CommandHistoryStore.seedInto(newExecutor, this)
        syncToolExecutorToSession(newExecutor)
        newExecutor.start()

        /* Tampilkan MOTD dinamis. */
        newExecutor.emulator.process(SystemInfo.buildMotd(this))
        newExecutor.triggerScreenUpdate()
    }

    /**
     * Phase 21: Buat tab SSH baru.
     * Create new SSH tab with given connection config.
     */
    private suspend fun createSshTab(config: SshConnectionConfig) {
        /* Phase 41 fix (CRIT-02): Pass hostKeyChangeCallback supaya user dapat dialog
         * blocking saat fingerprint server berubah (potensi MITM). */
        val sshExecutor = SshShellExecutor(
            themeHolder = themeHolder,
            config = config,
            context = this,
            hostKeyChangeCallback = { oldKey, newKey ->
                /* Blocking call — wait for user decision via suspendCancellableCoroutine..
                 * Karena callback ini dipanggil dari suspend context (start()), kita bisa
                 * suspend di sini sampai user tap tombol di dialog. */
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                        _sshHostKeyDialogState.value = SshHostKeyDialogState(
                            host = "${config.host}:${config.port}",
                            oldFingerprint = oldKey,
                            newFingerprint = newKey,
                            onResolve = { approved ->
                                if (cont.isActive) {
                                    cont.resume(approved) {}
                                }
                            }
                        )
                    }
                }
            }
        )
        shellExecutors.add(sshExecutor)
        activeExecutorId = sshExecutor.id
        CommandHistoryStore.seedInto(sshExecutor, this)
        syncToolExecutorToSession(sshExecutor)
        sshExecutor.start()
    }

    /**
     * Phase 38 (proot/Ubuntu): Buat tab Ubuntu baru. Kalau belum terinstal,
     * tampilkan dialog instalasi (akan auto-createUbuntuTab() setelah sukses).
     *
     * Phase 41 fix (CRIT-04): Di flavor "playstore", fitur proot dinonaktifkan
     * (BuildConfig.ENABLE_PROOT = false). Tombol 🐧 disembunyikan di TabBar,
     * dan fungsi ini return early dengan toast info.
     *
     * Retry logic: kalau sesi mati dalam <2 detik (indikasi SECCOMP issue),
     * destroy + retry sekali dengan disableSeccomp=true. Preferensi disimpan
     * supaya percobaan berikutnya pakai flag yang sama.
     */
    /**
     * Wave-23: Keep ToolExecutor path sandbox aligned with the active tab.
     * Without this, write_file on Ubuntu tab still targets Android workspace.
     */
    private fun syncToolExecutorToSession(session: TerminalSession?) {
        if (session == null) return
        val resolver = SessionTargetResolver(
            sessionType = session.sessionType,
            workspaceRoot = toolExecutor.workspaceRootFile(),
            rootfsDir = if (session.sessionType == "ubuntu" && ::prootBootstrap.isInitialized) {
                prootBootstrap.rootfsDir
            } else null
        )
        toolExecutor.setSessionTargetResolver(resolver)
        if (session is SshShellExecutor) {
            toolExecutor.setSshExecutor(session)
        } else {
            toolExecutor.setSshExecutor(null)
        }
        permissionManager.setActiveSession(session.id)
    }

    private suspend fun createUbuntuTab() {
        /* Phase 41 fix (CRIT-04): Nonaktifkan fitur Ubuntu di playstore flavor. */
        if (!com.tunnel.terminal.BuildConfig.ENABLE_PROOT) {
            Toast.makeText(
                this,
                "Fitur Linux Environment tidak tersedia di build ini (Play Store policy). " +
                "Gunakan build Full dari GitHub Releases.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (!prootBootstrap.isInstalled) {
            showUbuntuInstallDialog = true
            return
        }

        // Cek preferensi SECCOMP dari session sebelumnya.
        val prefs = getSharedPreferences("TunnelLinux", Context.MODE_PRIVATE)
        val useNoSeccomp = prefs.getBoolean("proot_no_seccomp", false)

        val executor = ProotShellExecutor(
            themeHolder = themeHolder,
            bootstrap = prootBootstrap,
            disableSeccomp = useNoSeccomp
        )
        shellExecutors.add(executor)
        activeExecutorId = executor.id
        CommandHistoryStore.seedInto(executor, this)
        syncToolExecutorToSession(executor)
        executor.start()

        // Beri MOTD khusus Ubuntu.
        executor.emulator.process(
            "\u001B[32m┌─ Ubuntu 24.04 (proot) ─────────────────────────────┐\u001B[0m\n" +
            "\u001B[32m│ Linux environment via proot — no root required     │\u001B[0m\n" +
            "\u001B[32m│ apt update && apt install <pkg> · HOME=/root       │\u001B[0m\n" +
            "\u001B[32m│ AI write_file → /root/ · workspace → /mnt/workspace│\u001B[0m\n" +
            "\u001B[32m└────────────────────────────────────────────────────┘\u001B[0m\n\n"
        )
        executor.triggerScreenUpdate()

        // Phase 39: Deteksi early death (SECCOMP issue) → retry sekali dengan flag.
        Thread {
            try {
                Thread.sleep(2500)
                if (!executor.isAlive) {
                    Log.w("MainActivity", "Sesi Ubuntu mati prematur — retry dengan PROOT_NO_SECCOMP=1")
                    // Hapus executor yang mati.
                    shellExecutors.removeAll { it.id == executor.id }
                    if (activeExecutorId == executor.id) {
                        activeExecutorId = shellExecutors.firstOrNull()?.id ?: 0
                    }
                    // Simpan preferensi supaya percobaan berikutnya pakai flag ini.
                    prefs.edit().putBoolean("proot_no_seccomp", true).apply()
                    // Retry.
                    lifecycleScope.launch {
                        val retryExecutor = ProotShellExecutor(
                            themeHolder = themeHolder,
                            bootstrap = prootBootstrap,
                            disableSeccomp = true
                        )
                        shellExecutors.add(retryExecutor)
                        activeExecutorId = retryExecutor.id
                        syncToolExecutorToSession(retryExecutor)
                        retryExecutor.start()
                        retryExecutor.emulator.process(
                            "\u001B[33m[RETRY] Sesi sebelumnya mati prematur — menggunakan PROOT_NO_SECCOMP=1.\u001B[0m\n\n"
                        )
                        retryExecutor.triggerScreenUpdate()
                    }
                }
            } catch (_: InterruptedException) {}
        }.apply { isDaemon = true; start() }
    }

    /**
     * Phase 39 (proot/Ubuntu): Install Ubuntu rootfs. Panggil dari UI dialog.
     * Reset state, jalankan install di IO dispatcher, update progress.
     */
    private fun startUbuntuInstall() {
        if (ubuntuInstalling) return
        ubuntuInstalling = true
        ubuntuInstallError = null
        ubuntuInstallStage = "Memulai instalasi"
        ubuntuInstallPercent = 0

        /*
         * Wave-22 CRITICAL FIX: install() MUST run on Dispatchers.IO.
         * OLD BUG: withContext(NonCancellable) alone stayed on Main (lifecycleScope).
         * HttpURLConnection on Main → NetworkOnMainThreadException / ANR / "download always fails".
         * FIX: Dispatchers.IO + NonCancellable; hop to Main only for Compose state updates.
         */
        val mainHandler = android.os.Handler(mainLooper)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO + NonCancellable) {
                    prootBootstrap.install(ProotBootstrap.ProgressListener { stage, percent ->
                        /* Never touch Compose state from the IO thread. */
                        mainHandler.post {
                            ubuntuInstallStage = stage
                            ubuntuInstallPercent = percent
                        }
                    })
                }
                ubuntuInstalling = false
                showUbuntuInstallDialog = false
                Toast.makeText(this@MainActivity, "Ubuntu siap digunakan", Toast.LENGTH_SHORT).show()
                createUbuntuTab()
            } catch (e: Exception) {
                Log.e("MainActivity", "Ubuntu install gagal: ${e.message}", e)
                ubuntuInstalling = false
                ubuntuInstallError = e.message ?: "Unknown error"
            }
        }
    }

    /**
     * Phase 39 (proot/Ubuntu): Uninstall Linux environment (bebaskan storage).
     * Tutup semua tab Ubuntu dulu sebelum uninstall supaya fd tidak leak.
     */
    private fun uninstallUbuntu() {
        lifecycleScope.launch {
            // Tutup semua tab Ubuntu.
            val ubuntuTabs = shellExecutors.filter { it.sessionType == "ubuntu" }
            ubuntuTabs.forEach { tab ->
                Thread { tab.destroy() }.start()
            }
            shellExecutors.removeAll { it.sessionType == "ubuntu" }
            if (activeExecutorId == 0 || shellExecutors.none { it.id == activeExecutorId }) {
                activeExecutorId = shellExecutors.firstOrNull()?.id ?: 0
            }
            if (shellExecutors.isEmpty()) {
                createNewTab()
            }

            // Hapus rootfs.
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                prootBootstrap.uninstall()
            }
            // Reset preferensi SECCOMP.
            getSharedPreferences("TunnelLinux", Context.MODE_PRIVATE).edit().clear().apply()
            Toast.makeText(this@MainActivity, "Linux environment dihapus", Toast.LENGTH_SHORT).show()
        }
    }

    private fun closeTab(id: Int) {
        /* Phase 25: Fix ANR — destroy() di background thread (was main thread = 350ms block).
         * Old code: destroy() blocking (Thread.sleep + join) di main thread → ANR. */
        val executor = shellExecutors.find { it.id == id }
        if (executor != null) {
            Thread { executor.destroy() }.start()
        }
        shellExecutors.removeAll { it.id == id }
        /* Wave-10: Drop custom label when tab closes. */
        tabLabels.remove(id)
        if (renameTabId == id) {
            renameTabId = null
            renameTabDraft = ""
        }
        /* Phase 24.5: Fix activeExecutorId=0 invalid state.
         * Old code: set to 0 if no tabs, but 0 is not a valid session id.
         * Fix: set to first available tab, atau biarkan 0 sementara (createNewTab akan set). */
        if (activeExecutorId == id) {
            activeExecutorId = shellExecutors.firstOrNull()?.id ?: 0
        }
        if (shellExecutors.isEmpty()) {
            /* createNewTab akan set activeExecutorId ke id baru. */
            lifecycleScope.launch { createNewTab() }
        }
        /* Phase 24.5: Clear block mode jika tab ditutup (avoid stale blocks). */
        if (blockMode) {
            blockManager.clear()
            blockMode = false
        }
    }

    /** Wave-10: Default tab label from session type + 1-based index. */
    private fun defaultTabLabel(executor: TerminalSession, index1Based: Int): String {
        return when (executor.sessionType) {
            "ubuntu" -> "Ubuntu $index1Based"
            "ssh" -> "SSH $index1Based"
            else -> "Tab $index1Based"
        }
    }

    /** Wave-10: Copy terminal output (last command if known, else clean buffer tail). */
    private fun copyLastOutput(executor: TerminalSession): String {
        val last = executor.lastCommandOutput.value.trim()
        val text = if (last.isNotEmpty()) last else executor.getCleanOutput().takeLast(12_000)
        if (text.isBlank()) {
            Toast.makeText(this, "No output to copy", Toast.LENGTH_SHORT).show()
            return "empty"
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("terminal-output", text))
        Toast.makeText(this, "Copied ${text.length} chars", Toast.LENGTH_SHORT).show()
        return "copied ${text.length} chars"
    }

    /** Wave-10: Handle `bookmark …` pseudo-commands. Returns message for terminal. */
    private fun handleBookmarkCommand(raw: String, executor: TerminalSession): String {
        val parts = raw.trim().split(Regex("\\s+"), limit = 4)
        val sub = parts.getOrNull(1)?.lowercase() ?: "list"
        return when (sub) {
            "list", "ls" -> BookmarkStore.formatList(this)
            "add" -> {
                val name = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
                    ?: return "Usage: bookmark add <name> [path]"
                val path = parts.getOrNull(3)?.takeIf { it.isNotBlank() }
                    ?: File(filesDir, "home").absolutePath
                BookmarkStore.add(this, name, path)
                "Bookmarked: $name → $path"
            }
            "go", "cd" -> {
                val key = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
                    ?: return "Usage: bookmark go <name|index>"
                val bm = key.toIntOrNull()?.let { BookmarkStore.getByIndex(this, it) }
                    ?: BookmarkStore.list(this).firstOrNull { it.name == key || it.path == key }
                    ?: return "Bookmark not found: $key"
                executor.executeCommand("cd ${shellQuote(bm.path)}")
                "cd ${bm.path}"
            }
            "remove", "rm", "delete" -> {
                val key = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
                    ?: return "Usage: bookmark remove <name|path|index>"
                val byIndex = key.toIntOrNull()?.let { BookmarkStore.getByIndex(this, it) }
                val target = byIndex?.name ?: key
                if (BookmarkStore.remove(this, target) ||
                    (byIndex != null && BookmarkStore.remove(this, byIndex.path))
                ) {
                    "Removed bookmark: $target"
                } else {
                    "Bookmark not found: $key"
                }
            }
            else -> "Usage: bookmark list|add <name> [path]|go <name|n>|remove <name|n>"
        }
    }

    private fun shellQuote(path: String): String {
        if (path.none { it.isWhitespace() || it == '\'' || it == '"' || it == '$' || it == '`' }) {
            return path
        }
        return "'" + path.replace("'", "'\\''") + "'"
    }

    override fun onDestroy() {
        super.onDestroy()
        /* Wave-1: Sessions live on TunnelApp so they survive Activity recreate.
         * Only tear them down when the Activity is actually finishing (user quit /
         * process end path), not on configuration-driven recreation. */
        if (!isFinishing) return
        /* Phase 20: destroy() blocks (Thread.sleep + join). Run on background thread
         * to avoid ANR. Old code: forEach on main thread = 5 tabs × 400ms = 2s ANR. */
        Thread {
            shellExecutors.toList().forEach { it.destroy() }
        }.start()
        stopService(Intent(this, TerminalForegroundService::class.java))
    }

    /**
     * Wave-20/21: True when AI side panel is open (volume history disabled so media works).
     * Named historically aiDrawerOpen; now tracks side panel, not ModalNavigationDrawer.
     */
    private var aiDrawerOpen by mutableStateOf(false)

    private fun isTerminalHistoryFocused(): Boolean =
        editingFile == null &&
            !showFileExplorer &&
            !showWorkspaceDrawer &&
            !showCommandPalette &&
            !showSshDialog &&
            !aiPanelOpen &&
            renameTabId == null

    /** Wave-21: Open AI copilot as right side panel (terminal stays visible). */
    private fun openAiPanel(tab: Int = 0) {
        chatInitialTab = tab
        aiPanelOpen = true
        aiDrawerOpen = true
        getSharedPreferences("TunnelUI", Context.MODE_PRIVATE)
            .edit().putBoolean("aiPanelOpen", true).apply()
    }

    private fun closeAiPanel() {
        aiPanelOpen = false
        aiDrawerOpen = false
        getSharedPreferences("TunnelUI", Context.MODE_PRIVATE)
            .edit().putBoolean("aiPanelOpen", false).apply()
    }

    private fun toggleAiPanel(tab: Int = 0) {
        if (aiPanelOpen) closeAiPanel() else openAiPanel(tab)
    }

    /* ─── Volume key command history navigation (per-active-executor) ─── */
    /* Phase 20 + Wave-20: Only intercept when terminal is the focused surface. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isTerminalHistoryFocused()) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                navigateHistory(forward = false); return true
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                navigateHistory(forward = true); return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (isTerminalHistoryFocused()) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun navigateHistory(forward: Boolean) {
        val activeExecutor = shellExecutors.find { it.id == activeExecutorId } ?: return
        val history = activeExecutor.commandHistory
        if (history.isEmpty()) return

        if (forward) {
            if (activeExecutor.historyIndex > 0) activeExecutor.historyIndex--
            else if (activeExecutor.historyIndex == 0) activeExecutor.historyIndex = -1
        } else {
            if (activeExecutor.historyIndex < history.size - 1) activeExecutor.historyIndex++
        }

        /* Clear current line (Ctrl+U) lalu tulis history. */
        activeExecutor.writeRaw("\u0015")
        activeExecutor.currentCommandBuffer = ""
        if (activeExecutor.historyIndex != -1) {
            val cmd = history[history.size - 1 - activeExecutor.historyIndex]
            activeExecutor.currentCommandBuffer = cmd
            activeExecutor.writeRaw(cmd)
            /* Wave-12: Keep transparent IME field aligned with recalled history. */
            syncImeLine(cmd)
        } else {
            syncImeLine("")
        }
    }

    /** Wave-12: Set both IME field trackers (and keep them equal). */
    private fun syncImeLine(text: String) {
        imeFieldText = text
        imeFieldLast = text
    }

    private fun clearImeLine() {
        imeFieldText = ""
        imeFieldLast = ""
    }

    /** Wave-14: Open http(s) URL in external browser. */
    private fun openExternalUrl(url: String) {
        if (!UrlOpenUtils.isSafeHttpUrl(url)) {
            Toast.makeText(this, "URL not allowed: $url", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open URL: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** Wave-14: Dead-session overlay text by session type. */
    private fun deadSessionLabel(session: TerminalSession): String = when (session.sessionType) {
        "ssh" -> "SSH disconnected.\nTap to reconnect\n(history preserved)."
        "ubuntu" -> "Ubuntu session exited.\nTap to restart\n(history preserved)."
        else -> "Session exited.\nTap to restart\n(history preserved)."
    }

    /* ─── Compose UI ─── */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun TerminalApp() {
        val scope = rememberCoroutineScope()

        var isCtrlActive by remember { mutableStateOf(false) }
        var isAltActive by remember { mutableStateOf(false) }

        /* Phase 24.5: Process pending voice text — open side panel + AI prompt. */
        val voiceText by _pendingVoiceText
        LaunchedEffect(voiceText) {
            if (voiceText.isNotBlank()) {
                openAiPanel(0)
                handleAIPrompt(voiceText)
                _pendingVoiceText.value = ""
            }
        }

        /* Wave-21: Auto-open side panel only when work *starts* (not if user closes mid-run). */
        var wasAiWorking by remember { mutableStateOf(false) }
        LaunchedEffect(isProcessingAI, autoPilotRunning, agentRunning) {
            val working = isProcessingAI || autoPilotRunning || agentRunning
            if (working && !wasAiWorking) {
                openAiPanel(0)
            }
            wasAiWorking = working
        }

        /* Back button: close AI panel / editor first. */
        BackHandler(enabled = editingFile != null || aiPanelOpen) {
            when {
                editingFile != null -> editingFile = null
                aiPanelOpen -> closeAiPanel()
            }
        }

        if (editingFile != null) {
            TunnelEditorDialog(
                filePath = editingFile!!,
                onDismiss = {
                    editingFile = null
                    shellExecutors.find { it.id == activeExecutorId }?.let { exec ->
                        exec.emulator.process("\u001B[2K\r\u001B[32m[Editor ditutup]\u001B[0m\n")
                        exec.triggerScreenUpdate()
                    }
                },
                theme = currentTheme
            )
        }

        /* Phase 22: Command Palette (Ctrl+K). */
        if (showCommandPalette) {
            val paletteItems = buildList {
                /* AI actions. */
                add(PaletteItem("ai_explain", "Ask AI to explain last output", "AI", Icons.Default.Psychology, PaletteCategory.AI) {
                    openAiPanel(0)
                    aiJob?.cancel()
                    aiJob = scope.launch { handleAIPrompt("Jelaskan output terminal terakhir.") }
                })
                add(PaletteItem("ai_fix", "Ask AI to fix errors", "AI", Icons.Default.Build, PaletteCategory.AI) {
                    openAiPanel(0)
                    aiJob?.cancel()
                    aiJob = scope.launch { handleAIPrompt("Perbaiki error di terminal.") }
                })
                add(PaletteItem("ai_open_chat", "Open AI chat (side panel)", "AI", Icons.Default.Chat, PaletteCategory.AI) {
                    openAiPanel(0)
                })
                add(PaletteItem("ai_autopilot", "Open AI chat (Auto-Pilot)", "AI", Icons.Default.SmartToy, PaletteCategory.AI) {
                    openAiPanel(0)
                })
                add(PaletteItem("ai_toggle_panel", "Toggle AI side panel", "AI", Icons.Default.Chat, PaletteCategory.AI) {
                    toggleAiPanel(0)
                })
                /* Navigation. */
                add(PaletteItem("new_tab", "New tab", "Navigation", Icons.Default.Add, PaletteCategory.NAVIGATION) { lifecycleScope.launch { createNewTab() } })
                add(PaletteItem("close_tab", "Close current tab", "Navigation", Icons.Default.Close, PaletteCategory.NAVIGATION) { closeTab(activeExecutorId) })
                add(PaletteItem("toggle_split", "Toggle split pane", "Navigation", Icons.Default.ViewColumn, PaletteCategory.NAVIGATION) {
                    /* Wave-20: Same create-tab logic as TabBar (was flag-only → empty pane). */
                    splitMode = !splitMode
                    if (splitMode) {
                        val otherTab = shellExecutors.firstOrNull { it.id != activeExecutorId }
                        if (otherTab != null) {
                            splitPaneId = otherTab.id
                        } else {
                            scope.launch {
                                createNewTab()
                                val oldTab = shellExecutors.firstOrNull { it.id != activeExecutorId }
                                if (oldTab != null) splitPaneId = oldTab.id
                            }
                        }
                    }
                })
                add(PaletteItem("toggle_block", "Toggle block mode", "Navigation", Icons.Default.ViewModule, PaletteCategory.NAVIGATION) {
                    blockMode = !blockMode
                    if (blockMode) {
                        shellExecutors.find { it.id == activeExecutorId }?.let { blockManager.parseFromOutput(it.getCleanOutput()) }
                    }
                })
                /* Settings. */
                add(PaletteItem("open_settings", "Open AI Settings", "Setting", Icons.Default.Settings, PaletteCategory.SETTING) {
                    openAiPanel(2)
                })
                add(PaletteItem("open_file_explorer", "Open File Explorer", "Setting", Icons.Default.Folder, PaletteCategory.SETTING) { showFileExplorer = true })
                add(PaletteItem("open_workspace", "Workspace Sessions", "Setting", Icons.Default.Save, PaletteCategory.SETTING) { showWorkspaceDrawer = true })
                add(PaletteItem("open_ssh", "SSH Connect", "Setting", Icons.Default.Cloud, PaletteCategory.SETTING) { showSshDialog = true })
                /* Phase 38 (proot/Ubuntu): Open Ubuntu / Install Linux environment. */
                add(PaletteItem("open_ubuntu", "Ubuntu (Linux Environment)", "Setting", Icons.Default.Terminal, PaletteCategory.SETTING) { lifecycleScope.launch { createUbuntuTab() } })
                /* Phase 39 (proot/Ubuntu): Manage install (uninstall if installed). */
                add(PaletteItem("manage_ubuntu", "Manage Linux Environment", "Setting", Icons.Default.Build, PaletteCategory.SETTING) { showUbuntuInstallDialog = true })
                /* Phase 47 (Bagian 2): Agent Mode — autonomous task runner. */
                add(PaletteItem("open_agent", "🤖 Agent Mode (Autonomous)", "AI", Icons.Default.SmartToy, PaletteCategory.AI) { showAgentScreen = true })
                /* Phase 49 (D-4): MCP server management UI. */
                add(PaletteItem("manage_mcp", "Manage MCP Servers", "Setting", Icons.Default.Cloud, PaletteCategory.SETTING) { showMcpServerDialog = true })
                /* Commands. */
                add(PaletteItem("cmd_ls", "Run: ls -la", "Command", Icons.Default.Terminal, PaletteCategory.COMMAND) { shellExecutors.find { it.id == activeExecutorId }?.executeCommand("ls -la") })
                add(PaletteItem("cmd_pwd", "Run: pwd", "Command", Icons.Default.Terminal, PaletteCategory.COMMAND) { shellExecutors.find { it.id == activeExecutorId }?.executeCommand("pwd") })
                add(PaletteItem("cmd_clear", "Run: clear", "Command", Icons.Default.Clear, PaletteCategory.COMMAND) { shellExecutors.find { it.id == activeExecutorId }?.clearScreen() })
                add(PaletteItem("cmd_help", "Show built-in help", "Command", Icons.Default.Help, PaletteCategory.COMMAND) {
                    /* Wave-20: Local help — do NOT shell executeCommand("help") (toybox has no help). */
                    shellExecutors.find { it.id == activeExecutorId }?.let { exec ->
                        exec.emulator.process("\u001B[36m${buildHelpText()}\u001B[0m\n")
                        exec.triggerScreenUpdate()
                    }
                })
                add(PaletteItem("cmd_tt_find", "Search scrollback (tt-find)", "Command", Icons.Default.Search, PaletteCategory.COMMAND) {
                    shellExecutors.find { it.id == activeExecutorId }?.let { exec ->
                        exec.emulator.process(
                            "\u001B[36mUsage: tt-find <query>   (search scrollback; shell find is not intercepted)\u001B[0m\n"
                        )
                        exec.triggerScreenUpdate()
                    }
                })
                /* Wave-8: History / export / metrics / permissions. */
                add(PaletteItem("cmd_history", "Show command history", "Command", Icons.Default.History, PaletteCategory.COMMAND) {
                    shellExecutors.find { it.id == activeExecutorId }?.let { exec ->
                        val lines = exec.commandHistory.mapIndexed { i, c -> "${i + 1}. $c" }.joinToString("\n").ifBlank { "(empty)" }
                        exec.emulator.process("\u001B[36m$lines\u001B[0m\n"); exec.triggerScreenUpdate()
                    }
                })
                add(PaletteItem("export_transcript", "Export terminal transcript", "Command", Icons.Default.Save, PaletteCategory.COMMAND) {
                    val exec = shellExecutors.find { it.id == activeExecutorId } ?: return@PaletteItem
                    val r = TranscriptExporter.exportSession(this@MainActivity, exec)
                    Toast.makeText(this@MainActivity, if (r.ok) "Exported: ${r.path}" else r.message, Toast.LENGTH_LONG).show()
                })
                add(PaletteItem("ai_metrics", "Show AI metrics", "AI", Icons.Default.Speed, PaletteCategory.AI) {
                    Toast.makeText(this@MainActivity, AiMetrics.summaryLine(), Toast.LENGTH_LONG).show()
                })
                add(PaletteItem("reset_permissions", "Reset AI permissions (this tab)", "Setting", Icons.Default.Lock, PaletteCategory.SETTING) {
                    permissionManager.resetAll()
                    Toast.makeText(this@MainActivity, "Permissions reset for active session", Toast.LENGTH_SHORT).show()
                })
                add(PaletteItem("export_chat", "Export AI chat", "AI", Icons.Default.Save, PaletteCategory.AI) { exportChat() })
                add(PaletteItem("clear_chat", "Clear AI chat", "AI", Icons.Default.Delete, PaletteCategory.AI) { clearChat() })
                add(PaletteItem("font_zoom_in", "Font size + (zoom in)", "Setting", Icons.Default.ZoomIn, PaletteCategory.SETTING) {
                    stepTerminalFont(+1)
                })
                add(PaletteItem("font_zoom_out", "Font size − (zoom out)", "Setting", Icons.Default.ZoomOut, PaletteCategory.SETTING) {
                    stepTerminalFont(-1)
                })
                add(PaletteItem("font_reset", "Reset font size", "Setting", Icons.Default.FormatSize, PaletteCategory.SETTING) { resetFontSize() })
                add(PaletteItem("ssh_list_keys", "List SSH host keys (TOFU)", "Setting", Icons.Default.VpnKey, PaletteCategory.SETTING) {
                    val list = SshHostKeyStore.formatList(this@MainActivity)
                    shellExecutors.find { it.id == activeExecutorId }?.let { exec ->
                        exec.emulator.process("\u001B[36m[SSH known hosts]\n$list\u001B[0m\n")
                        exec.triggerScreenUpdate()
                    }
                })
                /* Wave-10: Copy output, bookmarks, rename tab, keep screen on. */
                add(PaletteItem("copy_output", "Copy last terminal output", "Command", Icons.Default.ContentCopy, PaletteCategory.COMMAND) {
                    shellExecutors.find { it.id == activeExecutorId }?.let { copyLastOutput(it) }
                })
                add(PaletteItem("find_scrollback", "Find in scrollback…", "Command", Icons.Default.Search, PaletteCategory.COMMAND) {
                    shellExecutors.find { it.id == activeExecutorId }?.let { exec ->
                        exec.emulator.process(
                            "\u001B[36mType: tt-find <query>  (e.g. tt-find error)\n" +
                                "Shell find is not intercepted.\u001B[0m\n"
                        )
                        exec.triggerScreenUpdate()
                        insertIntoTerminal("tt-find ")
                    }
                })
                add(PaletteItem("open_url_from_output", "Open URL from last output", "Command", Icons.Default.Link, PaletteCategory.COMMAND) {
                    val exec = shellExecutors.find { it.id == activeExecutorId } ?: return@PaletteItem
                    val url = UrlOpenUtils.firstUrl(exec.getCleanOutput().takeLast(8000))
                    if (url != null) openExternalUrl(url)
                    else Toast.makeText(this@MainActivity, "No URL in recent output", Toast.LENGTH_SHORT).show()
                })
                add(PaletteItem("bookmark_list", "List directory bookmarks", "Command", Icons.Default.Bookmarks, PaletteCategory.COMMAND) {
                    shellExecutors.find { it.id == activeExecutorId }?.let { exec ->
                        val list = BookmarkStore.formatList(this@MainActivity)
                        exec.emulator.process("\u001B[36m$list\u001B[0m\n")
                        exec.triggerScreenUpdate()
                    }
                })
                add(PaletteItem("bookmark_add_home", "Bookmark app home", "Command", Icons.Default.Star, PaletteCategory.COMMAND) {
                    val path = File(filesDir, "home").absolutePath
                    BookmarkStore.add(this@MainActivity, "home", path)
                    Toast.makeText(this@MainActivity, "Bookmarked home → $path", Toast.LENGTH_SHORT).show()
                })
                BookmarkStore.list(this@MainActivity).take(8).forEach { bm ->
                    add(PaletteItem(
                        "bookmark_go_${bm.name}",
                        "Go: ${bm.name}",
                        "Command",
                        Icons.Default.FolderSpecial,
                        PaletteCategory.COMMAND
                    ) {
                        shellExecutors.find { it.id == activeExecutorId }
                            ?.executeCommand("cd ${shellQuote(bm.path)}")
                    })
                }
                add(PaletteItem("rename_tab", "Rename current tab", "Navigation", Icons.Default.Edit, PaletteCategory.NAVIGATION) {
                    val id = activeExecutorId
                    renameTabId = id
                    val exec = shellExecutors.find { it.id == id }
                    renameTabDraft = tabLabels[id]
                        ?: exec?.let { defaultTabLabel(it, shellExecutors.indexOf(it) + 1) }
                        ?: "Tab"
                })
                add(PaletteItem("toggle_keep_screen", "Toggle keep screen on", "Setting", Icons.Default.Visibility, PaletteCategory.SETTING) {
                    val prefs = getSharedPreferences("TunnelUI", Context.MODE_PRIVATE)
                    val next = !prefs.getBoolean("keepScreenOn", true)
                    prefs.edit().putBoolean("keepScreenOn", next).apply()
                    if (next) {
                        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    Toast.makeText(
                        this@MainActivity,
                        if (next) "Keep screen on: ON" else "Keep screen on: OFF",
                        Toast.LENGTH_SHORT
                    ).show()
                })
                /* Wave-9: Snippets in palette for quick type/run. */
                snippetsState.take(10).forEach { sn ->
                    add(PaletteItem(
                        "snippet_run_${sn.id}",
                        "Snippet ▶ ${sn.title}",
                        "Workflow",
                        Icons.Default.PlayArrow,
                        PaletteCategory.COMMAND
                    ) {
                        shellExecutors.find { it.id == activeExecutorId }?.executeCommand(sn.command)
                    })
                    add(PaletteItem(
                        "snippet_type_${sn.id}",
                        "Snippet ⌨ ${sn.title}",
                        "Workflow",
                        Icons.Default.Keyboard,
                        PaletteCategory.COMMAND
                    ) { insertIntoTerminal(sn.command) })
                }
                /* Phase 23: MCP discovery. */
                add(PaletteItem("mcp_discover", "Discover MCP Tools", "AI", Icons.Default.CloudSync, PaletteCategory.AI) { discoverMcpTools() })
                /* Phase 23: Voice input. */
                add(PaletteItem("voice_input", "Voice Input (speak AI prompt)", "AI", Icons.Default.Mic, PaletteCategory.AI) { startVoiceInput() })
                /* Phase 23: Agent workflows. */
                agentWorkflows.forEach { wf ->
                    add(PaletteItem("workflow_${wf.id}", "Run workflow: ${wf.name}", "AI", Icons.Default.AutoMode, PaletteCategory.AI) {
                        scope.launch { executeAgentWorkflow(wf) }
                    })
                }
            }
            val recentCmds = shellExecutors.find { it.id == activeExecutorId }?.commandHistory?.reversed() ?: emptyList()
            CommandPalette(
                theme = currentTheme,
                items = paletteItems,
                recentCommands = recentCmds,
                onExecute = { item ->
                    /* Wave-3: Recent palette items actually run the command. */
                    if (item.id.startsWith("recent_")) {
                        shellExecutors.find { it.id == activeExecutorId }
                            ?.executeCommand(item.title)
                    } else {
                        item.action()
                    }
                    showCommandPalette = false
                },
                onDismiss = { showCommandPalette = false }
            )
        }

        /* Phase 22: AI Tool Call Permission Dialog. */
        pendingToolCall?.let { call ->
            PermissionDialog(
                call = call,
                theme = currentTheme,
                onAllow = {
                    executeToolCall(call, alwaysAllow = false)
                    pendingToolCall = null
                },
                onAlwaysAllow = {
                    permissionManager.setPermission(call.tool, PermissionManager.PermissionState.ALWAYS_ALLOW)
                    executeToolCall(call, alwaysAllow = true)
                    pendingToolCall = null
                },
                onDeny = {
                    val msg = "Permission denied for: ${call.displayText}"
                    chatMessages.add(ChatMessage("assistant", msg, false, isError = true))
                    pendingToolCall = null
                    /* Wave-17: Let the model adapt after deny instead of stalling tool loop. */
                    continueToolLoop("User denied tool: ${call.tool} — ${call.displayText}")
                },
                onNeverAllow = {
                    permissionManager.setPermission(call.tool, PermissionManager.PermissionState.ALWAYS_DENY)
                    chatMessages.add(
                        ChatMessage(
                            "assistant",
                            "Never allow set for ${call.tool} (this session tab).",
                            false,
                            isError = true
                        )
                    )
                    pendingToolCall = null
                    continueToolLoop("User set never-allow for tool: ${call.tool}")
                }
            )
        }

        /* Phase 23: Inline Diff View untuk AI file edits. */
        pendingDiff?.let { (path, original, modified) ->
            DiffViewDialog(
                fileName = java.io.File(path).name,
                originalContent = original,
                modifiedContent = modified,
                theme = currentTheme,
                onApply = {
                    /* Wave-1: Apply via ToolExecutor so checkpoint + sandbox apply. */
                    val result = toolExecutor.execute(
                        AiToolCall("write_file", mapOf("path" to path, "content" to modified))
                    )
                    chatMessages.add(ChatMessage("assistant", "✅ $result", false, isError = result.startsWith("Error")))
                    pendingDiff = null
                    /* Wave-2: Continue multi-turn tool loop after apply. */
                    continueToolLoop(result)
                },
                onReject = {
                    chatMessages.add(ChatMessage("assistant", "Changes rejected for ${java.io.File(path).name}", false))
                    pendingDiff = null
                    continueToolLoop("User rejected write_file changes for $path")
                }
            )
        }

        /* Phase 21: SSH Connect Dialog. */
        if (showSshDialog) {
            SshConnectDialog(
                theme = currentTheme,
                onConnect = { config ->
                    showSshDialog = false
                    lifecycleScope.launch { createSshTab(config) }
                },
                onDismiss = { showSshDialog = false }
            )
        }

        /* Phase 41 fix (CRIT-02): SSH Host Key Change blocking dialog.
         * Muncul saat fingerprint server berubah (potential MITM).
         * User HARUS actively choose — tidak ada auto-approve. */
        _sshHostKeyDialogState.value?.let { dialogState ->
            SshHostKeyChangeDialog(
                state = dialogState,
                theme = currentTheme,
                onDismiss = {
                    _sshHostKeyDialogState.value = null
                    dialogState.onResolve(false)
                },
                onApprove = {
                    _sshHostKeyDialogState.value = null
                    dialogState.onResolve(true)
                }
            )
        }

        /* Phase 39 (proot/Ubuntu): Install / manage Linux environment dialog. */
        if (showUbuntuInstallDialog) {
            UbuntuInstallDialog(
                theme = currentTheme,
                bootstrap = prootBootstrap,
                installing = ubuntuInstalling,
                stage = ubuntuInstallStage,
                percent = ubuntuInstallPercent,
                error = ubuntuInstallError,
                onInstall = { startUbuntuInstall() },
                onUninstall = {
                    uninstallUbuntu()
                    showUbuntuInstallDialog = false
                },
                onDismiss = { showUbuntuInstallDialog = false }
            )
        }

        /* Phase 47 (Bagian 2): Agent Mode screen. */
        if (showAgentScreen) {
            AgentScreen(
                theme = currentTheme,
                isRunning = agentRunning,
                isPaused = agentPaused,
                events = agentEvents,
                pendingClarification = agentPendingClarification,
                lastGoal = agentLastGoal,
                onStart = { goal, useUbuntu ->
                    startAgentTask(goal, useUbuntu)
                },
                onPause = {
                    agentTaskRunner.pause()
                    agentPaused = true
                },
                onResume = {
                    agentTaskRunner.resume()
                    agentPaused = false
                },
                onStop = {
                    /* Phase 52 fix (Bug #3): Job.cancel() menginterupsi coroutine di titik
                     * suspend manapun — termasuk saat menunggu respons AI. */
                    agentJob?.cancel()
                    agentTaskRunner.stop()
                    agentRunning = false
                    agentPaused = false
                },
                onDismiss = { showAgentScreen = false },
                onAnswerClarification = { answer -> continueAgentWithClarification(answer) }
            )
        }

        /* Phase 52 fix (Bug #1): Approval dialog untuk aksi berisiko di Agent Mode.
         * Muncul saat assessRisk() mendeteksi command berisiko (rm -rf, curl|sh, sudo, dst).
         * User HARUS pilih Approve/Deny — tidak bisa dismiss tanpa pilihan eksplisit. */
        pendingAgentApproval?.let { (call, reason) ->
            AlertDialog(
                onDismissRequest = { /* sengaja kosong — tidak boleh dismiss tanpa pilihan */ },
                modifier = Modifier.background(currentTheme.uiBg),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⚠️ ", color = Color(0xFFFF5252), fontSize = 18.sp)
                        Text("Aksi Berisiko — Butuh Persetujuan",
                            color = Color(0xFFFF5252), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    }
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        Text(reason, color = currentTheme.uiTextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Command lengkap:", color = currentTheme.uiTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0x33FF5252),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                call.displayTextFull,
                                color = Color(0xFFFFAB00),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            agentApprovalDeferred?.complete(true)
                            pendingAgentApproval = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
                    ) { Text("Approve", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                },
                dismissButton = {
                    Button(
                        onClick = {
                            agentApprovalDeferred?.complete(false)
                            pendingAgentApproval = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = currentTheme.uiSurface)
                    ) { Text("Deny", color = currentTheme.uiText, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                }
            )
        }

        /* Phase 49 (D-4): MCP Server Management dialog. */
        if (showMcpServerDialog) {
            McpServerManagementDialog(
                theme = currentTheme,
                servers = mcpManager.servers,
                onAddServer = { config ->
                    mcpManager.addServer(config)
                },
                onRemoveServer = { name ->
                    mcpManager.removeServer(name)
                },
                onDismiss = { showMcpServerDialog = false }
            )
        }

        /* Wave-10: Rename tab dialog (long-press tab). */
        renameTabId?.let { tabId ->
            AlertDialog(
                onDismissRequest = {
                    renameTabId = null
                    renameTabDraft = ""
                },
                title = {
                    Text(
                        "Rename tab",
                        color = currentTheme.uiText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                },
                text = {
                    OutlinedTextField(
                        value = renameTabDraft,
                        onValueChange = { renameTabDraft = it.take(32) },
                        singleLine = true,
                        label = {
                            Text("Label", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = currentTheme.uiText,
                            unfocusedTextColor = currentTheme.uiText,
                            focusedBorderColor = currentTheme.uiAccent,
                            unfocusedBorderColor = currentTheme.uiSurface,
                            focusedLabelColor = currentTheme.uiTextMuted,
                            unfocusedLabelColor = currentTheme.uiTextMuted,
                            cursorColor = currentTheme.uiAccent
                        ),
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val label = renameTabDraft.trim()
                        if (label.isNotEmpty()) {
                            tabLabels[tabId] = label
                        } else {
                            tabLabels.remove(tabId)
                        }
                        renameTabId = null
                        renameTabDraft = ""
                    }) {
                        Text("Save", color = currentTheme.uiAccent, fontFamily = FontFamily.Monospace)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        renameTabId = null
                        renameTabDraft = ""
                    }) {
                        Text("Cancel", color = currentTheme.uiTextMuted, fontFamily = FontFamily.Monospace)
                    }
                },
                containerColor = currentTheme.uiBg
            )
        }

        /* Phase 19: File Explorer Dialog. */
        if (showFileExplorer) {
            AlertDialog(
                onDismissRequest = { showFileExplorer = false },
                modifier = Modifier.fillMaxSize(0.95f).background(currentTheme.uiBg),
                title = { Text("File Explorer", color = currentTheme.uiText, fontFamily = FontFamily.Monospace) },
                text = {
                    Box(modifier = Modifier.fillMaxSize().padding(4.dp)) {
                        FileExplorerPanel(
                            initialDir = File(applicationContext.filesDir, "home"),
                            theme = currentTheme,
                            onFileOpen = { file ->
                                openFileFromExplorer(file)
                                showFileExplorer = false
                            },
                            onFolderNavigate = { dir -> cdFromExplorer(dir) },
                            onClose = { showFileExplorer = false }
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { showFileExplorer = false },
                        colors = ButtonDefaults.buttonColors(containerColor = currentTheme.uiSurface)
                    ) { Text("Close", color = currentTheme.uiText) }
                }
            )
        }

        /* Phase 19: Workspace Sessions Dialog. */
        if (showWorkspaceDrawer) {
            WorkspaceSessionDialog(
                theme = currentTheme,
                sessions = workspaceSessions,
                currentTabCount = shellExecutors.size,
                onSaveSession = { name -> saveWorkspace(name) },
                onRestoreSession = { session -> scope.launch { restoreWorkspace(session); showWorkspaceDrawer = false } },
                onDeleteSession = { name -> deleteWorkspace(name) },
                onDismiss = { showWorkspaceDrawer = false }
            )
        }

        /* Wave-21: Side-by-side layout — terminal LEFT, AI panel RIGHT (never covers terminal). */
        val configuration = LocalConfiguration.current
        val panelWidthDp = (configuration.screenWidthDp * 0.40f).coerceIn(280f, 420f).dp

        Row(modifier = Modifier.fillMaxSize()) {
            /* ── Terminal column ── */
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            val activeExecutor = shellExecutors.find { it.id == activeExecutorId } ?: shellExecutors.firstOrNull()
            if (activeExecutor == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Inisialisasi Tunnel Terminal...", color = Color.White, fontFamily = FontFamily.Monospace)
                }
            } else {

            val screenDirty by activeExecutor.screenDirty.collectAsState()
            val tabsData = shellExecutors.mapIndexed { index, executor ->
                TabUiItem(
                    id = executor.id,
                    index = index + 1,
                    label = tabLabels[executor.id] ?: defaultTabLabel(executor, index + 1)
                )
            }
            /* Wave-11/12: IME field is Activity-scoped (imeFieldText / imeFieldLast). */

            /* Phase 33 (A2 fix): Deteksi keyboard fisik — jangan paksa soft keyboard muncul.
             * Old code: if (!hasPhysicalKeyboard) keyboardController?.show() selalu dipanggil → soft keyboard muncul
             * bahkan saat physical keyboard aktif → adjustResize mengecilkan terminal →
             * layar "naik ke atas", text tidak kelihatan. */
            val hasPhysicalKeyboard = configuration.keyboard == android.content.res.Configuration.KEYBOARD_QWERTY &&
                configuration.hardKeyboardHidden == android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO

            /* Phase 36: Flag untuk mencegah Enter double-fire antara handleKeyEvent
             * (physical keyboard KeyDown) dan onValueChange (soft keyboard commitText). */
            var enterHandledByKeyEvent by remember { mutableStateOf(false) }

            /* Wave-3: Live-update RUNNING blocks from terminal stream. */
            LaunchedEffect(activeExecutor.id, screenDirty, blockMode) {
                if (!blockMode || !blockManager.hasRunningBlock()) return@LaunchedEffect
                val clean = activeExecutor.getCleanOutput()
                val running = blockManager.blocks.lastOrNull {
                    it.status == CommandBlock.BlockStatus.RUNNING
                } ?: return@LaunchedEffect
                val idx = clean.lastIndexOf(running.command)
                val out = if (idx >= 0) {
                    clean.substring(idx + running.command.length).trim()
                } else {
                    clean.takeLast(800)
                }
                val tail = clean.takeLast(120)
                val promptBack = Regex("""(?:tunnel@android|[\w.-]+@[\w.-]+):[^\n]*[$#]\s*$""")
                    .containsMatchIn(tail) ||
                    Regex("""root@ubuntu:[^\n]*#\s*$""").containsMatchIn(tail)
                if (promptBack && out.isNotBlank()) {
                    val status = when {
                        out.lowercase().contains("command not found") ||
                            out.lowercase().contains("permission denied") ||
                            out.lowercase().contains("no such file") ||
                            out.lowercase().contains(": error") -> CommandBlock.BlockStatus.ERROR
                        else -> CommandBlock.BlockStatus.SUCCESS
                    }
                    blockManager.completeRunning(out, status)
                } else {
                    blockManager.updateRunningOutput(out)
                }
            }

            /* Phase 26: Fix auto-error-detection terlalu agresif.
             * Old code: trigger pada setiap output chunk yang mengandung "error"
             * → false positive untuk `grep error`, `cat error.log`, dll.
             * Fix: hanya trigger jika output ENDS dengan error pattern (bukan contains),
             * + cooldown 5 detik antara notifikasi. */
            var lastErrorNotificationTime by remember { mutableStateOf(0L) }
            LaunchedEffect(activeExecutor.id, activeExecutor.lastCommandOutput.value) {
                if (isProcessingAI) return@LaunchedEffect
                val now = System.currentTimeMillis()
                /* Cooldown 5 detik. */
                if (now - lastErrorNotificationTime < 5000) return@LaunchedEffect

                val lastOut = activeExecutor.getCleanOutput().lowercase()
                /* Hanya cek 500 char terakhir (output terbaru, bukan seluruh buffer). */
                val recentOut = lastOut.takeLast(500)
                /* Error hanya jika di akhir baris (bukan di tengah command seperti grep). */
                val hasError = recentOut.lines().any { line ->
                    (line.endsWith("error") || line.endsWith("not found") ||
                     line.endsWith("no such file or directory") || line.endsWith("permission denied") ||
                     line.contains(": error:") || line.contains("command not found"))
                }
                if (hasError) {
                    val hasExistingErrorNotification = chatMessages.any {
                        it.role == "assistant" && it.content.contains("mendeteksi error")
                    }
                    if (!hasExistingErrorNotification) {
                        lastErrorNotificationTime = now
                        chatMessages.add(ChatMessage(
                            "assistant",
                            "⚠️ Saya mendeteksi error di terminal. Klik 🛠 untuk minta solusi, " +
                            "atau deskripsikan masalah di chat.",
                            false
                        ))
                    }
                }
            }

            fun handleExtraKey(key: String) {
                /* Phase 34 (A4) + Phase 53: PASTE — extract ke pasteFromClipboard() supaya
                 * bisa dipakai ulang oleh floating toolbar. */
                if (key == "PASTE") {
                    pasteFromClipboard(activeExecutor)
                    return
                }
                /* Wave-12: One-shot control chips (^C etc.) — not sticky CTRL. */
                when (key) {
                    "^C" -> {
                        /* Wave-20: Interrupt also drops our IME/line trackers so next type is clean. */
                        activeExecutor.writeRaw(3.toChar().toString())
                        activeExecutor.currentCommandBuffer = ""
                        activeExecutor.historyIndex = -1
                        clearImeLine()
                        return
                    }
                    "^D" -> { activeExecutor.writeRaw(4.toChar().toString()); return }
                    "^Z" -> { activeExecutor.writeRaw(26.toChar().toString()); return }
                    "^L" -> {
                        activeExecutor.writeRaw(12.toChar().toString())
                        return
                    }
                    "^U" -> {
                        activeExecutor.writeRaw(21.toChar().toString())
                        activeExecutor.currentCommandBuffer = ""
                        clearImeLine()
                        return
                    }
                    "^W" -> {
                        /* Delete last word: send Ctrl+W and drop last token from trackers. */
                        activeExecutor.writeRaw(23.toChar().toString())
                        val prev = activeExecutor.currentCommandBuffer.trimEnd()
                        val i = prev.lastIndexOf(' ')
                        val next = if (i < 0) "" else prev.take(i + 1)
                        activeExecutor.currentCommandBuffer = next
                        syncImeLine(next)
                        return
                    }
                    "^A" -> { activeExecutor.writeRaw(1.toChar().toString()); return }
                    "^E" -> { activeExecutor.writeRaw(5.toChar().toString()); return }
                }
                val emu = activeExecutor.emulator
                val ansiCode: String = when (key) {
                    "ESC" -> "\u001B"
                    "TAB" -> "\t"
                    "↑" -> emu.cursorKey('A')
                    "↓" -> emu.cursorKey('B')
                    "→" -> emu.cursorKey('C')
                    "←" -> emu.cursorKey('D')
                    /* Wave-14/20: xterm application keypad HOME/END (readline / bash). */
                    "HOME" -> "\u001B[1~"
                    "END" -> "\u001B[4~"
                    "PGUP" -> "\u001B[5~"
                    "PGDN" -> "\u001B[6~"
                    "BKSP" -> {
                        if (activeExecutor.currentCommandBuffer.isNotEmpty()) {
                            activeExecutor.currentCommandBuffer =
                                activeExecutor.currentCommandBuffer.dropLast(1)
                        }
                        if (imeFieldText.isNotEmpty()) {
                            syncImeLine(imeFieldText.dropLast(1))
                        }
                        "\u007F"
                    }
                    "DEL" -> "\u001B[3~"
                    "CTRL" -> { isCtrlActive = !isCtrlActive; "" }
                    "ALT" -> { isAltActive = !isAltActive; "" }
                    "F1" -> emu.functionKey(1)
                    "F2" -> emu.functionKey(2)
                    "F3" -> emu.functionKey(3)
                    "F4" -> emu.functionKey(4)
                    "F5" -> emu.functionKey(5)
                    "F6" -> emu.functionKey(6)
                    "F7" -> emu.functionKey(7)
                    "F8" -> emu.functionKey(8)
                    "F9" -> emu.functionKey(9)
                    "F10" -> emu.functionKey(10)
                    "F11" -> emu.functionKey(11)
                    "F12" -> emu.functionKey(12)
                    /* Wave-16: Font zoom chips (also pinch on screen). */
                    "A+", "A＋" -> {
                        stepTerminalFont(+1)
                        ""
                    }
                    "A−", "A-", "A–" -> {
                        stepTerminalFont(-1)
                        ""
                    }
                    else -> {
                        /* Sticky CTRL + symbol/letter from ExtraKeys. */
                        if (isCtrlActive && key.length == 1) {
                            isCtrlActive = false
                            val ch = key[0].lowercaseChar()
                            if (ch in 'a'..'z') {
                                (ch - 'a' + 1).toChar().toString()
                            } else key
                        } else if (isAltActive && key.length == 1) {
                            isAltActive = false
                            "\u001B$key"
                        } else {
                            /* Printable symbol — also update line tracker. */
                            if (key.length == 1 && !key[0].isISOControl()) {
                                activeExecutor.currentCommandBuffer += key
                                syncImeLine(imeFieldText + key)
                            }
                            key
                        }
                    }
                }
                if (ansiCode.isNotEmpty()) activeExecutor.writeRaw(ansiCode)
            }

            fun processInput(input: String) {
                /* Wave-20: Keep raw line for DEL count (trim shrinks erase → leftover chars on shell). */
                val rawLine = input.replace("\n", "").replace("\r", "")
                val cmd = rawLine.trim()

                /* Phase 45 fix Bug #2: Pseudo-command lokal menempel ke command berikutnya.
                 *
                 * OLD BUG: Setiap karakter yang user ketik langsung dikirim live ke shell asli
                 * (writeRaw char-by-char) untuk echo, tab-completion, dst. Saat user tekan
                 * Enter untuk pseudo-command lokal (help, clear, setup-storage, dst), app
                 * menjalankan aksi lokalnya sendiri TAPI TIDAK mengirim \n ke shell asli.
                 * Akibatnya: karakter "setup-storage" masih nangkring di buffer baris internal
                 * shell. Saat user ketik "ls" berikutnya, 'l' dan 's' nyangkut ke buffer
                 * yang masih berisi "setup-storage" → shell eksekusi "setup-storagels" →
                 * error "sh: setup-storagels: inaccessible or not found".
                 *
                 * FIX: Sebelum proses pseudo-command lokal, hapus dulu teks itu dari buffer
                 * baris shell asli dengan mengirim backspace (\u007F) sebanyak panjang baris
                 * yang benar-benar diketik (rawLine / currentCommandBuffer), bukan trim(cmd).
                 *
                 * Wave-20: `find` is NO LONGER local — use `tt-find` so real shell find works.
                 */
                val isLocalOnly = cmd == "help" || cmd == "clear" || cmd == "setup-storage" ||
                    cmd == "storage-status" || cmd == "storage-reset" ||
                    cmd == "storage-grant-all" ||
                    cmd == "storage-ls" || cmd.startsWith("storage-ls ") ||
                    cmd == "storage-put" || cmd.startsWith("storage-put ") ||
                    cmd == "storage-get" || cmd.startsWith("storage-get ") ||
                    cmd == "storage-save-download" || cmd.startsWith("storage-save-download ") ||
                    cmd == "storage-write" || cmd.startsWith("storage-write ") ||
                    cmd == "storage-rm" || cmd.startsWith("storage-rm ") ||
                    cmd == "ssh-reset-hostkeys" || cmd == "ssh-list-hostkeys" ||
                    cmd == "system-info" ||
                    cmd == "history" || cmd == "history-clear" ||
                    cmd == "export-output" || cmd == "export-chat" || cmd == "ai-metrics" ||
                    cmd == "font-reset" ||
                    cmd == "copy-output" ||
                    cmd == "bookmark" || cmd.startsWith("bookmark ") ||
                    cmd == "tt-find" || cmd.startsWith("tt-find ") ||
                    cmd == "search-scrollback" || cmd.startsWith("search-scrollback ") ||
                    cmd == "open-url" || cmd.startsWith("open-url ") ||
                    cmd == "open" || cmd.startsWith("open ") ||
                    (activeExecutor.sessionType == "ubuntu" &&
                        (cmd == "systemctl" || cmd.startsWith("systemctl ") ||
                            cmd == "service" || cmd.startsWith("service ")))

                if (isLocalOnly && rawLine.isNotEmpty()) {
                    /* Prefer app line tracker length (matches what was typed into PTY). */
                    val eraseLen = activeExecutor.currentCommandBuffer.length
                        .takeIf { it > 0 } ?: rawLine.length
                    repeat(eraseLen) { activeExecutor.writeRaw("\u007F") }
                }

                if (cmd.isNotEmpty()) {
                    val h = activeExecutor.commandHistory
                    if (h.isEmpty() || h.last() != cmd) h.add(cmd)
                    if (h.size > 500) h.removeAt(0)
                    /* Wave-8: Persist global history for autocomplete across restarts. */
                    CommandHistoryStore.append(this@MainActivity, cmd)
                    /* Phase 26: Add block ke BlockManager saat command dijalankan. */
                    if (blockMode) {
                        blockManager.addBlock(cmd)
                    }
                }
                activeExecutor.historyIndex = -1

                /* Built-in commands yang ditangani lokal. */
                when {
                    cmd == "help" -> {
                        val helpText = buildHelpText()
                        activeExecutor.emulator.process("\u001B[36m$helpText\u001B[0m\n")
                        activeExecutor.triggerScreenUpdate()
                        if (blockMode) {
                            blockManager.completeRunning(helpText, CommandBlock.BlockStatus.SUCCESS)
                        }
                    }
                    cmd == "clear" -> {
                        /* Clear screen buffer lokal - tidak kirim ke shell.
                         * Local clear - don't send to shell. */
                        activeExecutor.clearScreen()
                        if (blockMode) {
                            blockManager.completeRunning("(cleared)", CommandBlock.BlockStatus.SUCCESS)
                        }
                    }
                    cmd == "setup-storage" -> {
                        activeExecutor.emulator.process(
                            "\n\u001B[36m[Setup Storage] Membuka picker folder perangkat...\u001B[0m\n" +
                            "\u001B[33mDisarankan pilih folder Download (atau Documents).\u001B[0m\n" +
                            "\u001B[33mSetelah grant, gunakan storage-ls / storage-put / storage-save-download.\u001B[0m\n"
                        )
                        activeExecutor.triggerScreenUpdate()
                        storageLauncher.launch(storageManager.createOpenTreeIntent())
                    }
                    cmd == "storage-status" -> {
                        val report = storageManager.statusReport()
                        activeExecutor.emulator.process("\n$report\n")
                        activeExecutor.triggerScreenUpdate()
                    }
                    cmd == "storage-reset" -> {
                        storageManager.clearSetup()
                        activeExecutor.emulator.process(
                            "\n\u001B[33m[Storage] Setup direset. Ketik 'setup-storage' untuk pilih folder lagi.\u001B[0m\n"
                        )
                        activeExecutor.triggerScreenUpdate()
                    }
                    cmd == "storage-grant-all" -> {
                        activeExecutor.emulator.process(
                            "\n\u001B[36m[Storage] Membuka pengaturan \"Akses semua file\"...\u001B[0m\n" +
                            "\u001B[33mIzinkan Tunnel Terminal, lalu kembali ke app.\u001B[0m\n" +
                            "\u001B[33mIni opsional — storage-* (SAF) tetap bekerja tanpa ini.\u001B[0m\n"
                        )
                        activeExecutor.triggerScreenUpdate()
                        manageAllFilesLauncher.launch(storageManager.createManageAllFilesIntent())
                    }
                    cmd == "storage-ls" || cmd.startsWith("storage-ls ") -> {
                        val sub = cmd.removePrefix("storage-ls").trim()
                        val result = storageManager.listRelative(sub)
                        val out = result.fold(
                            onSuccess = { rows ->
                                if (rows.isEmpty()) "(kosong) ${storageManager.getDisplayName()}/${sub.trim('/')}"
                                else rows.joinToString("\n")
                            },
                            onFailure = { "Error: ${it.message}" }
                        )
                        activeExecutor.emulator.process("\n\u001B[36m$out\u001B[0m\n")
                        activeExecutor.triggerScreenUpdate()
                    }
                    cmd == "storage-put" || cmd.startsWith("storage-put ") -> {
                        val rest = if (cmd == "storage-put") "" else cmd.removePrefix("storage-put ").trim()
                        val parts = rest.split(Regex("\\s+"), limit = 2).filter { it.isNotEmpty() }
                        val srcName = parts.getOrNull(0).orEmpty()
                        val dest = parts.getOrNull(1)
                        if (srcName.isBlank()) {
                            activeExecutor.emulator.process(
                                "\n\u001B[31mUsage: storage-put <file-workspace|path> [nama-di-folder-SAF]\u001B[0m\n"
                            )
                        } else {
                            val local = resolveLocalStorageFile(srcName)
                            val r = if (local != null) {
                                storageManager.putLocalFile(local, dest)
                            } else {
                                Result.failure(IllegalArgumentException("File tidak ditemukan: $srcName (coba path di workspace)"))
                            }
                            val msg = r.fold(
                                onSuccess = { "\u001B[32m$it\u001B[0m" },
                                onFailure = { "\u001B[31mError: ${it.message}\u001B[0m" }
                            )
                            activeExecutor.emulator.process("\n$msg\n")
                        }
                        activeExecutor.triggerScreenUpdate()
                    }
                    cmd == "storage-get" || cmd.startsWith("storage-get ") -> {
                        val rest = if (cmd == "storage-get") "" else cmd.removePrefix("storage-get ").trim()
                        val parts = rest.split(Regex("\\s+"), limit = 2).filter { it.isNotEmpty() }
                        val remote = parts.getOrNull(0).orEmpty()
                        val localName = parts.getOrNull(1) ?: File(remote).name
                        if (remote.isBlank()) {
                            activeExecutor.emulator.process(
                                "\n\u001B[31mUsage: storage-get <file-di-folder-SAF> [nama-lokal-di-workspace]\u001B[0m\n"
                            )
                        } else {
                            val dest = File(storageManager.workspaceDir, localName)
                            val r = storageManager.getToLocalFile(remote, dest)
                            val msg = r.fold(
                                onSuccess = { "\u001B[32m$it\u001B[0m" },
                                onFailure = { "\u001B[31mError: ${it.message}\u001B[0m" }
                            )
                            activeExecutor.emulator.process("\n$msg\n")
                        }
                        activeExecutor.triggerScreenUpdate()
                    }
                    cmd == "storage-save-download" || cmd.startsWith("storage-save-download ") -> {
                        val rest = if (cmd == "storage-save-download") "" else cmd.removePrefix("storage-save-download ").trim()
                        val parts = rest.split(Regex("\\s+"), limit = 2).filter { it.isNotEmpty() }
                        val srcName = parts.getOrNull(0).orEmpty()
                        val displayName = parts.getOrNull(1)
                        if (srcName.isBlank()) {
                            activeExecutor.emulator.process(
                                "\n\u001B[31mUsage: storage-save-download <file-workspace> [nama-di-Download]\u001B[0m\n" +
                                    "\u001B[33mMenyimpan ke Download publik (MediaStore) — terlihat di app Files/Downloads.\u001B[0m\n"
                            )
                        } else {
                            val local = resolveLocalStorageFile(srcName)
                            val r = if (local != null) {
                                storageManager.saveLocalFileToPublicDownloads(local, displayName)
                            } else {
                                Result.failure(IllegalArgumentException("File tidak ditemukan: $srcName"))
                            }
                            val msg = r.fold(
                                onSuccess = { "\u001B[32m$it\u001B[0m" },
                                onFailure = { "\u001B[31mError: ${it.message}\u001B[0m" }
                            )
                            activeExecutor.emulator.process("\n$msg\n")
                        }
                        activeExecutor.triggerScreenUpdate()
                    }
                    cmd == "storage-write" || cmd.startsWith("storage-write ") -> {
                        /* storage-write <rel-path> <text...> */
                        val rest = if (cmd == "storage-write") "" else cmd.removePrefix("storage-write ").trim()
                        val sp = rest.indexOf(' ')
                        if (sp <= 0) {
                            activeExecutor.emulator.process(
                                "\n\u001B[31mUsage: storage-write <path-relatif-SAF> <teks>\u001B[0m\n" +
                                    "\u001B[33mContoh: storage-write catatan.txt Halo dari Tunnel\u001B[0m\n"
                            )
                        } else {
                            val rel = rest.substring(0, sp).trim()
                            val text = rest.substring(sp + 1)
                            val r = storageManager.writeTextRelative(rel, text)
                            val msg = r.fold(
                                onSuccess = { "\u001B[32m$it\u001B[0m" },
                                onFailure = { "\u001B[31mError: ${it.message}\u001B[0m" }
                            )
                            activeExecutor.emulator.process("\n$msg\n")
                        }
                        activeExecutor.triggerScreenUpdate()
                    }
                    cmd == "storage-rm" || cmd.startsWith("storage-rm ") -> {
                        val rel = if (cmd == "storage-rm") "" else cmd.removePrefix("storage-rm ").trim()
                        if (rel.isBlank()) {
                            activeExecutor.emulator.process(
                                "\n\u001B[31mUsage: storage-rm <path-relatif-SAF>\u001B[0m\n"
                            )
                        } else {
                            val r = storageManager.deleteRelative(rel)
                            val msg = r.fold(
                                onSuccess = { "\u001B[32m$it\u001B[0m" },
                                onFailure = { "\u001B[31mError: ${it.message}\u001B[0m" }
                            )
                            activeExecutor.emulator.process("\n$msg\n")
                        }
                        activeExecutor.triggerScreenUpdate()
                    }
                    cmd == "ssh-reset-hostkeys" -> {
                        /* BUG-02: Reset SSH host key fingerprints (TOFU). */
                        SshHostKeyStore.clearAll(this@MainActivity)
                        activeExecutor.emulator.process("\n\u001B[33m[SSH] Semua host key fingerprints direset. Koneksi berikutnya akan menerima host key baru.\u001B[0m\n")
                        activeExecutor.triggerScreenUpdate()
                    }
                    cmd == "ssh-list-hostkeys" -> {
                        val list = SshHostKeyStore.formatList(this@MainActivity)
                        activeExecutor.emulator.process("\u001B[36m[SSH known hosts]\n$list\u001B[0m\n")
                        activeExecutor.triggerScreenUpdate()
                    }
                    cmd == "font-reset" -> {
                        resetFontSize()
                        activeExecutor.emulator.process("\u001B[33m[UI] Font size reset.\u001B[0m\n")
                        activeExecutor.triggerScreenUpdate()
                    }
                    cmd == "export-chat" -> {
                        exportChat()
                        activeExecutor.emulator.process("\u001B[32m[Export] Chat export requested (see toast).\u001B[0m\n")
                        activeExecutor.triggerScreenUpdate()
                    }
                    cmd == "system-info" -> {
                        val info = SystemInfo.buildMotd(this@MainActivity)
                        activeExecutor.emulator.process(info)
                        activeExecutor.emulator.process("\u001B[36m${AiMetrics.summaryLine()}\u001B[0m\n")
                        activeExecutor.triggerScreenUpdate()
                    }
                    cmd == "history" -> {
                        val lines = activeExecutor.commandHistory.mapIndexed { i, c ->
                            "${(i + 1).toString().padStart(4)}  $c"
                        }.joinToString("\n").ifBlank { "(empty history)" }
                        activeExecutor.emulator.process("\u001B[36m$lines\u001B[0m\n")
                        activeExecutor.triggerScreenUpdate()
                        if (blockMode) {
                            blockManager.completeRunning(lines, CommandBlock.BlockStatus.SUCCESS)
                        }
                    }
                    cmd == "history-clear" -> {
                        activeExecutor.commandHistory.clear()
                        CommandHistoryStore.clear(this@MainActivity)
                        activeExecutor.emulator.process("\u001B[33m[History] Cleared (session + persisted).\u001B[0m\n")
                        activeExecutor.triggerScreenUpdate()
                        if (blockMode) {
                            blockManager.completeRunning("history cleared", CommandBlock.BlockStatus.SUCCESS)
                        }
                    }
                    cmd == "export-output" -> {
                        val result = TranscriptExporter.exportSession(this@MainActivity, activeExecutor)
                        val msg = if (result.ok) {
                            "\u001B[32m[Export] ${result.message}\n${result.path}\u001B[0m\n"
                        } else {
                            "\u001B[31m[Export] ${result.message}\u001B[0m\n"
                        }
                        activeExecutor.emulator.process(msg)
                        activeExecutor.triggerScreenUpdate()
                        if (blockMode) {
                            blockManager.completeRunning(result.message, if (result.ok) CommandBlock.BlockStatus.SUCCESS else CommandBlock.BlockStatus.ERROR)
                        }
                    }
                    cmd == "ai-metrics" -> {
                        val line = AiMetrics.summaryLine()
                        val recent = AiMetrics.recent(5).joinToString("\n") {
                            "  ${it.provider}/${it.model} ${it.latencyMs}ms ok=${it.success} style=${it.apiStyle}"
                        }.ifBlank { "  (no recent requests)" }
                        activeExecutor.emulator.process("\u001B[36m$line\nRecent:\n$recent\u001B[0m\n")
                        activeExecutor.triggerScreenUpdate()
                    }
                    cmd == "copy-output" -> {
                        val result = copyLastOutput(activeExecutor)
                        activeExecutor.emulator.process("\u001B[32m[Clipboard] $result\u001B[0m\n")
                        activeExecutor.triggerScreenUpdate()
                        if (blockMode) {
                            blockManager.completeRunning(result, CommandBlock.BlockStatus.SUCCESS)
                        }
                    }
                    cmd == "bookmark" || cmd.startsWith("bookmark ") -> {
                        val msg = handleBookmarkCommand(cmd, activeExecutor)
                        activeExecutor.emulator.process("\u001B[36m$msg\u001B[0m\n")
                        activeExecutor.triggerScreenUpdate()
                        if (blockMode) {
                            blockManager.completeRunning(msg, CommandBlock.BlockStatus.SUCCESS)
                        }
                    }
                    cmd == "tt-find" || cmd.startsWith("tt-find ") ||
                    cmd == "search-scrollback" || cmd.startsWith("search-scrollback ") -> {
                        /* Wave-20: Renamed from `find` so shell find/grep pipelines work. */
                        val q = when {
                            cmd.startsWith("tt-find") -> cmd.removePrefix("tt-find").trim()
                            else -> cmd.removePrefix("search-scrollback").trim()
                        }
                        val msg = if (q.isBlank()) {
                            "Usage: tt-find <query>   (search scrollback + screen)\n" +
                                "Note: shell `find` is not intercepted — use real find for files."
                        } else {
                            val lines = activeExecutor.emulator.exportPlainLines(2000)
                            val hits = ScrollbackSearch.find(lines, q, ignoreCase = true)
                            ScrollbackSearch.formatHits(hits, q)
                        }
                        activeExecutor.emulator.process("\u001B[36m$msg\u001B[0m\n")
                        activeExecutor.triggerScreenUpdate()
                        if (blockMode) {
                            blockManager.completeRunning(msg, CommandBlock.BlockStatus.SUCCESS)
                        }
                    }
                    cmd == "open-url" || cmd.startsWith("open-url ") -> {
                        val raw = cmd.removePrefix("open-url").trim()
                        val url = UrlOpenUtils.firstUrl(raw) ?: UrlOpenUtils.firstUrl(activeExecutor.getCleanOutput().takeLast(4000))
                        val msg = if (url == null) {
                            "No http(s) URL found. Usage: open-url https://…"
                        } else {
                            openExternalUrl(url)
                            "Opening $url"
                        }
                        activeExecutor.emulator.process("\u001B[36m$msg\u001B[0m\n")
                        activeExecutor.triggerScreenUpdate()
                    }
                    cmd == "open" || cmd.startsWith("open ") -> {
                        val fileName = if (cmd == "open") "" else cmd.removePrefix("open ").trim()
                        if (fileName.isBlank()) {
                            activeExecutor.emulator.process(
                                "\n\u001B[31mUsage: open <file>\u001B[0m\n"
                            )
                            activeExecutor.triggerScreenUpdate()
                        } else {
                            resolveAndOpen(fileName, activeExecutor)
                        }
                    }
                    /* Phase 43 fix (LOW-04): Intercept systemctl/service di tab Ubuntu.
                     * Tampilkan workaround langsung di terminal supaya user tidak perlu
                     * buka GitHub untuk tahu solusi. */
                    activeExecutor.sessionType == "ubuntu" &&
                        (cmd.startsWith("systemctl ") || cmd.startsWith("service ")) -> {
                        activeExecutor.emulator.process(
                            "\n\u001B[33m[INFO] systemctl/service tidak didukung di proot (tidak ada systemd).\u001B[0m\n" +
                            "\u001B[36mWorkaround: jalankan servis manual sebagai proses biasa.\u001B[0m\n" +
                            "\u001B[36mContoh:\u001B[0m\n" +
                            "  nginx -g \"daemon off;\" &\n" +
                            "  sshd -D &\n" +
                            "  cron -f &\n" +
                            "  mysqld_safe &\n\n" +
                            "\u001B[36mAtau install supervisor: apt install supervisor && supervisord\u001B[0m\n\n"
                        )
                        activeExecutor.triggerScreenUpdate()
                    }
                    else -> {
                        /* Phase 40 fix (A1): Jangan kirim full command lagi — karakter
                         * sudah dikirim char-by-char via onValueChange (soft keyboard
                         * commitText) atau handleKeyEvent (physical keyboard).
                         * Kirim hanya newline untuk trigger execution.
                         *
                         * OLD BUG: writeRaw(input) mengirim "ls\n" ke shell, padahal
                         * 'l' dan 's' sudah dikirim sebelumnya → shell terima "lsls\n".
                         * FIX: writeRaw("\n") hanya kirim Enter → shell eksekusi baris
                         * yang sudah ter-build di line buffer-nya. */
                        activeExecutor.writeRaw("\n")
                    }
                }
            }

            fun handleChar(char: Char): String {
                if (isCtrlActive) {
                    isCtrlActive = false
                    /* Wave-20: Only a–z → control codes (digits/symbols were garbage bytes). */
                    val lower = char.lowercaseChar()
                    if (lower in 'a'..'z') {
                        return (lower - 'a' + 1).toChar().toString()
                    }
                    return char.toString()
                }
                if (isAltActive) {
                    isAltActive = false
                    return "\u001B$char"
                }
                return char.toString()
            }

            /**
             * Wave-3 + Wave-11: Unified soft-IME / BasicTextField handler.
             *
             * Wave-11 fix (text disappears while typing):
             * BasicTextField is controlled by [imeFieldText]. Previously we only updated
             * [lastInputValue] on each keystroke and left imeFieldText as "" until Enter.
             * When the shell echoed a char → screenDirty → recompose, Compose forced the
             * field back to "" and IME fired onValueChange(""), which was interpreted as
             * "delete all" → backspaces wiped the just-typed (and echoed) characters.
             *
             * Fix: always keep imeFieldText in sync with the tracked IME string via setHidden.
             * Text stays transparent (color Transparent) so the user still sees only PTY echo.
             */
            fun applyImeValueChange(
                newValue: String,
                lastInputValue: String,
                setLast: (String) -> Unit,
                setHidden: (String) -> Unit
            ) {
                if (newValue == lastInputValue) return

                fun sendBackspace() {
                    if (activeExecutor.currentCommandBuffer.isNotEmpty()) {
                        activeExecutor.currentCommandBuffer =
                            activeExecutor.currentCommandBuffer.dropLast(1)
                    }
                    activeExecutor.writeRaw("\u007F")
                }

                fun processChar(ch: Char) {
                    when (ch) {
                        '\n', '\r' -> {
                            if (!enterHandledByKeyEvent) {
                                processInput(activeExecutor.currentCommandBuffer + "\n")
                                activeExecutor.currentCommandBuffer = ""
                            }
                            enterHandledByKeyEvent = false
                            setHidden("")
                            setLast("")
                        }
                        '\u007F', '\b' -> sendBackspace()
                        else -> {
                            val translated = handleChar(ch)
                            activeExecutor.currentCommandBuffer += translated
                            activeExecutor.writeRaw(translated)
                        }
                    }
                }

                /** Commit IME tracking state (never leave imeFieldText stale). */
                fun syncField(value: String) {
                    setLast(value)
                    setHidden(value)
                }

                when {
                    newValue.startsWith(lastInputValue) -> {
                        val added = newValue.substring(lastInputValue.length)
                        for (ch in added) processChar(ch)
                        /* processChar already cleared both on Enter; only sync if still typing. */
                        if (!added.contains('\n') && !added.contains('\r')) {
                            syncField(newValue)
                        }
                    }
                    lastInputValue.startsWith(newValue) -> {
                        val deleted = lastInputValue.length - newValue.length
                        /*
                         * Wave-18: Ignore spurious full wipes.
                         * After shell echo → recompose, some IMEs fire onValueChange("") once.
                         * Treating that as "delete all" sends N backspaces and wipes the line.
                         * Real single-char backspaces still have deleted == 1 (or small).
                         * Full clear: use ExtraKeys ^U instead.
                         */
                        if (newValue.isEmpty() && lastInputValue.isNotEmpty() && deleted >= 2) {
                            syncField(lastInputValue)
                            return
                        }
                        if (newValue.isEmpty() && lastInputValue.length == 1) {
                            /* Allow deleting the last remaining character. */
                            sendBackspace()
                            syncField("")
                            return
                        }
                        repeat(deleted.coerceAtLeast(0)) { sendBackspace() }
                        syncField(newValue)
                    }
                    else -> {
                        /* IME composition / autocorrect replace.
                         * Wave-18: If newValue is empty, do NOT mass-backspace (same spurious wipe). */
                        if (newValue.isEmpty() && lastInputValue.isNotEmpty()) {
                            syncField(lastInputValue)
                            return
                        }
                        repeat(lastInputValue.length) { sendBackspace() }
                        var sawEnter = false
                        for (ch in newValue) {
                            if (ch == '\n' || ch == '\r') sawEnter = true
                            processChar(ch)
                        }
                        if (!sawEnter) syncField(newValue)
                    }
                }
            }

            /** Map Compose Key to char untuk Alt+key handling.
             * Phase 21 hotfix: Dipindahkan SEBELUM handleKeyEvent (forward reference
             * tidak allowed untuk local functions di Kotlin). */
            fun keyToChar(key: Key, shift: Boolean): Char {
                /* Phase 32: Support a-z, 0-9, dan simbol umum. */
                val name = key.toString().lowercase()
                if (name.length == 1 && name[0] in 'a'..'z') {
                    return if (shift) name[0].uppercaseChar() else name[0]
                }
                /* Angka dan simbol di atas angka. */
                return when (key) {
                    Key.Zero -> if (shift) ')' else '0'
                    Key.One -> if (shift) '!' else '1'
                    Key.Two -> if (shift) '@' else '2'
                    Key.Three -> if (shift) '#' else '3'
                    Key.Four -> if (shift) '$' else '4'
                    Key.Five -> if (shift) '%' else '5'
                    Key.Six -> if (shift) '^' else '6'
                    Key.Seven -> if (shift) '&' else '7'
                    Key.Eight -> if (shift) '*' else '8'
                    Key.Nine -> if (shift) '(' else '9'
                    Key.Spacebar -> ' '
                    Key.Minus -> if (shift) '_' else '-'
                    Key.Equals -> if (shift) '+' else '='
                    Key.LeftBracket -> if (shift) '{' else '['
                    Key.RightBracket -> if (shift) '}' else ']'
                    Key.Backslash -> if (shift) '|' else '\\'
                    Key.Semicolon -> if (shift) ':' else ';'
                    Key.Apostrophe -> if (shift) '"' else '\''
                    Key.Comma -> if (shift) '<' else ','
                    Key.Period -> if (shift) '>' else '.'
                    Key.Slash -> if (shift) '?' else '/'
                    Key.Grave -> if (shift) '~' else '`'
                    else -> '\u0000'
                }
            }

            /**
             * Phase 19.5: Handle physical key event dari keyboard/mouse.
             * Returns true jika event di-consume, false untuk fallback ke BasicTextField.
             *
             * Penting: Handle special keys di KeyDown (bukan KeyUp) untuk mencegah
             * double-firing. BasicTextField bisa trigger onValueChange di KeyDown,
             * lalu KeyUp trigger lagi → double input.
             *
             * Handle special keys at KeyDown to prevent double-firing.
             */
            fun handleKeyEvent(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
                /* Phase 32: Handle SEMUA key events di KeyDown untuk mencegah double input.
                 * Old code: return false untuk karakter biasa → BasicTextField juga process
                 * → karakter dikirim 2x (onValueChange + hardware key event).
                 * Fix: return true untuk SEMUA keys, handle karakter langsung di sini. */
                if (event.type != KeyEventType.KeyDown) return false
                val key = event.key
                val ctrl = event.isCtrlPressed
                val alt = event.isAltPressed
                val shift = event.isShiftPressed

                /* Ctrl+key combos (priority). */
                if (ctrl) {
                    when (key) {
                        Key.C -> {
                            activeExecutor.writeRaw(3.toChar().toString())
                            activeExecutor.currentCommandBuffer = ""
                            activeExecutor.historyIndex = -1
                            clearImeLine()
                            return true
                        }
                        Key.U -> {
                            activeExecutor.writeRaw(21.toChar().toString())
                            activeExecutor.currentCommandBuffer = ""
                            clearImeLine()
                            return true
                        }
                        Key.W -> {
                            activeExecutor.writeRaw(23.toChar().toString())
                            val prev = activeExecutor.currentCommandBuffer.trimEnd()
                            val i = prev.lastIndexOf(' ')
                            val next = if (i < 0) "" else prev.take(i + 1)
                            activeExecutor.currentCommandBuffer = next
                            syncImeLine(next)
                            return true
                        }
                        else -> {
                            val ch = when (key) {
                                Key.D -> 4.toChar()
                                Key.Z -> 26.toChar()
                                Key.L -> 12.toChar()
                                Key.A -> 1.toChar()
                                Key.E -> 5.toChar()
                                Key.K -> 11.toChar()
                                Key.R -> 18.toChar()
                                Key.X -> 24.toChar()
                                else -> '\u0000'
                            }
                            if (ch != '\u0000') {
                                activeExecutor.writeRaw(ch.toString())
                                return true
                            }
                        }
                    }
                    /* Ctrl+other = consume tapi tidak lakukan apa-apa. */
                    return true
                }

                /* Alt+key → ESC + key. */
                if (alt) {
                    val ch = keyToChar(key, shift)
                    if (ch != '\u0000') {
                        activeExecutor.writeRaw("\u001B$ch")
                        return true
                    }
                    return true
                }

                /* Special keys. */
                when (key) {
                    Key.Enter -> {
                        /* Phase 36: Set flag agar onValueChange tidak double-fire. */
                        enterHandledByKeyEvent = true
                        processInput(activeExecutor.currentCommandBuffer + "\n")
                        activeExecutor.currentCommandBuffer = ""
                        /* Wave-11: Clear both controlled field and IME tracker. */
                        imeFieldText = ""
                        imeFieldLast = ""
                        return true
                    }
                    Key.Backspace -> {
                        if (activeExecutor.currentCommandBuffer.isNotEmpty()) {
                            activeExecutor.currentCommandBuffer = activeExecutor.currentCommandBuffer.dropLast(1)
                        }
                        activeExecutor.writeRaw("\u007F")
                        /* Wave-11: Keep IME field tracker aligned with shell line buffer. */
                        if (imeFieldText.isNotEmpty()) {
                            imeFieldText = imeFieldText.dropLast(1)
                            imeFieldLast = imeFieldText
                        }
                        return true
                    }
                    Key.Tab -> { activeExecutor.writeRaw("\t"); return true }
                    Key.DirectionUp -> {
                        activeExecutor.writeRaw(activeExecutor.emulator.cursorKey('A')); return true
                    }
                    Key.DirectionDown -> {
                        activeExecutor.writeRaw(activeExecutor.emulator.cursorKey('B')); return true
                    }
                    Key.DirectionRight -> {
                        activeExecutor.writeRaw(activeExecutor.emulator.cursorKey('C')); return true
                    }
                    Key.DirectionLeft -> {
                        activeExecutor.writeRaw(activeExecutor.emulator.cursorKey('D')); return true
                    }
                    Key.Escape -> { activeExecutor.writeRaw("\u001B"); return true }
                    /* Wave-20: Match ExtraKeys HOME/END (xterm CSI) for bash/readline. */
                    Key.MoveHome -> { activeExecutor.writeRaw("\u001B[1~"); return true }
                    Key.MoveEnd -> { activeExecutor.writeRaw("\u001B[4~"); return true }
                    Key.PageUp -> { activeExecutor.writeRaw("\u001B[5~"); return true }
                    Key.PageDown -> { activeExecutor.writeRaw("\u001B[6~"); return true }
                    Key.Delete -> { activeExecutor.writeRaw("\u001B[3~"); return true }
                    /* Wave-12: Full F1–F12 for TUI apps. */
                    Key.F1 -> { activeExecutor.writeRaw(activeExecutor.emulator.functionKey(1)); return true }
                    Key.F2 -> { activeExecutor.writeRaw(activeExecutor.emulator.functionKey(2)); return true }
                    Key.F3 -> { activeExecutor.writeRaw(activeExecutor.emulator.functionKey(3)); return true }
                    Key.F4 -> { activeExecutor.writeRaw(activeExecutor.emulator.functionKey(4)); return true }
                    Key.F5 -> { activeExecutor.writeRaw(activeExecutor.emulator.functionKey(5)); return true }
                    Key.F6 -> { activeExecutor.writeRaw(activeExecutor.emulator.functionKey(6)); return true }
                    Key.F7 -> { activeExecutor.writeRaw(activeExecutor.emulator.functionKey(7)); return true }
                    Key.F8 -> { activeExecutor.writeRaw(activeExecutor.emulator.functionKey(8)); return true }
                    Key.F9 -> { activeExecutor.writeRaw(activeExecutor.emulator.functionKey(9)); return true }
                    Key.F10 -> { activeExecutor.writeRaw(activeExecutor.emulator.functionKey(10)); return true }
                    Key.F11 -> { activeExecutor.writeRaw(activeExecutor.emulator.functionKey(11)); return true }
                    Key.F12 -> { activeExecutor.writeRaw(activeExecutor.emulator.functionKey(12)); return true }
                }

                /* Phase 33 (A1 fix): JANGAN handle karakter cetak biasa di sini.
                 * Return false agar BasicTextField (IME/InputConnection path) menjadi
                 * SATU-SATUYA jalur yang mengirim karakter ke PTY via onValueChange.
                 * Phase 32 salah: return true di sini → karakter dikirim 2x
                 * (sekali di sini, sekali di onValueChange) → "lsls" bukan "ls".
                 *
                 * onPreviewKeyEvent dan onValueChange adalah 2 jalur INDEPENDEN di Android:
                 * - onPreviewKeyEvent: raw KeyEvent dispatch (hardware keyboard)
                 * - onValueChange: IME InputConnection commitText (soft + hard keyboard)
                 * Konsumsi event di jalur 1 TIDAK menghentikan jalur 2.
                 * Solusi: hanya handle special keys di sini (Enter, Backspace, arrows, dll),
                 * biarkan karakter cetak via onValueChange. */
                return false
            }

            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    TabBar(
                        tabs = tabsData, activeTabId = activeExecutorId,
                        onTabSelected = {
                            activeExecutorId = it
                            /* Wave-23: Centralize resolver + permission session scope. */
                            syncToolExecutorToSession(shellExecutors.find { exec -> exec.id == it })
                        },
                        onNewTab = { lifecycleScope.launch { createNewTab() } },
                        onTabClosed = { closeTab(it) },
                        onOpenAI = { toggleAiPanel(0) },
                        onOpenFileExplorer = { showFileExplorer = true },
                        onOpenWorkspace = { showWorkspaceDrawer = true },
                        onOpenSsh = { showSshDialog = true },
                        /* Phase 41 fix (CRIT-04): Sembunyikan tombol Ubuntu di playstore flavor.
                         * ubuntuInstalled=true supaya dot indikator tidak muncul (karena feature
                         * memang tidak ada, bukan "belum diinstall"). */
                        onOpenUbuntu = { lifecycleScope.launch { createUbuntuTab() } },
                        ubuntuInstalled = if (com.tunnel.terminal.BuildConfig.ENABLE_PROOT) prootBootstrap.isInstalled else true,
                        ubuntuEnabled = com.tunnel.terminal.BuildConfig.ENABLE_PROOT,
                        onToggleSplit = {
                            splitMode = !splitMode
                            if (splitMode) {
                                /* Phase 26: Fix race condition — createNewTab async, tapi
                                 * splitPaneId di-set sync. Fix: cari tab lain dulu, jika
                                 * tidak ada, create async lalu set splitPaneId di callback. */
                                val otherTab = shellExecutors.firstOrNull { it.id != activeExecutorId }
                                if (otherTab != null) {
                                    splitPaneId = otherTab.id
                                } else {
                                    /* Hanya 1 tab — buat tab baru async, set splitPaneId
                                     * setelah tab dibuat. */
                                    lifecycleScope.launch {
                                        createNewTab()
                                        /* createNewTab sets activeExecutorId ke tab baru.
                                         * Ambil tab LAMA (sebelumnya aktif) untuk split pane. */
                                        val oldTab = shellExecutors.firstOrNull { it.id != activeExecutorId }
                                        if (oldTab != null) splitPaneId = oldTab.id
                                    }
                                }
                            }
                        },
                        isSplitMode = splitMode,
                        onOpenPalette = { showCommandPalette = true },
                        onTabRename = { id ->
                            renameTabId = id
                            val exec = shellExecutors.find { it.id == id }
                            renameTabDraft = tabLabels[id]
                                ?: exec?.let { defaultTabLabel(it, shellExecutors.indexOf(it) + 1) }
                                ?: "Tab"
                        },
                        onToggleBlockMode = {
                            blockMode = !blockMode
                            if (blockMode) {
                                /* Parse current terminal output ke blocks. */
                                val activeExec = shellExecutors.find { it.id == activeExecutorId }
                                activeExec?.let { blockManager.parseFromOutput(it.getCleanOutput()) }
                            }
                        },
                        isBlockMode = blockMode,
                        theme = currentTheme
                    )

                    /* Phase 21: Split Pane mode — 2 terminals side by side. */
                    if (splitMode) {
                        val splitExecutor = shellExecutors.find { it.id != activeExecutorId }
                        if (splitExecutor != null) splitPaneId = splitExecutor.id
                        Row(modifier = Modifier.weight(1f)) {
                            /* Left pane: active terminal. */
                            Box(modifier = Modifier.weight(1f)) {
                                val focusRequester = remember { FocusRequester() }
                                val keyboardController = LocalSoftwareKeyboardController.current
                                /* Wave-13/20: On tab change restore IME from that tab's buffer. */
                                var lastFocusedId by remember { mutableStateOf(-1) }
                                LaunchedEffect(activeExecutorId) {
                                    if (lastFocusedId != activeExecutorId) {
                                        val buf = shellExecutors.find { it.id == activeExecutorId }
                                            ?.currentCommandBuffer.orEmpty()
                                        syncImeLine(buf)
                                    }
                                    lastFocusedId = activeExecutorId
                                    try { focusRequester.requestFocus(); if (!hasPhysicalKeyboard) keyboardController?.show() } catch (_: Exception) {}
                                }
                                BasicTextField(
                                    value = imeFieldText,
                                    onValueChange = { newValue ->
                                        applyImeValueChange(
                                            newValue, imeFieldLast,
                                            setLast = { imeFieldLast = it },
                                            setHidden = { imeFieldText = it }
                                        )
                                    },
                                    textStyle = TextStyle(color = Color.Transparent),
                                    cursorBrush = SolidColor(Color.Transparent),
                                    modifier = Modifier.fillMaxSize().focusRequester(focusRequester).onPreviewKeyEvent { event -> handleKeyEvent(event) }
                                )
                                TerminalScreenView(
                                    emulator = activeExecutor.emulator, screenDirty = screenDirty,
                                    isAlive = activeExecutor.isAlive,
                                    onRestartSession = { scope.launch { activeExecutor.restart() } },
                                    onResize = { r, c, f -> activeExecutor.resizeTerminal(r, c, f) },
                                    theme = currentTheme,
                                    onTap = { try { focusRequester.requestFocus(); if (!hasPhysicalKeyboard) keyboardController?.show() } catch (_: Exception) {} },
                                    onPasteRequested = { pasteFromClipboard(activeExecutor) },
                                    onOpenUrl = { openExternalUrl(it) },
                                    deadSessionMessage = deadSessionLabel(activeExecutor),
                                    fontSizeState = terminalFontSize,
                                    onFontSizeChange = { applyTerminalFontSize(it) }
                                )
                            }
                            /* Divider. */
                            Box(modifier = Modifier.width(2.dp).fillMaxHeight().background(currentTheme.uiSurface))
                            /* Right pane: second terminal — Wave-13: tap focuses + becomes active
                             * (left pane always shows activeExecutor after swap). */
                            Box(modifier = Modifier.weight(1f)) {
                                splitExecutor?.let { exec ->
                                    val sd by exec.screenDirty.collectAsState()
                                    TerminalScreenView(
                                        emulator = exec.emulator, screenDirty = sd,
                                        isAlive = exec.isAlive,
                                        onRestartSession = { scope.launch { exec.restart() } },
                                        onResize = { r, c, f -> exec.resizeTerminal(r, c, f) },
                                        theme = currentTheme,
                                        onTap = {
                                            /* Activate this session so ExtraKeys/IME target it. */
                                            if (activeExecutorId != exec.id) {
                                                activeExecutorId = exec.id
                                            }
                                        },
                                        onPasteRequested = { pasteFromClipboard(exec) },
                                        onOpenUrl = { openExternalUrl(it) },
                                        deadSessionMessage = deadSessionLabel(exec),
                                        fontSizeState = terminalFontSize,
                                        onFontSizeChange = { applyTerminalFontSize(it) }
                                    )
                                }
                            }
                        }
                    } else if (blockMode) {
                        /* Phase 22: Block mode (Warp-style block terminal).
                         * Phase 24: Tambah BasicTextField untuk input (was missing). */
                        Box(modifier = Modifier.weight(1f)) {
                            val focusRequester = remember { FocusRequester() }
                            val keyboardController = LocalSoftwareKeyboardController.current
                            LaunchedEffect(activeExecutorId, blockMode) {
                                val buf = shellExecutors.find { it.id == activeExecutorId }
                                    ?.currentCommandBuffer.orEmpty()
                                syncImeLine(buf)
                                try { focusRequester.requestFocus(); if (!hasPhysicalKeyboard) keyboardController?.show() } catch (_: Exception) {}
                            }
                            BasicTextField(
                                value = imeFieldText,
                                onValueChange = { newValue ->
                                    applyImeValueChange(
                                        newValue, imeFieldLast,
                                        setLast = { imeFieldLast = it },
                                        setHidden = { imeFieldText = it }
                                    )
                                },
                                textStyle = TextStyle(color = Color.Transparent),
                                cursorBrush = SolidColor(Color.Transparent),
                                modifier = Modifier.fillMaxSize().focusRequester(focusRequester).onPreviewKeyEvent { event -> handleKeyEvent(event) }
                            )
                            BlockTerminalView(
                                blocks = blockManager.blocks,
                                theme = currentTheme,
                                onBlockClick = { /* TODO: navigate to block */ },
                                onBlockRerun = { block ->
                                    shellExecutors.find { it.id == activeExecutorId }?.executeCommand(block.command)
                                },
                                onBlockExplain = { block ->
                                    scope.launch {
                                        handleAIPrompt("Jelaskan output dari command ini:\n$ ${block.command}\n${block.output}")
                                        openAiPanel(0)
                                    }
                                },
                                onToggleCollapse = { id -> blockManager.toggleCollapse(id) },
                                /* Phase 44 fix (MED-02): Pinch-to-zoom sekarang jalan di Block Mode. */
                                fontSizeState = terminalFontSize,
                                onFontSizeChange = { applyTerminalFontSize(it) }
                            )
                        }
                    } else {
                        /* Normal mode: single terminal (existing logic). */
                        Box(modifier = Modifier.weight(1f)) {
                        /* Phase 19.5: FocusRequester untuk auto-focus BasicTextField.
                         * Tap pada terminal area = request focus = show soft keyboard. */
                        val focusRequester = remember { FocusRequester() }
                        val keyboardController = LocalSoftwareKeyboardController.current

                        /* Auto-focus saat tab aktif berubah, agar input langsung ready. */
                        LaunchedEffect(activeExecutorId) {
                            /* Phase 40 fix (H7): Reset enterHandledByKeyEvent saat pindah tab. */
                            enterHandledByKeyEvent = false
                            /* Wave-20: Restore IME from per-tab buffer — do NOT wipe to "".
                             * Clearing IME while currentCommandBuffer still holds text desyncs
                             * next keystroke deltas vs shell line. */
                            val buf = shellExecutors.find { it.id == activeExecutorId }
                                ?.currentCommandBuffer.orEmpty()
                            syncImeLine(buf)
                            try {
                                focusRequester.requestFocus()
                                if (!hasPhysicalKeyboard) keyboardController?.show()
                            } catch (_: Exception) {
                                /* FocusRequester belum siap, coba lagi nanti. */
                            }
                        }

                        BasicTextField(
                            value = imeFieldText,
                            onValueChange = { newValue ->
                                applyImeValueChange(
                                    newValue, imeFieldLast,
                                    setLast = { imeFieldLast = it },
                                    setHidden = { imeFieldText = it }
                                )
                            },
                            textStyle = TextStyle(color = Color.Transparent),
                            cursorBrush = SolidColor(Color.Transparent),
                            modifier = Modifier
                                .fillMaxSize()
                                .focusRequester(focusRequester)
                                .onPreviewKeyEvent { event ->
                                    /* Phase 19.5: Handle SEMUA special keys via preview. */
                                    handleKeyEvent(event)
                                }
                        )

                        /* Phase 19.5: TerminalScreenView DI ATAS (z-order terakhir).
                         * Terima tap/click/scroll, forward focus request ke BasicTextField. */
                        TerminalScreenView(
                            emulator = activeExecutor.emulator,
                            screenDirty = screenDirty,
                            isAlive = activeExecutor.isAlive,
                            onRestartSession = { scope.launch { activeExecutor.restart() } },
                            onResize = { rows, cols, fontSize -> activeExecutor.resizeTerminal(rows, cols, fontSize) },
                            theme = currentTheme,
                            /* Phase 19.5: Tap-to-focus + mouse click support. */
                            onTap = {
                                try {
                                    focusRequester.requestFocus()
                                    if (!hasPhysicalKeyboard) keyboardController?.show()
                                } catch (_: Exception) {}
                            },
                            /* Wave-14: Wheel handled inside TerminalScreenView. */
                            onScroll = { },
                            fontSizeState = terminalFontSize,
                            onFontSizeChange = { applyTerminalFontSize(it) },
                            onPasteRequested = { pasteFromClipboard(activeExecutor) },
                            onOpenUrl = { openExternalUrl(it) },
                            deadSessionMessage = deadSessionLabel(activeExecutor)
                        )
                    }
                    } /* end else (normal mode) */
                    /* Wave-4: Smart autocomplete from history + common commands. */
                    val acSuggestions = remember(activeExecutor.currentCommandBuffer, activeExecutor.commandHistory.size) {
                        SmartAutocomplete.getSuggestions(
                            activeExecutor.currentCommandBuffer,
                            activeExecutor.commandHistory,
                            limit = 8
                        )
                    }
                    if (acSuggestions.isNotEmpty() && activeExecutor.currentCommandBuffer.isNotBlank()) {
                        AutocompleteDropdown(
                            suggestions = acSuggestions,
                            theme = currentTheme,
                            onSelect = { suggestion ->
                                /* Replace current buffer + sync shell line with backspaces then type. */
                                val old = activeExecutor.currentCommandBuffer
                                repeat(old.length) { activeExecutor.writeRaw("\u007F") }
                                activeExecutor.currentCommandBuffer = suggestion
                                activeExecutor.writeRaw(suggestion)
                                /* Wave-11: Keep transparent IME field aligned after autocomplete. */
                                imeFieldText = suggestion
                                imeFieldLast = suggestion
                            }
                        )
                    }
                    ExtraKeysBar(
                        isCtrlActive = isCtrlActive,
                        isAltActive = isAltActive,
                        onKeyPressed = { handleExtraKey(it) },
                        theme = currentTheme,
                        expanded = extraKeysExpanded,
                        onToggleExpanded = {
                            extraKeysExpanded = !extraKeysExpanded
                            getSharedPreferences("TunnelUI", Context.MODE_PRIVATE)
                                .edit().putBoolean("extraKeysExpanded", extraKeysExpanded).apply()
                        }
                    )
                }

                /* Wave-21: FAB toggles right AI panel (terminal stays visible). */
                FloatingActionButton(
                    onClick = { toggleAiPanel(0) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .padding(bottom = 40.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = {
                                    val activeExec = shellExecutors.find { it.id == activeExecutorId }
                                    val ctx = activeExec?.getCleanOutput() ?: ""
                                    val hasError = ctx.lowercase().let {
                                        it.contains("error") || it.contains("not found") ||
                                            it.contains("no such file") || it.contains("permission denied")
                                    }
                                    val prompt = if (hasError) {
                                        "Saya menemukan error di terminal. Berikut outputnya:\n\n$ctx\n\nBagaimana cara memperbaikinya?"
                                    } else {
                                        "Tolong jelaskan apa yang sedang terjadi di terminal saya dan beri saran perintah selanjutnya. Output terminal:\n\n$ctx"
                                    }
                                    openAiPanel(0)
                                    aiJob?.cancel()
                                    aiJob = lifecycleScope.launch { handleAIPrompt(prompt) }
                                }
                            )
                        },
                    containerColor = when {
                        aiPanelOpen && (isProcessingAI || autoPilotRunning) -> currentTheme.uiAccent
                        aiPanelOpen -> Color(0xFF3949AB)
                        agentRunning -> Color(0xFFFF6D00)
                        isProcessingAI || autoPilotRunning -> currentTheme.uiAccent
                        else -> Color(0xFF6200EE)
                    },
                    contentColor = Color.White
                ) {
                    Text(
                        when {
                            aiPanelOpen && (isProcessingAI || autoPilotRunning) -> "●"
                            aiPanelOpen -> "◀"
                            agentRunning -> "🤖"
                            isProcessingAI || autoPilotRunning -> "●"
                            else -> "AI"
                        },
                        fontSize = 16.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (agentRunning && !showAgentScreen) {
                    Text(
                        "Agent berjalan — ketuk untuk buka",
                        color = Color(0xFFFFAB00),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 100.dp)
                            .background(currentTheme.uiSurface, RoundedCornerShape(8.dp))
                            .clickable { showAgentScreen = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            } /* FAB overlay Box */
            } /* else activeExecutor */
            } /* terminal column Box */

            /* Wave-21: AI Copilot — right side panel, terminal remains visible. */
            if (aiPanelOpen) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(currentTheme.uiAccent.copy(alpha = 0.4f))
                )
                Box(
                    modifier = Modifier
                        .width(panelWidthDp)
                        .fillMaxHeight()
                        .background(currentTheme.uiBg)
                ) {
                    AIChatPanel(
                        messages = chatMessages,
                        settings = aiSettings,
                        snippets = snippetsState,
                        theme = currentTheme,
                        themes = ThemeManager.presets,
                        isProcessingAI = isProcessingAI,
                        onSettingsChanged = { saveAISettings(it) },
                        onSendPrompt = { prompt ->
                            aiJob?.cancel()
                            aiJob = scope.launch { handleAIPrompt(prompt) }
                        },
                        onRunCommand = { cmd ->
                            shellExecutors.find { it.id == activeExecutorId }?.executeCommand(cmd)
                        },
                        onRunAutoPilot = { commands ->
                            autoPilotJob?.cancel()
                            autoPilotJob = scope.launch { runAutoPilot(commands) }
                        },
                        onSaveSnippet = { title, cmd -> saveSnippet(title, cmd) },
                        onRunSnippet = { cmd ->
                            shellExecutors.find { it.id == activeExecutorId }?.executeCommand(cmd)
                        },
                        onDeleteSnippet = { id -> deleteSnippet(id) },
                        onThemeChanged = { changeTheme(it) },
                        onClearChat = { clearChat() },
                        onExportChat = { exportChat() },
                        onInsertSnippet = { cmd -> insertIntoTerminal(cmd) },
                        onClose = { closeAiPanel() },
                        pendingImages = pendingImages,
                        onAttachImage = { attachImage() },
                        onRemoveImage = { idx -> removeImage(idx) },
                        availableModels = availableModels,
                        isLoadingModels = isLoadingModels,
                        modelsFetchError = modelsFetchError,
                        onFetchModels = { fetchModels() },
                        onSelectModel = { m -> selectModel(m) },
                        onStopAI = {
                            aiJob?.cancel()
                            aiJob = null
                            isProcessingAI = false
                            val idx = chatMessages.indexOfLast { it.isStreaming }
                            if (idx >= 0) {
                                val m = chatMessages[idx]
                                chatMessages[idx] = m.copy(
                                    content = m.content.ifBlank { "(dihentikan)" } + "\n\n⏹ Dihentikan.",
                                    isStreaming = false
                                )
                            }
                        },
                        onRetryLastPrompt = {
                            val lastUser = chatMessages.lastOrNull { it.role == "user" }?.content
                            if (!lastUser.isNullOrBlank()) {
                                aiJob?.cancel()
                                aiJob = scope.launch { handleAIPrompt(lastUser) }
                            }
                        },
                        autoPilotRunning = autoPilotRunning,
                        autoPilotStep = autoPilotStep,
                        autoPilotTotal = autoPilotTotal,
                        autoPilotCommand = autoPilotCommand,
                        onStopAutoPilot = {
                            autoPilotStopped = true
                            autoPilotJob?.cancel()
                        },
                        initialTab = chatInitialTab,
                        sidePanelMode = true
                    )
                }
            }
        } /* Row side-by-side */
    }

    /** Phase 53 + Wave-12: Safe paste with bracketed mode + IME sync. */
    private fun pasteFromClipboard(executor: TerminalSession) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clipText = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
        if (clipText.isEmpty()) {
            Toast.makeText(this, "Clipboard kosong", Toast.LENGTH_SHORT).show()
            return
        }
        val prepared = PasteUtils.prepare(
            raw = clipText,
            bracketed = executor.emulator.bracketedPaste,
            flattenNewlines = true
        )
        if (prepared.payload.isEmpty()) {
            Toast.makeText(this, "Clipboard kosong", Toast.LENGTH_SHORT).show()
            return
        }
        executor.writeRaw(prepared.payload)
        /* Track single-line form so next keystroke deltas stay consistent. */
        if (prepared.multiLine && executor.emulator.bracketedPaste) {
            /* Bracketed paste inserts into shell; keep our line tracker as append of flattened. */
            executor.currentCommandBuffer += prepared.lineBuffer
            syncImeLine(executor.currentCommandBuffer)
        } else {
            executor.currentCommandBuffer += prepared.lineBuffer
            syncImeLine(executor.currentCommandBuffer)
        }
        val msg = buildString {
            append("Pasted ${prepared.payload.length} chars")
            if (prepared.multiLine && !executor.emulator.bracketedPaste) {
                append(" (newlines → spaces)")
            }
            if (prepared.truncated) append(" [truncated]")
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /**
     * Wave-19: Resolve a local file for storage-put / storage-save-download.
     * Order: absolute path → workspace → home → CWD of active session (best-effort).
     */
    private fun resolveLocalStorageFile(nameOrPath: String): File? {
        val raw = nameOrPath.trim().removePrefix("~/")
        if (raw.isEmpty()) return null
        val candidates = mutableListOf<File>()
        if (nameOrPath.startsWith("/")) candidates.add(File(nameOrPath))
        candidates.add(File(storageManager.workspaceDir, raw))
        candidates.add(File(storageManager.homeDir, raw))
        candidates.add(File(filesDir, raw))
        /* bare filename also under workspace */
        if (!raw.contains('/')) {
            candidates.add(File(storageManager.workspaceDir, nameOrPath))
        }
        return candidates.firstOrNull { it.exists() && it.isFile }
    }

    private fun buildHelpText(): String = """
        ==========================================
        TUNNEL TERMINAL v${BuildConfig.VERSION_NAME} - AI NATIVE DEV ENVIRONMENT
        ==========================================
        Built-in Commands (ditangani lokal):
        - help              Tampilkan menu bantuan ini
        - clear             Bersihkan layar terminal
        - history           Tampilkan riwayat perintah tab ini
        - history-clear     Hapus history (tab + storage)
        - export-output     Export terminal transcript ke filesDir/exports/
        - export-chat       Export percakapan AI
        - copy-output       Salin output terminal ke clipboard
        - tt-find <query>   Cari teks di scrollback (shell find tidak di-intercept)
        - open-url [url]    Buka URL http(s) (atau deteksi dari output)
        - bookmark list     Daftar bookmark direktori
        - bookmark add <n> [path]  Simpan bookmark (default: app home)
        - bookmark go <n|#>        cd ke bookmark
        - bookmark remove <n|#>    Hapus bookmark
        - ai-metrics        Latency / size request AI terakhir
        - font-reset        Reset ukuran font terminal
        - setup-storage     Pilih folder perangkat (SAF; disarankan Download)
        - storage-status    Status grant + path + all-files
        - storage-ls [sub]  List isi folder SAF
        - storage-put <f> [dest]   Workspace → folder SAF
        - storage-get <f> [local]  Folder SAF → workspace
        - storage-write <p> <teks> Tulis teks ke folder SAF
        - storage-save-download <f> [name]  Simpan ke Download publik
        - storage-grant-all Izinkan akses semua file (opsional shell path)
        - storage-rm <p>    Hapus file di folder SAF
        - storage-reset     Cabut grant & reset setup
        - system-info       Tampilkan info sistem (MOTD)
        - open <file>       Edit file di Tunnel Editor UI
        - ssh-list-hostkeys List TOFU fingerprints
        - ssh-reset-hostkeys Reset TOFU host key fingerprints

        Shell / Ubuntu / SSH:
        - Local: toybox sh | Ubuntu: apt/git/python via proot | SSH: remote shell

        AI Copilot:
        - Multi-Provider (OpenAI, Anthropic Native, Gemini, DeepSeek, Groq, Ollama)
        - Tool calling, Agent Mode, MCP HTTP bridge, vision (model-dependent)

        Shortcuts & UX:
        - Volume Up/Down : History (IME-synced, per-tab)
        - Ctrl+K         : Command palette
        - Long-press tab : Rename tab label
        - ExtraKeys      : ^C ^D ^Z ^L ^U ^W, F5–F12, PASTE (safe)
        - CTRL + C       : Hentikan proses yang berjalan
        - Pinch Screen   : Zoom In/Out ukuran font (8–28sp)
        - ExtraKeys A+ A−: Zoom font per 1sp (atau palette)
        - Scroll ↑ + ↓ FAB: Jump to live bottom
        ==========================================
    """.trimIndent()

    private fun resolveAndOpen(fileName: String, executor: TerminalSession) {
        /* Cari file di beberapa lokasi: absolute, ~/home, app filesDir.
         * Search: absolute, ~/home, app filesDir. */
        val candidates = listOf(
            File(fileName),
            File(applicationContext.filesDir, "home/$fileName"),
            File(applicationContext.filesDir, fileName)
        )
        val found = candidates.firstOrNull { it.exists() && it.isFile }
        if (found != null) {
            editingFile = found.absolutePath
            executor.emulator.process("\u001B[32m[Editor] Membuka ${found.name}...\u001B[0m\n")
        } else {
            executor.emulator.process("\u001B[31m[Editor] File tidak ditemukan: $fileName\u001B[0m\n")
        }
        executor.triggerScreenUpdate()
    }

    /* ─── Smart Auto-Pilot ─── */
    /**
     * Auto-Pilot cerdas: jalankan rangkaian perintah, tunggu prompt shell
     * sebelum lanjut ke perintah berikutnya, deteksi error mid-sequence.
     *
     * Smart Auto-Pilot: runs commands sequentially, waits for shell prompt,
     * detects errors mid-sequence.
     */
    /* Phase 37: MarkerExecutor instance untuk command execution dengan marker. */
    private val markerExecutor = MarkerExecutor()

    private suspend fun runAutoPilot(commands: List<String>) {
        val activeExecutor = shellExecutors.find { it.id == activeExecutorId } ?: return
        autoPilotStopped = false
        autoPilotRunning = true
        autoPilotTotal = commands.size
        autoPilotStep = 0
        autoPilotCommand = ""
        chatMessages.add(ChatMessage("assistant", "🚀 Auto-Pilot memulai ${commands.size} langkah…", false))

        try {
            for (i in commands.indices) {
                if (autoPilotStopped) {
                    chatMessages.add(ChatMessage("assistant", "⏹ Auto-Pilot dihentikan oleh pengguna.", false, isError = true))
                    return
                }
                val cmd = commands[i]
                autoPilotStep = i + 1
                autoPilotCommand = cmd
                chatMessages.add(ChatMessage("assistant", "▶ [${i + 1}/${commands.size}] Menjalankan: $cmd", false))

                val outcome = markerExecutor.executeWithMarker(
                    activeExecutor, cmd,
                    maxTimeoutMs = 300000,
                    idleTimeoutMs = 15000
                )
                if (autoPilotStopped) {
                    chatMessages.add(ChatMessage("assistant", "⏹ Auto-Pilot dihentikan oleh pengguna.", false, isError = true))
                    return
                }

                when (outcome) {
                    is MarkerExecutor.ExecutionOutcome.Completed -> {
                        val result = outcome.result
                        val statusIcon = if (result.isSuccess) "✓" else "✗"
                        val outputDisplay = if (result.output.isBlank()) "(no output)" else result.output.take(500)
                        chatMessages.add(ChatMessage(
                            "assistant",
                            "$statusIcon [${i + 1}/${commands.size}] Exit code: ${result.exitCode} (${result.executionTimeMs}ms)\n$outputDisplay",
                            false, isError = !result.isSuccess
                        ))
                        if (!result.isSuccess) {
                            chatMessages.add(ChatMessage(
                                "assistant",
                                "❌ Command gagal (exit code ${result.exitCode}). Auto-Pilot dihentikan.",
                                false, isError = true
                            ))
                            return
                        }
                    }
                    is MarkerExecutor.ExecutionOutcome.PossiblyWaitingForInput -> {
                        val outputDisplay = if (outcome.partialOutput.isBlank()) "(no output)" else outcome.partialOutput.take(500)
                        chatMessages.add(ChatMessage(
                            "assistant",
                            "⚠️ [${i + 1}/${commands.size}] Kemungkinan menunggu input interaktif " +
                                "(idle 15s, elapsed ${outcome.elapsedMs}ms).\n$outputDisplay\n" +
                                "Auto-Pilot dihentikan — periksa output dan beri arahan manual.",
                            false, isError = true
                        ))
                        return
                    }
                    is MarkerExecutor.ExecutionOutcome.TimedOut -> {
                        val outputDisplay = if (outcome.partialOutput.isBlank()) "(no output)" else outcome.partialOutput.take(500)
                        chatMessages.add(ChatMessage(
                            "assistant",
                            "⚠️ [${i + 1}/${commands.size}] Timeout (5 menit) menunggu command selesai.\n$outputDisplay\n" +
                                "Auto-Pilot dihentikan.",
                            false, isError = true
                        ))
                        return
                    }
                }
            }
            chatMessages.add(ChatMessage("assistant", "✅ Auto-Pilot selesai! Semua ${commands.size} perintah berhasil.", false))
        } finally {
            autoPilotRunning = false
            autoPilotStep = 0
            autoPilotTotal = 0
            autoPilotCommand = ""
            autoPilotStopped = false
        }
    }

    /**
     * Tunggu sampai prompt shell muncul kembali di output terminal.
     * Wait for shell prompt to reappear.
     */
    private suspend fun waitForPrompt(executor: TerminalSession, outputBeforeLen: Int, timeoutMs: Long): Boolean {
        val startTime = System.currentTimeMillis()
        /* BUG-10 fix: Perketat regex — hanya match prompt spesifik Tunnel Terminal,
         * bukan baris apa pun yang berakhir dengan $ atau #. */
        val promptRegex = Regex("""tunnel@android:[^\$]*\$\s*$|[a-zA-Z_][a-zA-Z0-9_]*@[a-zA-Z0-9.-]+:[^\$#]*[\$#]\s*$""")
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val current = executor.getCleanOutput()
            if (current.length > outputBeforeLen + 2) {
                /* Cek apakah ada prompt di akhir output. */
                val tail = current.takeLast(100)
                if (promptRegex.containsMatchIn(tail)) return true
            }
            delay(150)
        }
        return false
    }

    /**
     * Handle AI prompt dengan streaming SSE + multi-turn conversation memory.
     *
     * Phase 18:
     * - Tambah user message ke chatMessages (untuk display + conversation history)
     * - Buat placeholder assistant message dengan isStreaming=true
     * - Koleksi token dari askAIStreaming() Flow, append ke placeholder
     * - Saat stream selesai, parse bash blocks untuk deteksi command/autopilot
     * - Conversation history dikirim ke AI untuk multi-turn context
     *
     * Streaming SSE handler with multi-turn memory.
     */
    private suspend fun handleAIPrompt(prompt: String, fromToolResult: Boolean = false) {
        /* Wave-2: Reset tool loop when user starts a fresh prompt. */
        if (!fromToolResult) toolLoopDepth = 0
        if (isProcessingAI && !fromToolResult) {
            /* Wave-17: Cancel previous stream instead of only warning. */
            aiJob?.cancel()
            delay(50)
        }
        isProcessingAI = true

        /* Tambah user message ke history (untuk display + multi-turn memory).
         * Phase 19: Attach pending images jika ada.
         * Phase 40 fix (H8): Strip @mentions dari user message sebelum kirim ke AI.
         * OLD BUG: User ketik "@file:foo.txt jelaskan ini" → AI terima pesan dengan
         * "@file:foo.txt" yang bukan syntax yang AI kenal → AI bingung.
         * FIX: Hapus mentions dari prompt, kirim content mention sebagai context terpisah. */
        val imagesToSend = pendingImages.toList()
        val cleanPrompt = contextManager.stripMentions(prompt)
        val userMsg = ChatMessage(
            role = "user",
            content = if (cleanPrompt.isBlank() && imagesToSend.isNotEmpty()) "Tolong analisa gambar ini." else cleanPrompt,
            conversationRole = "user",
            images = imagesToSend
        )
        chatMessages.add(userMsg)
        trimChatIfNeeded()
        /* Clear pending images setelah di-attach ke pesan. */
        pendingImages.clear()

        val activeExecutor = shellExecutors.find { it.id == activeExecutorId }
        /* Wave-23: Always re-sync path resolver before tools/AI path advice (Ubuntu/local/SSH). */
        syncToolExecutorToSession(activeExecutor)
        val terminalContext = activeExecutor?.getCleanOutput() ?: ""
        val sessionPathAdvice = toolExecutor.sessionPathInstructions()

        /* Phase 23: Resolve @context mentions (@file, @block, @command, @terminal, @snippet).
         * Phase 37: @command: sekarang dieksekusi secara nyata via MarkerExecutor. */
        val (resolvedMentions, mentionContext) = contextManager.resolveAll(
            text = prompt,
            blockManager = blockManager,
            terminalSession = activeExecutor,
            snippetManager = snippetManager
        )

        /* Phase 37: Resolve @command: mentions secara async dengan MarkerExecutor. */
        val commandMentions = resolvedMentions.filter { it.type == ContextManager.MentionType.COMMAND }
        val asyncCommandContext = if (commandMentions.isNotEmpty() && activeExecutor != null) {
            val sb = StringBuilder()
            for (cm in commandMentions) {
                val resolved = contextManager.resolveCommandAsync(cm.mention, activeExecutor, markerExecutor)
                sb.append("\n[${resolved.displayName}]:\n${resolved.content}\n")
            }
            sb.toString()
        } else ""

        val fullContext = buildString {
            append(terminalContext)
            append(mentionContext)
            append(asyncCommandContext)
            /* Wave-23: Remind model of path rules for active tab every turn. */
            if (sessionPathAdvice.isNotBlank()) {
                append("\n\n").append(sessionPathAdvice)
            }
        }

        /* Placeholder assistant message yang akan di-update selama streaming.
         * Placeholder assistant message updated during streaming. */
        val streamingMsg = ChatMessage(
            role = "assistant",
            content = "",
            isStreaming = true,
            conversationRole = "assistant"
        )
        chatMessages.add(streamingMsg)
        val streamingIdx = chatMessages.size - 1

        val fullResponse = StringBuilder()
        var firstChunk = true
        /* Phase 19 hotfix: Flow.collect pakai crossinline lambda, jadi non-local return
         * dilarang. Pakai flag aborted untuk handle client-side error tanpa return. */
        var abortedWithError: String? = null

        try {
            /* Koleksi token-by-token dari streaming Flow. */
            /* Phase 23: Pass fullContext (dengan @mentions resolved) ke AI. */
            val sessionType = activeExecutor?.sessionType ?: "local"
            val envDesc = buildString {
                append(activeExecutor?.environmentDescription ?: "")
                if (sessionType == "ubuntu") {
                    append(" | AI file tools → guest /root ; bind workspace → /mnt/workspace")
                }
            }
            /* Wave-23: Project context from Ubuntu /root when on proot tab. */
            val projectRoot = if (sessionType == "ubuntu" && ::prootBootstrap.isInitialized) {
                File(prootBootstrap.rootfsDir, "root")
            } else {
                toolExecutor.workspaceRootFile()
            }
            aiAgent.askAIStreaming(
                aiSettings, chatMessages.toList(), fullContext,
                sessionType,
                envDesc,
                /* Phase 50 fix (B-5): Inject project context (git, manifests, file tree). */
                projectContext.buildContext(projectRoot, sessionType),
                /* Phase 58 fix (§4.6): Inject task plan (imun dari cap 20 pesan). */
                taskPlanManager.renderForSystemPrompt()
            ).collect { delta ->
                if (abortedWithError != null) return@collect  /* skip further chunks */
                if (firstChunk) {
                    firstChunk = false
                    /* Cek apakah delta pertama adalah error message (client-side error). */
                    val isClientError = delta.startsWith("Error") || delta.startsWith("Timeout") ||
                                        delta.startsWith("Kesalahan") || delta.startsWith("API Key") ||
                                        delta.startsWith("DNS") || delta.startsWith("SSL") ||
                                        delta.startsWith("Akses ditolak") || delta.startsWith("Endpoint") ||
                                        delta.startsWith("Rate limit") || delta.startsWith("Server")
                    if (isClientError) {
                        abortedWithError = delta
                        chatMessages[streamingIdx] = streamingMsg.copy(
                            content = delta,
                            isStreaming = false,
                            isError = true
                        )
                        return@collect  /* stop processing this chunk */
                    }
                }
                fullResponse.append(delta)
                /* Update placeholder dengan content yang sudah ter-akumulasi.
                 * Update placeholder with accumulated content. */
                chatMessages[streamingIdx] = streamingMsg.copy(
                    content = fullResponse.toString(),
                    isStreaming = true
                )
            }

            /* Kalau client error di chunk pertama, exit early (return di sini allowed
             * karena di suspend fun langsung, bukan di lambda). */
            if (abortedWithError != null) {
                isProcessingAI = false
                return
            }

            /* Stream selesai. Parse bash blocks untuk deteksi command/autopilot.
             * Stream done. Parse bash blocks to detect command/autopilot. */
            val response = fullResponse.toString()
            val bashRegex = Regex("```(?:bash|sh|shell)?\\n([\\s\\S]*?)\\n```")
            val matches = bashRegex.findAll(response).toList()

            if (matches.size > 1) {
                val commands = matches.map { it.groupValues[1].trim() }
                val explanation = response.substring(0, matches.first().range.first).trim()
                val finalContent = if (explanation.isNotEmpty()) explanation else
                    "Rangkaian perintah siap dieksekusi (${commands.size} langkah)."
                /* Replace streaming message: keep explanation as content, attach commands. */
                chatMessages[streamingIdx] = streamingMsg.copy(
                    content = finalContent,
                    isStreaming = false,
                    commands = commands
                )
            } else if (matches.size == 1) {
                val command = matches[0].groupValues[1].trim()
                val explanation = response.substring(0, matches[0].range.first).trim()
                val finalContent = if (explanation.isNotEmpty()) explanation else command
                chatMessages[streamingIdx] = streamingMsg.copy(
                    content = finalContent,
                    isStreaming = false,
                    isCommand = true,
                    commands = listOf(command)
                )
            } else {
                /* No bash block - just text response. Keep accumulated content. */
                chatMessages[streamingIdx] = streamingMsg.copy(
                    content = response,
                    isStreaming = false
                )
            }

            /* Phase 22: Process AI tool calls (function calling).
             * Parse <tool_call> dari response, execute dengan permission flow. */
            processToolCalls(response)
        } catch (e: Exception) {
            /* Stream cancelled or error mid-stream. Keep partial content + mark as error. */
            val partial = fullResponse.toString()
            chatMessages[streamingIdx] = streamingMsg.copy(
                content = if (partial.isBlank()) "Stream terputus: ${e.message}" else partial,
                isStreaming = false,
                isError = partial.isBlank()
            )
        } finally {
            isProcessingAI = false
            /* Wave-10: Optional haptic when a long AI response finishes. */
            maybeVibrateOnAiDone(fullResponse.length)
        }
    }

    /** Wave-10: Short haptic feedback after AI replies (opt-in, default on for long replies). */
    private fun maybeVibrateOnAiDone(responseChars: Int) {
        if (responseChars < 200) return
        val enabled = getSharedPreferences("TunnelUI", Context.MODE_PRIVATE)
            .getBoolean("vibrateOnAiDone", true)
        if (!enabled) return
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    android.os.VibrationEffect.createOneShot(
                        40,
                        android.os.VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(40)
            }
        } catch (_: Exception) {
            /* ignore — haptic is best-effort */
        }
    }
}
