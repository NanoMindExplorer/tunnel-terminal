package com.tunnel.terminal

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import java.io.File
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
    private val shellExecutors = mutableStateListOf<TerminalSession>()
    private var activeExecutorId by mutableStateOf(0)
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
    /** Phase 24: Global font size untuk terminal (persist pinch-to-zoom across tabs/modes). */
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
                val base64List = uris.mapNotNull { uri ->
                    ImageHelper.uriToBase64(this@MainActivity, uri)
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

        snippetManager = SnippetManager(this)
        snippetsState.addAll(snippetManager.snippets)
        storageManager = StorageManager(this)
        workspaceManager = WorkspaceManager(this)
        workspaceSessions.addAll(workspaceManager.sessions)
        toolExecutor = ToolExecutor(this)
        permissionManager = PermissionManager(this)
        contextManager = ContextManager(this)
        mcpManager = McpManager(this)
        agentWorkflowManager = AgentWorkflowManager(this)
        voiceInputManager = VoiceInputManager(this)
        agentWorkflows.addAll(agentWorkflowManager.workflows)
        loadAISettings()
        loadTheme()

        /* Request POST_NOTIFICATIONS permission untuk Android 13+. */
        requestNotificationPermission()

        /* Start foreground service untuk keep-alive. */
        val serviceIntent = Intent(this, TerminalForegroundService::class.java)
        startForegroundService(serviceIntent)

        /* Buat tab pertama. */
        lifecycleScope.launch { createNewTab() }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = currentTheme.background) {
                    TerminalApp()
                }
            }
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
        themeHolder.theme = newTheme
        currentTheme = newTheme
        ThemeManager.setActiveTheme(this, newTheme)
        /* Trigger screen update on all tabs so theme color applies to existing cells.
         * Note: existing cells keep their assigned colors (they were set when written).
         * New cells will use new theme. For full refresh, user can clear screen. */
        shellExecutors.forEach { it.triggerScreenUpdate() }
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
                val cmd = call.args["cmd"] ?: return
                shellExecutors.find { it.id == activeExecutorId }?.executeCommand(cmd)
            }
            call.tool == "write_file" -> {
                /* Phase 23: Inline diff view untuk AI file edits.
                 * Show diff sebelum apply — user bisa review/reject. */
                val path = call.args["path"] ?: return
                val content = call.args["content"] ?: return
                val file = java.io.File(path)
                val original = if (file.exists()) file.readText() else ""
                if (original != content) {
                    pendingDiff = Triple(path, original, content)
                } else {
                    chatMessages.add(ChatMessage("assistant", "No changes needed for $path", false))
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

        for (step in workflow.steps) {
            chatMessages.add(ChatMessage("assistant", "▶ Step: ${step.displayText}", false))
            when (step.type) {
                AgentStep.StepType.AI_STEP -> {
                    /* Phase 24: Tunggu AI selesai sebelum lanjut ke step berikutnya.
                     * Old code: handleAIPrompt di-guard isProcessingAI → skip jika AI masih jalan.
                     * Fix: tunggu AI idle sebelum call, lalu call. */
                    while (isProcessingAI) { delay(100) }
                    handleAIPrompt(step.prompt)
                    /* Tunggu AI selesai sebelum next step. */
                    while (isProcessingAI) { delay(100) }
                }
                AgentStep.StepType.COMMAND_STEP -> {
                    activeExecutor.executeCommand(step.command)
                    if (step.waitForOutput) {
                        delay(step.timeoutMs)
                    }
                }
                AgentStep.StepType.DELAY_STEP -> {
                    delay(step.timeoutMs)
                }
                AgentStep.StepType.CONDITIONAL_STEP -> {
                    val output = activeExecutor.getCleanOutput().lowercase()
                    if (output.contains(step.prompt.lowercase())) {
                        activeExecutor.executeCommand(step.command)
                        if (step.waitForOutput) delay(step.timeoutMs)
                    }
                }
            }
        }
        chatMessages.add(ChatMessage("assistant", "✅ Workflow '${workflow.name}' completed", false))
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
                shellExecutors.lastOrNull()?.executeCommand("cd $dir")
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
        shellExecutors.find { it.id == activeExecutorId }?.executeCommand("cd ${dir.absolutePath}")
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }
                    .launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun loadAISettings() {
        val prefs = getSharedPreferences("TunnelAIPrefs", Context.MODE_PRIVATE)
        aiSettings = AISettings(
            providerName = prefs.getString("providerName", "OpenAI")!!,
            baseUrl = prefs.getString("baseUrl", "https://api.openai.com/v1")!!,
            apiKey = prefs.getString("apiKey", "")!!,
            modelName = prefs.getString("modelName", "gpt-4o-mini")!!,
            temperature = prefs.getDouble("temperature", 0.2),
            maxTokens = prefs.getInt("maxTokens", 2000),
            requestTimeoutMs = prefs.getInt("requestTimeoutMs", 30000),
            supportsVision = prefs.getBoolean("supportsVision", false)
        )
    }

    private fun saveAISettings(newSettings: AISettings) {
        aiSettings = newSettings
        val prefs = getSharedPreferences("TunnelAIPrefs", Context.MODE_PRIVATE).edit()
        prefs.putString("providerName", newSettings.providerName)
        prefs.putString("baseUrl", newSettings.baseUrl)
        prefs.putString("apiKey", newSettings.apiKey)
        prefs.putString("modelName", newSettings.modelName)
        prefs.putDouble("temperature", newSettings.temperature)
        prefs.putInt("maxTokens", newSettings.maxTokens)
        prefs.putInt("requestTimeoutMs", newSettings.requestTimeoutMs)
        prefs.putBoolean("supportsVision", newSettings.supportsVision)
        prefs.apply()
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
        val newExecutor = ShellExecutor(themeHolder)
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
        val sshExecutor = SshShellExecutor(themeHolder, config)
        shellExecutors.add(sshExecutor)
        activeExecutorId = sshExecutor.id
        sshExecutor.start()
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
                    java.io.File(path).writeText(modified)
                    chatMessages.add(ChatMessage("assistant", "✅ Applied changes to ${java.io.File(path).name}", false))
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

            /* Phase 20: Deteksi error di output terakhir (debounced).
             * Guard dengan isProcessingAI flag + check apakah message error-detection
             * sudah ada (avoid spam). Old code bisa race dengan handleAIPrompt
             * yang menambah streamingMsg — streamingIdx shift. */
            LaunchedEffect(activeExecutor.id, activeExecutor.lastCommandOutput.value) {
                /* Skip jika AI sedang processing (streaming atau non-streaming). */
                if (isProcessingAI) return@LaunchedEffect
                val lastOut = activeExecutor.getCleanOutput().lowercase()
                val hasError = lastOut.contains("error") || lastOut.contains("not found") ||
                               lastOut.contains("no such file") || lastOut.contains("permission denied")
                if (hasError) {
                    /* Cek apakah message error-detection sudah ada (avoid duplicate). */
                    val hasExistingErrorNotification = chatMessages.any {
                        it.role == "assistant" && it.content.contains("mendeteksi error")
                    }
                    if (!hasExistingErrorNotification) {
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
                if (cmd.isNotEmpty()) {
                    /* Tambah ke history per-executor dengan dedup consecutive. */
                    val h = activeExecutor.commandHistory
                    if (h.isEmpty() || h.last() != cmd) h.add(cmd)
                    /* Cap history size. */
                    if (h.size > 500) h.removeAt(0)
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
                    cmd == "system-info" -> {
                        val info = SystemInfo.buildMotd(this@MainActivity)
                        activeExecutor.emulator.process(info)
                        activeExecutor.triggerScreenUpdate()
                    }
                    cmd.startsWith("open ") -> {
                        val fileName = cmd.removePrefix("open ").trim()
                        resolveAndOpen(fileName, activeExecutor)
                    }
                    else -> {
                        /* Forward ke shell. */
                        activeExecutor.writeRaw(input)
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
                val name = key.toString().lowercase()
                if (name.length == 1 && name[0] in 'a'..'z') {
                    return if (shift) name[0].uppercaseChar() else name[0]
                }
                return '\u0000'
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
                /* Hanya handle KeyDown untuk special keys. KeyUp diabaikan. */
                if (event.type != KeyEventType.KeyDown) return false
                val key = event.key
                val ctrl = event.isCtrlPressed
                val alt = event.isAltPressed
                val shift = event.isShiftPressed

                /* Ctrl+key combos (priority). */
                if (ctrl) {
                    val ch = when (key) {
                        Key.C -> 3.toChar()    /* SIGINT */
                        Key.D -> 4.toChar()    /* EOF */
                        Key.Z -> 26.toChar()   /* SIGTSTP */
                        Key.L -> 12.toChar()   /* clear */
                        Key.A -> 1.toChar()    /* line start */
                        Key.E -> 5.toChar()    /* line end */
                        Key.K -> 11.toChar()   /* kill to EOL */
                        Key.U -> 21.toChar()   /* kill line */
                        Key.W -> 23.toChar()   /* kill word */
                        Key.R -> 18.toChar()   /* reverse search */
                        Key.X -> 24.toChar()   /* cancel */
                        else -> '\u0000'
                    }
                    if (ch != '\u0000') {
                        activeExecutor.writeRaw(ch.toString())
                        return true
                    }
                }

                /* Alt+key → ESC + key. */
                if (alt) {
                    val ch = keyToChar(key, shift)
                    if (ch != '\u0000') {
                        activeExecutor.writeRaw("\u001B$ch")
                        return true
                    }
                }

                /* Special keys — handle di KeyDown, return true untuk consume. */
                when (key) {
                    Key.Enter -> {
                        /* Enter: process buffer + newline. Consume agar tidak double-fire. */
                        processInput(activeExecutor.currentCommandBuffer + "\n")
                        activeExecutor.currentCommandBuffer = ""
                        /* Phase 25: Clear hiddenInput agar text tidak menumpuk. */
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

                /* Regular character keys (a-z, 0-9, symbols) — fallback to BasicTextField.
                 * BasicTextField.onValueChange akan handle via commitText dari IME. */
                return false
            }

            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    TabBar(
                        tabs = tabsData, activeTabId = activeExecutorId,
                        onTabSelected = {
                            activeExecutorId = it
                            /* Phase 19.5: currentCommandBuffer & historyIndex sekarang per-tab
                             * (disimpan di ShellExecutor), tidak perlu reset di sini. */
                        },
                        onNewTab = { lifecycleScope.launch { createNewTab() } },
                        onTabClosed = { closeTab(it) },
                        onOpenAI = { scope.launch { drawerState.open() } },
                        onOpenFileExplorer = { showFileExplorer = true },
                        onOpenWorkspace = { showWorkspaceDrawer = true },
                        onOpenSsh = { showSshDialog = true },
                        onToggleSplit = {
                            splitMode = !splitMode
                            if (splitMode) {
                                /* Pilih tab lain untuk split pane (atau buat baru). */
                                val otherTab = shellExecutors.firstOrNull { it.id != activeExecutorId }
                                splitPaneId = otherTab?.id ?: run {
                                    lifecycleScope.launch { createNewTab() }
                                    shellExecutors.lastOrNull()?.id ?: activeExecutorId
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
                                    try { focusRequester.requestFocus(); keyboardController?.show() } catch (_: Exception) {}
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
                                                    '\n', '\r' -> { processInput(activeExecutor.currentCommandBuffer + "\n"); activeExecutor.currentCommandBuffer = "" }
                                                    '\u007F', '\b' -> { if (activeExecutor.currentCommandBuffer.isNotEmpty()) activeExecutor.currentCommandBuffer = activeExecutor.currentCommandBuffer.dropLast(1); activeExecutor.writeRaw("\u007F") }
                                                    else -> { val t = handleChar(ch); activeExecutor.currentCommandBuffer += t; activeExecutor.writeRaw(t) }
                                                }
                                            }
                                        }
                                        if (newValue.isNotEmpty()) { hiddenInput = ""; lastInputValue = "" }
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
                                    onTap = { try { focusRequester.requestFocus(); keyboardController?.show() } catch (_: Exception) {} },
                                    fontSizeState = terminalFontSize,
                                    onFontSizeChange = { terminalFontSize = it }
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
                                        onFontSizeChange = { terminalFontSize = it }
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
                                try { focusRequester.requestFocus(); keyboardController?.show() } catch (_: Exception) {}
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
                                                '\n', '\r' -> { processInput(activeExecutor.currentCommandBuffer + "\n"); activeExecutor.currentCommandBuffer = "" }
                                                '\u007F', '\b' -> { if (activeExecutor.currentCommandBuffer.isNotEmpty()) activeExecutor.currentCommandBuffer = activeExecutor.currentCommandBuffer.dropLast(1); activeExecutor.writeRaw("\u007F") }
                                                else -> { val t = handleChar(ch); activeExecutor.currentCommandBuffer += t; activeExecutor.writeRaw(t) }
                                            }
                                        }
                                    }
                                    if (newValue.isNotEmpty()) { hiddenInput = ""; lastInputValue = "" }
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
                                onToggleCollapse = { id -> blockManager.toggleCollapse(id) }
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
                            try {
                                focusRequester.requestFocus()
                                keyboardController?.show()
                            } catch (_: Exception) {
                                /* FocusRequester belum siap, coba lagi nanti. */
                            }
                        }

                        /* Phase 25: Fix input — JANGAN reset hiddenInput di onValueChange!
                         * Old code: hiddenInput = "" setiap kali user type → IME confused → stop sending text.
                         * Fix: biarkan text menumpuk (invisible). Clear hanya saat Enter (di handleKeyEvent). */
                        var lastInputValue by remember { mutableStateOf("") }

                        BasicTextField(
                            value = hiddenInput,
                            onValueChange = { newValue ->
                                /* Delta tracking: bandingkan dengan lastValue. */
                                val oldText = lastInputValue
                                lastInputValue = newValue

                                if (newValue.length > oldText.length) {
                                    /* Karakter baru ditambahkan di akhir. */
                                    val added = newValue.substring(oldText.length)
                                    for (ch in added) {
                                        when (ch) {
                                            '\n', '\r' -> {
                                                processInput(activeExecutor.currentCommandBuffer + "\n")
                                                activeExecutor.currentCommandBuffer = ""
                                                /* Clear input setelah Enter. */
                                                hiddenInput = ""
                                                lastInputValue = ""
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
                                /* JANGAN reset hiddenInput di sini — IME akan confused.
                                 * Text menumpuk tapi invisible (transparent). Clear saat Enter. */
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
                                    keyboardController?.show()
                                } catch (_: Exception) {}
                            },
                            /* Phase 19.5: Mouse scroll wheel untuk scroll terminal history. */
                            onScroll = { delta ->
                                /* Forward ke TerminalScreenView internal scroll (handled di composable). */
                            },
                            /* Phase 24: External fontSize state untuk persist pinch-to-zoom. */
                            fontSizeState = terminalFontSize,
                            onFontSizeChange = { terminalFontSize = it }
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
    private suspend fun runAutoPilot(commands: List<String>) {
        val activeExecutor = shellExecutors.find { it.id == activeExecutorId } ?: return
        chatMessages.add(ChatMessage("assistant", "🚀 Auto-Pilot memulai ${commands.size} langkah...", false))

        for (i in commands.indices) {
            val cmd = commands[i]
            chatMessages.add(ChatMessage("assistant", "▶ [${i + 1}/${commands.size}] Menjalankan: $cmd", false))

            /* Phase 20: Capture output snapshot SEBELUM perintah untuk deteksi delta.
             * Simpan snapshot String (bukan length) agar tahan terhadap buffer trim.
             * Old code: simpan length, substring(length) -> crash jika buffer di-trim
             * (capped at 4000 chars, outputBefore > current length). */
            val outputBefore = activeExecutor.getCleanOutput()
            activeExecutor.executeCommand(cmd)

            /* Tunggu sampai prompt shell muncul kembali (timeout 15s). */
            val ok = waitForPrompt(activeExecutor, outputBefore.length, timeoutMs = 15000)
            if (!ok) {
                chatMessages.add(ChatMessage(
                    "assistant",
                    "⚠️ Timeout menunggu perintah #${i + 1} selesai. Auto-Pilot dihentikan.",
                    false, isError = true
                ))
                return
            }

            /* Phase 20: Deteksi error di output baru. Bandingkan dengan snapshot
             * string, bukan substring(length) yang bisa out of bounds. */
            val outputAfter = activeExecutor.getCleanOutput()
            val newOutput = if (outputAfter.length > outputBefore.length && outputAfter.startsWith(outputBefore)) {
                /* Normal case: output appended. */
                outputAfter.substring(outputBefore.length)
            } else {
                /* Buffer was trimmed or changed — just use full output. */
                outputAfter
            }
            val lower = newOutput.lowercase()
            val hasError = lower.contains("error") || lower.contains("not found") ||
                           lower.contains("no such file") || lower.contains("permission denied") ||
                           lower.contains("syntax error")
            if (hasError) {
                chatMessages.add(ChatMessage(
                    "assistant",
                    "❌ Error terdeteksi setelah perintah #${i + 1}:\n${newOutput.take(300)}",
                    false, isError = true
                ))
                chatMessages.add(ChatMessage("assistant", "Auto-Pilot dihentikan karena error.", false))
                return
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
        val promptRegex = Regex("""\$\s*$|#\s*$|tunnel@android:[^\$]*\$\s*$""")
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
         * Phase 19: Attach pending images jika ada. */
        val imagesToSend = pendingImages.toList()
        val userMsg = ChatMessage(
            role = "user",
            content = if (prompt.isBlank() && imagesToSend.isNotEmpty()) "Tolong analisa gambar ini." else prompt,
            conversationRole = "user",
            images = imagesToSend
        )
        chatMessages.add(userMsg)
        /* Clear pending images setelah di-attach ke pesan. */
        pendingImages.clear()

        val activeExecutor = shellExecutors.find { it.id == activeExecutorId }
        val terminalContext = activeExecutor?.getCleanOutput() ?: ""

        /* Phase 23: Resolve @context mentions (@file, @block, @command, @terminal, @snippet).
         * Mentions di-resolve ke content dan di-append ke terminalContext. */
        val (resolvedMentions, mentionContext) = contextManager.resolveAll(
            text = prompt,
            blockManager = blockManager,
            terminalSession = activeExecutor,
            snippetManager = snippetManager
        )
        val fullContext = terminalContext + mentionContext

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
            aiAgent.askAIStreaming(aiSettings, chatMessages.toList(), fullContext).collect { delta ->
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
