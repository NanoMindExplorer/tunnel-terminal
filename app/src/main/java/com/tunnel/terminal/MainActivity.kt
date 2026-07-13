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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
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

    /** SAF launcher - dipanggil saat user ketik `setup-storage`. */
    private val storageLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val ok = storageManager.persistTreeUri(uri)
            val msg = if (ok) {
                storageManager.createStorageSymlink()
            } else {
                "Gagal mengambil persistable permission untuk URI: $uri"
            }
            /* Output ke terminal aktif. */
            shellExecutors.find { it.id == activeExecutorId }?.let { exec ->
                exec.emulator.process("\n\u001B[32m$msg\u001B[0m\n")
                exec.triggerScreenUpdate()
            }
        } else {
            shellExecutors.find { it.id == activeExecutorId }?.let { exec ->
                exec.emulator.process("\n\u001B[33mSetup storage dibatalkan.\u001B[0m\n")
                exec.triggerScreenUpdate()
            }
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
        permissionManager = PermissionManager(this)
        contextManager = ContextManager(this)
        mcpManager = McpManager(this)
        /* Phase 60 fix (audit B-2): Set mcpManager ke aiAgent supaya MCP tools
         * bisa di-inject dinamis ke TOOL_SCHEMA di setiap request AI. */
        aiAgent.setMcpManager(mcpManager)
        agentWorkflowManager = AgentWorkflowManager(this)
        /* Phase 47 (Bagian 2): Init AgentTaskRunner. */
        agentTaskRunner = AgentTaskRunner(aiAgent, toolExecutor, permissionManager, markerExecutor)
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
            terminalFontSize = getSharedPreferences("TunnelUI", Context.MODE_PRIVATE).getFloat("fontSize", 12f)
        } catch (_: Exception) {
            terminalFontSize = 12f
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

    /** Execute single tool call. */
    private fun executeToolCall(call: AiToolCall, alwaysAllow: Boolean) {
        chatMessages.add(ChatMessage("assistant", "🔧 Tool: ${call.displayText}", false))

        when {
            call.tool == "run_command" -> {
                /* Phase 37: run_command pakai MarkerExecutor — bukan fire-and-forget.
                 * Phase 46 (Pilar 1b): Handle ExecutionOutcome (3 kemungkinan).
                 * Hasil (output + exit code) dikirim balik ke AI sebagai context. */
                val cmd = call.args["cmd"] ?: return
                val session = shellExecutors.find { it.id == activeExecutorId }
                if (session != null) {
                    chatMessages.add(ChatMessage("assistant", "🔧 Running: $cmd", false))
                    lifecycleScope.launch {
                        val outcome = markerExecutor.executeWithMarker(
                            session, cmd,
                            maxTimeoutMs = 300000,  // 5 min — apt install bisa lama
                            idleTimeoutMs = 15000   // 15s idle = curiga nunggu input
                        )
                        val outcomeText = markerExecutor.formatOutcomeForAI(outcome)
                        chatMessages.add(ChatMessage("assistant", "📋 Result:\n$outcomeText", false))
                        handleAIPrompt("Berikut hasil eksekusi command:\n$outcomeText\n\nApakah perlu perbaikan atau langkah selanjutnya?")
                    }
                }
            }
            call.tool == "write_file" -> {
                /* Phase 23: Inline diff view untuk AI file edits.
                 * Wave-1: Resolve path via ToolExecutor sandbox — never raw File(path). */
                val path = call.args["path"] ?: return
                val content = call.args["content"] ?: return
                try {
                    val file = toolExecutor.resolvePathForAccess(path)
                    val original = if (file.exists() && file.isFile) {
                        try { file.readText() } catch (e: Exception) { "" }
                    } else ""
                    if (original != content) {
                        /* Store resolved absolute path so Apply hits the same file. */
                        pendingDiff = Triple(file.absolutePath, original, content)
                    } else {
                        chatMessages.add(ChatMessage("assistant", "No changes needed for ${file.absolutePath}", false))
                    }
                } catch (e: SecurityException) {
                    chatMessages.add(ChatMessage("assistant", "Error: ${e.message}", false, isError = true))
                } catch (e: Exception) {
                    chatMessages.add(ChatMessage("assistant", "Error resolving path: ${e.message}", false, isError = true))
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
                    }
                }
            }
            else -> {
                val result = toolExecutor.execute(call)
                chatMessages.add(ChatMessage("assistant", "📋 Result:\n$result", false))
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
    private fun parseWorkingDir(prompt: String): String {
        val regex = Regex("""tunnel@android:([^\$]+)\$\s*$""")
        val match = regex.find(prompt) ?: return ""
        return match.groupValues[1].trim()
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
        val securePrefs = SecureStorage.getAIPrefs(this)
        /* C3 fix: Wrap getDouble dalam try-catch — ClassCastException jika preference corrupt
         * atau disimpan sebagai tipe lain oleh versi lama. */
        val temperature = try { prefs.getDouble("temperature", 0.2) } catch (_: Exception) { 0.2 }
        aiSettings = AISettings(
            providerName = prefs.getString("providerName", "OpenAI") ?: "OpenAI",
            baseUrl = prefs.getString("baseUrl", "https://api.openai.com/v1") ?: "https://api.openai.com/v1",
            apiKey = securePrefs.getString("apiKey", "") ?: "",
            modelName = prefs.getString("modelName", "gpt-4o-mini") ?: "gpt-4o-mini",
            temperature = temperature,
            maxTokens = prefs.getInt("maxTokens", 2000),
            requestTimeoutMs = prefs.getInt("requestTimeoutMs", 30000),
            supportsVision = prefs.getBoolean("supportsVision", false),
            supportsToolCalling = prefs.getBoolean("supportsToolCalling", false)
        )
    }

    private fun saveAISettings(newSettings: AISettings) {
        aiSettings = newSettings
        /* Phase 41 fix (CRIT-01): apiKey disimpan di encrypted prefs, sisanya plaintext. */
        val prefs = getSharedPreferences("TunnelAIPrefs", Context.MODE_PRIVATE).edit()
        prefs.putString("providerName", newSettings.providerName)
        prefs.putString("baseUrl", newSettings.baseUrl)
        prefs.putString("modelName", newSettings.modelName)
        prefs.putDouble("temperature", newSettings.temperature)
        prefs.putInt("maxTokens", newSettings.maxTokens)
        prefs.putInt("requestTimeoutMs", newSettings.requestTimeoutMs)
        prefs.putBoolean("supportsVision", newSettings.supportsVision)
        prefs.putBoolean("supportsToolCalling", newSettings.supportsToolCalling)
        prefs.apply()

        val securePrefs = SecureStorage.getAIPrefs(this).edit()
        securePrefs.putString("apiKey", newSettings.apiKey)
        securePrefs.apply()
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
        executor.start()

        // Beri MOTD khusus Ubuntu.
        executor.emulator.process(
            "\u001B[32m┌─ Ubuntu 24.04 (proot) ─────────────────────────────┐\u001B[0m\n" +
            "\u001B[32m│ Linux environment via proot — no root required     │\u001B[0m\n" +
            "\u001B[32m│ apt update && apt install <pkg> untuk install tool │\u001B[0m\n" +
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

        /* Phase 60 fix (audit C-5): NonCancellable context supaya download
         * tidak dibatalkan saat app di-background. Download 29MB butuh waktu
         * lama di koneksi lambat. lifecycleScope akan cancel coroutine kalau
         * Activity di-destroy, tapi NonCancellable memastikan install() selesai. */
        lifecycleScope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    prootBootstrap.install(ProotBootstrap.ProgressListener { stage, percent ->
                        ubuntuInstallStage = stage
                        ubuntuInstallPercent = percent
                    })
                }
                // Sukses — tutup dialog, buka tab Ubuntu.
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

    /* ─── Volume key command history navigation (per-active-executor) ─── */
    /* Phase 20: Only intercept volume keys when terminal is focused
     * (not when AI drawer / editor / file explorer is open).
     * Old code intercepted ALL volume keys globally — annoying when
     * user wants to adjust media volume in other contexts. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        /* Only intercept volume keys when no overlay is open. */
        val terminalFocused = editingFile == null && !showFileExplorer && !showWorkspaceDrawer
        if (terminalFocused) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                navigateHistory(forward = false); return true
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                navigateHistory(forward = true); return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        val terminalFocused = editingFile == null && !showFileExplorer && !showWorkspaceDrawer
        if (terminalFocused) {
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
        }
    }

    /* ─── Compose UI ─── */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun TerminalApp() {
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()

        var isCtrlActive by remember { mutableStateOf(false) }
        var isAltActive by remember { mutableStateOf(false) }

        /* Phase 24.5: Process pending voice text — kirim sebagai AI prompt + open drawer. */
        val voiceText by _pendingVoiceText
        LaunchedEffect(voiceText) {
            if (voiceText.isNotBlank()) {
                drawerState.open()
                handleAIPrompt(voiceText)
                _pendingVoiceText.value = ""
            }
        }

        /* Back button handler: tutup drawer/editor dulu sebelum exit. */
        BackHandler(enabled = editingFile != null || drawerState.isOpen) {
            when {
                editingFile != null -> editingFile = null
                drawerState.isOpen -> scope.launch { drawerState.close() }
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
                add(PaletteItem("ai_explain", "Ask AI to explain last output", "AI", Icons.Default.Psychology, PaletteCategory.AI) { scope.launch { handleAIPrompt("Jelaskan output terminal terakhir."); drawerState.open() } })
                add(PaletteItem("ai_fix", "Ask AI to fix errors", "AI", Icons.Default.Build, PaletteCategory.AI) { scope.launch { handleAIPrompt("Perbaiki error di terminal."); drawerState.open() } })
                add(PaletteItem("ai_autopilot", "Open AI Auto-Pilot", "AI", Icons.Default.SmartToy, PaletteCategory.AI) { scope.launch { drawerState.open() } })
                /* Navigation. */
                add(PaletteItem("new_tab", "New tab", "Navigation", Icons.Default.Add, PaletteCategory.NAVIGATION) { lifecycleScope.launch { createNewTab() } })
                add(PaletteItem("close_tab", "Close current tab", "Navigation", Icons.Default.Close, PaletteCategory.NAVIGATION) { closeTab(activeExecutorId) })
                add(PaletteItem("toggle_split", "Toggle split pane", "Navigation", Icons.Default.ViewColumn, PaletteCategory.NAVIGATION) { splitMode = !splitMode })
                add(PaletteItem("toggle_block", "Toggle block mode", "Navigation", Icons.Default.ViewModule, PaletteCategory.NAVIGATION) {
                    blockMode = !blockMode
                    if (blockMode) {
                        shellExecutors.find { it.id == activeExecutorId }?.let { blockManager.parseFromOutput(it.getCleanOutput()) }
                    }
                })
                /* Settings. */
                add(PaletteItem("open_settings", "Open AI Settings", "Setting", Icons.Default.Settings, PaletteCategory.SETTING) { scope.launch { drawerState.open() } })
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
                add(PaletteItem("cmd_help", "Run: help", "Command", Icons.Default.Help, PaletteCategory.COMMAND) { shellExecutors.find { it.id == activeExecutorId }?.executeCommand("help") })
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
                onExecute = { it.action() },
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
                    chatMessages.add(ChatMessage("assistant", "Permission denied for: ${call.displayText}", false, isError = true))
                    pendingToolCall = null
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
                },
                onReject = {
                    chatMessages.add(ChatMessage("assistant", "Changes rejected for ${java.io.File(path).name}", false))
                    pendingDiff = null
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
                onDismiss = { showAgentScreen = false }
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

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(modifier = Modifier.fillMaxHeight(0.9f).background(currentTheme.uiBg)) {
                    AIChatPanel(
                        messages = chatMessages,
                        settings = aiSettings,
                        snippets = snippetsState,
                        theme = currentTheme,
                        themes = ThemeManager.presets,
                        isProcessingAI = isProcessingAI,
                        onSettingsChanged = { saveAISettings(it) },
                        onSendPrompt = { prompt -> scope.launch { handleAIPrompt(prompt) } },
                        onRunCommand = { cmd ->
                            shellExecutors.find { it.id == activeExecutorId }?.executeCommand(cmd)
                            scope.launch { drawerState.close() }
                        },
                        onRunAutoPilot = { commands ->
                            scope.launch { runAutoPilot(commands); drawerState.close() }
                        },
                        onSaveSnippet = { title, cmd -> saveSnippet(title, cmd) },
                        onRunSnippet = { cmd ->
                            shellExecutors.find { it.id == activeExecutorId }?.executeCommand(cmd)
                            scope.launch { drawerState.close() }
                        },
                        onDeleteSnippet = { id -> deleteSnippet(id) },
                        onThemeChanged = { changeTheme(it) },
                        onClearChat = { clearChat() },
                        onClose = { scope.launch { drawerState.close() } },
                        /* Phase 19: Image Vision. */
                        pendingImages = pendingImages,
                        onAttachImage = { attachImage() },
                        onRemoveImage = { idx -> removeImage(idx) },
                        /* Phase 19: Model fetcher. */
                        availableModels = availableModels,
                        isLoadingModels = isLoadingModels,
                        modelsFetchError = modelsFetchError,
                        onFetchModels = { fetchModels() },
                        onSelectModel = { m -> selectModel(m) }
                    )
                }
            }
        ) {
            val activeExecutor = shellExecutors.find { it.id == activeExecutorId } ?: shellExecutors.firstOrNull()
            if (activeExecutor == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Inisialisasi Tunnel Terminal...", color = Color.White, fontFamily = FontFamily.Monospace)
                }
                return@ModalNavigationDrawer
            }

            val screenDirty by activeExecutor.screenDirty.collectAsState()
            val tabsData = shellExecutors.mapIndexed { index, executor -> Pair(executor.id, index + 1) }
            var hiddenInput by remember { mutableStateOf("") }

            /* Phase 33 (A2 fix): Deteksi keyboard fisik — jangan paksa soft keyboard muncul.
             * Old code: if (!hasPhysicalKeyboard) keyboardController?.show() selalu dipanggil → soft keyboard muncul
             * bahkan saat physical keyboard aktif → adjustResize mengecilkan terminal →
             * layar "naik ke atas", text tidak kelihatan. */
            val configuration = LocalConfiguration.current
            val hasPhysicalKeyboard = configuration.keyboard == android.content.res.Configuration.KEYBOARD_QWERTY &&
                configuration.hardKeyboardHidden == android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO

            /* Phase 36: Flag untuk mencegah Enter double-fire antara handleKeyEvent
             * (physical keyboard KeyDown) dan onValueChange (soft keyboard commitText). */
            var enterHandledByKeyEvent by remember { mutableStateOf(false) }

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
                val ansiCode: String = when (key) {
                    "ESC" -> "\u001B"
                    "TAB" -> "\t"
                    "↑" -> "\u001B[A"
                    "↓" -> "\u001B[B"
                    "→" -> "\u001B[C"
                    "←" -> "\u001B[D"
                    "HOME" -> "\u001B[H"
                    "END" -> "\u001B[F"
                    "PGUP" -> "\u001B[5~"
                    "PGDN" -> "\u001B[6~"
                    "BKSP" -> "\u007F"
                    "DEL" -> "\u001B[3~"
                    "CTRL" -> { isCtrlActive = !isCtrlActive; "" }
                    "ALT" -> { isAltActive = !isAltActive; "" }
                    else -> key
                }
                if (ansiCode.isNotEmpty()) activeExecutor.writeRaw(ansiCode)
            }

            fun processInput(input: String) {
                val cmd = input.trim().replace("\n", "")

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
                 * baris shell asli dengan mengirim backspace (\u007F) sebanyak panjang cmd.
                 * Teknik ini valid karena PTY dalam mode "cooked" (default saat menunggu
                 * prompt) menangani backspace di level kernel: menghapus karakter terakhir
                 * dari buffer baris internalnya sebelum di-submit.
                 *
                 * Berlaku untuk SEMUA pseudo-command lokal: help, clear, setup-storage,
                 * storage-status, storage-reset, ssh-reset-hostkeys, system-info, open,
                 * dan systemctl intercept di tab Ubuntu. Branch `else` (command shell biasa)
                 * TIDAK perlu di-backspace karena karakternya memang harus sampai ke shell. */
                val isLocalOnly = cmd == "help" || cmd == "clear" || cmd == "setup-storage" ||
                    cmd == "storage-status" || cmd == "storage-reset" ||
                    cmd == "ssh-reset-hostkeys" || cmd == "system-info" ||
                    cmd.startsWith("open ") ||
                    (activeExecutor.sessionType == "ubuntu" &&
                        (cmd.startsWith("systemctl ") || cmd.startsWith("service ")))

                if (isLocalOnly && cmd.isNotEmpty()) {
                    /* Kirim backspace sebanyak panjang cmd untuk hapus dari buffer shell.
                     * \u007F = DEL (backspace di terminal). */
                    repeat(cmd.length) { activeExecutor.writeRaw("\u007F") }
                }

                if (cmd.isNotEmpty()) {
                    val h = activeExecutor.commandHistory
                    if (h.isEmpty() || h.last() != cmd) h.add(cmd)
                    if (h.size > 500) h.removeAt(0)
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
                    }
                    cmd == "clear" -> {
                        /* Clear screen buffer lokal - tidak kirim ke shell.
                         * Local clear - don't send to shell. */
                        activeExecutor.clearScreen()
                    }
                    cmd == "setup-storage" -> {
                        activeExecutor.emulator.process(
                            "\n\u001B[36m[Setup Storage] Membuka picker folder...\u001B[0m\n" +
                            "\u001B[33mPilih folder yang ingin diakses (biasanya /sdcard atau Documents).\u001B[0m\n"
                        )
                        activeExecutor.triggerScreenUpdate()
                        storageLauncher.launch(null)
                    }
                    cmd == "storage-status" -> {
                        val report = storageManager.statusReport()
                        activeExecutor.emulator.process("\n$report\n")
                        activeExecutor.triggerScreenUpdate()
                    }
                    cmd == "storage-reset" -> {
                        storageManager.clearSetup()
                        activeExecutor.emulator.process("\n\u001B[33m[Storage] Setup direset. Ketik 'setup-storage' untuk konfigurasi ulang.\u001B[0m\n")
                        activeExecutor.triggerScreenUpdate()
                    }
                    cmd == "ssh-reset-hostkeys" -> {
                        /* BUG-02: Reset SSH host key fingerprints (TOFU). */
                        getSharedPreferences("TunnelSshHostKeys", Context.MODE_PRIVATE).edit().clear().apply()
                        activeExecutor.emulator.process("\n\u001B[33m[SSH] Semua host key fingerprints direset. Koneksi berikutnya akan menerima host key baru.\u001B[0m\n")
                        activeExecutor.triggerScreenUpdate()
                    }
                    cmd == "system-info" -> {
                        val info = SystemInfo.buildMotd(this@MainActivity)
                        activeExecutor.emulator.process(info)
                        activeExecutor.triggerScreenUpdate()
                    }
                    cmd.startsWith("open ") -> {
                        val fileName = cmd.removePrefix("open ").trim()
                        resolveAndOpen(fileName, activeExecutor)
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
                    /* Ctrl+<char> = char code - 'a' + 1 (e.g. Ctrl+C = 3) */
                    return (char.lowercaseChar() - 'a' + 1).toChar().toString()
                }
                if (isAltActive) {
                    isAltActive = false
                    return "\u001B$char"
                }
                return char.toString()
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
                    val ch = when (key) {
                        Key.C -> 3.toChar()
                        Key.D -> 4.toChar()
                        Key.Z -> 26.toChar()
                        Key.L -> 12.toChar()
                        Key.A -> 1.toChar()
                        Key.E -> 5.toChar()
                        Key.K -> 11.toChar()
                        Key.U -> 21.toChar()
                        Key.W -> 23.toChar()
                        Key.R -> 18.toChar()
                        Key.X -> 24.toChar()
                        else -> '\u0000'
                    }
                    if (ch != '\u0000') {
                        activeExecutor.writeRaw(ch.toString())
                        return true
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
                        hiddenInput = ""
                        return true
                    }
                    Key.Backspace -> {
                        if (activeExecutor.currentCommandBuffer.isNotEmpty()) {
                            activeExecutor.currentCommandBuffer = activeExecutor.currentCommandBuffer.dropLast(1)
                        }
                        activeExecutor.writeRaw("\u007F")
                        return true
                    }
                    Key.Tab -> { activeExecutor.writeRaw("\t"); return true }
                    Key.DirectionUp -> { activeExecutor.writeRaw("\u001B[A"); return true }
                    Key.DirectionDown -> { activeExecutor.writeRaw("\u001B[B"); return true }
                    Key.DirectionRight -> { activeExecutor.writeRaw("\u001B[C"); return true }
                    Key.DirectionLeft -> { activeExecutor.writeRaw("\u001B[D"); return true }
                    Key.Escape -> { activeExecutor.writeRaw("\u001B"); return true }
                    Key.MoveHome -> { activeExecutor.writeRaw("\u001B[H"); return true }
                    Key.MoveEnd -> { activeExecutor.writeRaw("\u001B[F"); return true }
                    Key.PageUp -> { activeExecutor.writeRaw("\u001B[5~"); return true }
                    Key.PageDown -> { activeExecutor.writeRaw("\u001B[6~"); return true }
                    Key.Delete -> { activeExecutor.writeRaw("\u001B[3~"); return true }
                    Key.F1 -> { activeExecutor.writeRaw("\u001BOP"); return true }
                    Key.F2 -> { activeExecutor.writeRaw("\u001BOQ"); return true }
                    Key.F3 -> { activeExecutor.writeRaw("\u001BOR"); return true }
                    Key.F4 -> { activeExecutor.writeRaw("\u001BOS"); return true }
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
                            /* Phase 43 fix (HIGH-05): Update session aktif di PermissionManager
                             * supaya permission "Always Allow" di-scope per-tab. */
                            permissionManager.setActiveSession(it)
                            /* Phase 57 fix (§4.1): Update SessionTargetResolver saat pindah tab
                             * supaya write_file/read_file tahu target yang benar (Local/Ubuntu/SSH). */
                            val activeExec = shellExecutors.find { exec -> exec.id == it }
                            if (activeExec != null) {
                                val resolver = SessionTargetResolver(
                                    sessionType = activeExec.sessionType,
                                    workspaceRoot = toolExecutor.workspaceRootFile(),
                                    rootfsDir = if (activeExec.sessionType == "ubuntu" && ::prootBootstrap.isInitialized) prootBootstrap.rootfsDir else null
                                )
                                toolExecutor.setSessionTargetResolver(resolver)
                                /* Phase 58 fix (§4.1-D): Set SshShellExecutor reference untuk SFTP. */
                                if (activeExec is SshShellExecutor) {
                                    toolExecutor.setSshExecutor(activeExec)
                                } else {
                                    toolExecutor.setSshExecutor(null)
                                }
                            }
                            /* Phase 19.5: currentCommandBuffer & historyIndex sekarang per-tab
                             * (disimpan di ShellExecutor), tidak perlu reset di sini. */
                        },
                        onNewTab = { lifecycleScope.launch { createNewTab() } },
                        onTabClosed = { closeTab(it) },
                        onOpenAI = { scope.launch { drawerState.open() } },
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
                                LaunchedEffect(activeExecutorId) {
                                    try { focusRequester.requestFocus(); if (!hasPhysicalKeyboard) keyboardController?.show() } catch (_: Exception) {}
                                }
                                var lastInputValue by remember { mutableStateOf("") }
                                BasicTextField(
                                    value = hiddenInput,
                                    onValueChange = { newValue ->
                                        val oldText = lastInputValue
                                        lastInputValue = newValue
                                        if (newValue.length > oldText.length) {
                                            val added = newValue.substring(oldText.length)
                                            for (ch in added) {
                                                when (ch) {
                                                    '\n', '\r' -> {
                                                        processInput(activeExecutor.currentCommandBuffer + "\n")
                                                        activeExecutor.currentCommandBuffer = ""
                                                        hiddenInput = ""; lastInputValue = ""
                                                    }
                                                    '\u007F', '\b' -> { if (activeExecutor.currentCommandBuffer.isNotEmpty()) activeExecutor.currentCommandBuffer = activeExecutor.currentCommandBuffer.dropLast(1); activeExecutor.writeRaw("\u007F") }
                                                    else -> { val t = handleChar(ch); activeExecutor.currentCommandBuffer += t; activeExecutor.writeRaw(t) }
                                                }
                                            }
                                        }
                                        /* Phase 26: JANGAN reset hiddenInput di sini (IME confused). */
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
                                    fontSizeState = terminalFontSize,
                                    onFontSizeChange = {
                                        terminalFontSize = it
                                        /* Phase 26: Persist fontSize. */
                                        getSharedPreferences("TunnelUI", Context.MODE_PRIVATE)
                                            .edit().putFloat("fontSize", it).apply()
                                    }
                                )
                            }
                            /* Divider. */
                            Box(modifier = Modifier.width(2.dp).fillMaxHeight().background(currentTheme.uiSurface))
                            /* Right pane: second terminal. */
                            Box(modifier = Modifier.weight(1f).clickable {
                                splitExecutor?.let { activeExecutorId = it.id }
                            }) {
                                splitExecutor?.let { exec ->
                                    val sd by exec.screenDirty.collectAsState()
                                    TerminalScreenView(
                                        emulator = exec.emulator, screenDirty = sd,
                                        isAlive = exec.isAlive,
                                        onRestartSession = { scope.launch { exec.restart() } },
                                        onResize = { r, c, f -> exec.resizeTerminal(r, c, f) },
                                        theme = currentTheme,
                                        fontSizeState = terminalFontSize,
                                        onFontSizeChange = {
                                        terminalFontSize = it
                                        /* Phase 26: Persist fontSize. */
                                        getSharedPreferences("TunnelUI", Context.MODE_PRIVATE)
                                            .edit().putFloat("fontSize", it).apply()
                                    }
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
                                try { focusRequester.requestFocus(); if (!hasPhysicalKeyboard) keyboardController?.show() } catch (_: Exception) {}
                            }
                            var lastInputValue by remember { mutableStateOf("") }
                            BasicTextField(
                                value = hiddenInput,
                                onValueChange = { newValue ->
                                    val oldText = lastInputValue
                                    lastInputValue = newValue
                                    if (newValue.length > oldText.length) {
                                        val added = newValue.substring(oldText.length)
                                        for (ch in added) {
                                            when (ch) {
                                                '\n', '\r' -> {
                                                    processInput(activeExecutor.currentCommandBuffer + "\n")
                                                    activeExecutor.currentCommandBuffer = ""
                                                    hiddenInput = ""; lastInputValue = ""
                                                }
                                                '\u007F', '\b' -> { if (activeExecutor.currentCommandBuffer.isNotEmpty()) activeExecutor.currentCommandBuffer = activeExecutor.currentCommandBuffer.dropLast(1); activeExecutor.writeRaw("\u007F") }
                                                else -> { val t = handleChar(ch); activeExecutor.currentCommandBuffer += t; activeExecutor.writeRaw(t) }
                                            }
                                        }
                                    }
                                    /* Phase 26: JANGAN reset hiddenInput di sini (IME confused). */
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
                                        drawerState.open()
                                    }
                                },
                                onToggleCollapse = { id -> blockManager.toggleCollapse(id) },
                                /* Phase 44 fix (MED-02): Pinch-to-zoom sekarang jalan di Block Mode. */
                                fontSizeState = terminalFontSize,
                                onFontSizeChange = {
                                    terminalFontSize = it
                                    getSharedPreferences("TunnelUI", Context.MODE_PRIVATE)
                                        .edit().putFloat("fontSize", it).apply()
                                }
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
                            /* Phase 40 fix (H7): Reset enterHandledByKeyEvent saat pindah tab.
                             * OLD BUG: flag tidak di-reset → Enter di tab B tidak jalan setelah
                             * physical keyboard Enter di tab A (flag masih true dari tab A). */
                            enterHandledByKeyEvent = false
                            try {
                                focusRequester.requestFocus()
                                if (!hasPhysicalKeyboard) keyboardController?.show()
                            } catch (_: Exception) {
                                /* FocusRequester belum siap, coba lagi nanti. */
                            }
                        }

                        /* Phase 36 fix: Soft keyboard Enter tidak trigger Key.Enter di
                         * onPreviewKeyEvent — hanya trigger onValueChange dengan \n.
                         * enterHandledByKeyEvent declared di scope luar. */
                        var lastInputValue by remember { mutableStateOf("") }

                        BasicTextField(
                            value = hiddenInput,
                            onValueChange = { newValue ->
                                val oldText = lastInputValue
                                lastInputValue = newValue

                                if (newValue.length > oldText.length) {
                                    val added = newValue.substring(oldText.length)
                                    for (ch in added) {
                                        when (ch) {
                                            '\n', '\r' -> {
                                                /* Phase 36: Enter dari soft keyboard — handle di sini.
                                                 * Cek flag: jika handleKeyEvent sudah handle (physical keyboard),
                                                 * skip untuk mencegah double-fire. */
                                                if (!enterHandledByKeyEvent) {
                                                    processInput(activeExecutor.currentCommandBuffer + "\n")
                                                    activeExecutor.currentCommandBuffer = ""
                                                    hiddenInput = ""
                                                    lastInputValue = ""
                                                }
                                                enterHandledByKeyEvent = false
                                            }
                                            '\u007F', '\b' -> {
                                                if (activeExecutor.currentCommandBuffer.isNotEmpty()) {
                                                    activeExecutor.currentCommandBuffer = activeExecutor.currentCommandBuffer.dropLast(1)
                                                }
                                                activeExecutor.writeRaw("\u007F")
                                            }
                                            else -> {
                                                val translated = handleChar(ch)
                                                activeExecutor.currentCommandBuffer += translated
                                                activeExecutor.writeRaw(translated)
                                            }
                                        }
                                    }
                                }
                                /* JANGAN reset hiddenInput di sini — IME confused. */
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
                            /* Phase 19.5: Mouse scroll wheel untuk scroll terminal history. */
                            onScroll = { delta ->
                                /* Forward ke TerminalScreenView internal scroll (handled di composable). */
                            },
                            /* Phase 24: External fontSize state untuk persist pinch-to-zoom. */
                            fontSizeState = terminalFontSize,
                            onFontSizeChange = { terminalFontSize = it },
                            /* Phase 53: Paste callback for floating selection toolbar. */
                            onPasteRequested = { pasteFromClipboard(activeExecutor) }
                        )
                    }
                    } /* end else (normal mode) */
                    ExtraKeysBar(
                        isCtrlActive = isCtrlActive,
                        isAltActive = isAltActive,
                        onKeyPressed = { handleExtraKey(it) }
                    )
                }

                /* FAB contextual untuk auto-debug. */
                FloatingActionButton(
                    onClick = {
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
                        scope.launch {
                            handleAIPrompt(prompt)
                            drawerState.open()
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).padding(bottom = 40.dp),
                    containerColor = Color(0xFF6200EE),
                    contentColor = Color.White
                ) { Text("🛠", fontSize = 20.sp) }
            }
        }
    }

    /** Phase 53: Extract paste logic supaya bisa dipakai ulang oleh floating toolbar. */
    private fun pasteFromClipboard(executor: TerminalSession) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clipText = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
        if (clipText.isNotEmpty()) {
            executor.writeRaw(clipText)
            executor.currentCommandBuffer += clipText.replace("\n", "")
            Toast.makeText(this, "Pasted ${clipText.length} chars", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Clipboard kosong", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildHelpText(): String = """
        ==========================================
        TUNNEL TERMINAL - AI NATIVE DEV ENVIRONMENT
        ==========================================
        Built-in Commands (ditangani lokal):
        - help              Tampilkan menu bantuan ini
        - clear             Bersihkan layar terminal
        - setup-storage     Bridge ke /sdcard via Storage Access Framework
        - storage-status    Cek status konfigurasi storage
        - storage-reset     Reset konfigurasi storage
        - system-info       Tampilkan info sistem (MOTD)
        - open <file>       Edit file di Tunnel Editor UI

        Shell Commands (dilempar ke /system/bin/sh):
        - ls, cd, cat, echo, mkdir, rm, cp, mv, pwd
        - ps, kill, df, du, head, tail, grep, sed, awk
        - wget, curl (jika tersedia di system)

        AI Copilot Features (Klik tombol AI di kanan atas):
        - Multi-Provider (OpenAI, Claude, Gemini, DeepSeek, Groq, Ollama)
        - Auto-Debug     : Klik tombol 🛠 untuk minta AI baca error
        - Auto-Pilot     : Minta AI menyelesaikan tugas berurutan
        - Workflows      : Simpan perintah AI ke Snippet Vault

        Shortcuts & UX:
        - Volume Up/Down : Navigasi riwayat perintah (per-tab)
        - CTRL + C       : Hentikan proses yang berjalan
        - Pinch Screen   : Zoom In/Out ukuran font terminal
        - HOME/END/PGUP/PGDN : Navigasi untuk less/vim
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
        chatMessages.add(ChatMessage("assistant", "🚀 Auto-Pilot memulai ${commands.size} langkah...", false))

        for (i in commands.indices) {
            val cmd = commands[i]
            chatMessages.add(ChatMessage("assistant", "▶ [${i + 1}/${commands.size}] Menjalankan: $cmd", false))

            /* Phase 37: Pakai MarkerExecutor — bukan regex prompt nebak.
             * Phase 46 (Pilar 1b): Handle ExecutionOutcome (3 kemungkinan).
             * maxTimeoutMs 5 menit (apt install bisa lama), idleTimeoutMs 15s (curiga nunggu input). */
            val outcome = markerExecutor.executeWithMarker(
                activeExecutor, cmd,
                maxTimeoutMs = 300000,
                idleTimeoutMs = 15000
            )

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
    private suspend fun handleAIPrompt(prompt: String) {
        if (isProcessingAI) {
            chatMessages.add(ChatMessage(
                "assistant",
                "⏳ AI masih memproses permintaan sebelumnya. Tunggu sebentar.",
                false
            ))
            return
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
        /* Clear pending images setelah di-attach ke pesan. */
        pendingImages.clear()

        val activeExecutor = shellExecutors.find { it.id == activeExecutorId }
        val terminalContext = activeExecutor?.getCleanOutput() ?: ""

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

        val fullContext = terminalContext + mentionContext + asyncCommandContext

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
            aiAgent.askAIStreaming(
                aiSettings, chatMessages.toList(), fullContext,
                activeExecutor?.sessionType ?: "local",
                activeExecutor?.environmentDescription ?: "",
                /* Phase 50 fix (B-5): Inject project context (git, manifests, file tree). */
                projectContext.buildContext(toolExecutor.workspaceRootFile()),
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
        }
    }
}
