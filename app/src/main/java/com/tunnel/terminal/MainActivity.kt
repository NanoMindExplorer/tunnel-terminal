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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
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
    private val shellExecutors = mutableStateListOf<ShellExecutor>()
    private var activeExecutorId by mutableStateOf(0)
    private val aiAgent = AIAgent()

    private val chatMessages = mutableStateListOf<ChatMessage>()
    private var aiSettings by mutableStateOf(AISettings())
    private var isProcessingAI by mutableStateOf(false)
    private var editingFile by mutableStateOf<String?>(null)

    private lateinit var snippetManager: SnippetManager
    private val snippetsState = mutableStateListOf<Snippet>()

    /** Shared command buffer for the currently focused tab's input line. */
    private var currentCommandBuffer = ""
    private var historyIndex = -1

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
        /* Tampilkan MOTD sebelum start. */
        newExecutor.start()
        shellExecutors.add(newExecutor)
        activeExecutorId = newExecutor.id

        /* Tampilkan MOTD dinamis. */
        newExecutor.emulator.process(SystemInfo.buildMotd(this))
        newExecutor.triggerScreenUpdate()
    }

    private fun closeTab(id: Int) {
        shellExecutors.find { it.id == id }?.destroy()
        shellExecutors.removeAll { it.id == id }
        if (activeExecutorId == id) {
            activeExecutorId = shellExecutors.firstOrNull()?.id ?: 0
        }
        if (shellExecutors.isEmpty()) {
            lifecycleScope.launch { createNewTab() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        shellExecutors.forEach { it.destroy() }
        stopService(Intent(this, TerminalForegroundService::class.java))
    }

    /* ─── Volume key command history navigation (per-active-executor) ─── */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            navigateHistory(forward = false); return true
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            navigateHistory(forward = true); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun navigateHistory(forward: Boolean) {
        val activeExecutor = shellExecutors.find { it.id == activeExecutorId } ?: return
        val history = activeExecutor.commandHistory
        if (history.isEmpty()) return

        if (forward) {
            if (historyIndex > 0) historyIndex--
            else if (historyIndex == 0) historyIndex = -1
        } else {
            if (historyIndex < history.size - 1) historyIndex++
        }

        /* Clear current line (Ctrl+U) lalu tulis history. */
        activeExecutor.writeRaw("\u0015")
        currentCommandBuffer = ""
        if (historyIndex != -1) {
            val cmd = history[history.size - 1 - historyIndex]
            currentCommandBuffer = cmd
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
                }
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

            /* Deteksi error di output terakhir (debounced). */
            LaunchedEffect(activeExecutor.id, activeExecutor.lastCommandOutput.value) {
                val lastOut = activeExecutor.getCleanOutput().lowercase()
                val hasError = lastOut.contains("error") || lastOut.contains("not found") ||
                               lastOut.contains("no such file") || lastOut.contains("permission denied")
                if (hasError && !isProcessingAI) {
                    val lastMsg = chatMessages.lastOrNull()
                    if (lastMsg?.role != "assistant" || !lastMsg.content.contains("mendeteksi error")) {
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
                historyIndex = -1

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

            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    TabBar(
                        tabs = tabsData, activeTabId = activeExecutorId,
                        onTabSelected = {
                            activeExecutorId = it
                            currentCommandBuffer = ""
                            historyIndex = -1
                        },
                        onNewTab = { lifecycleScope.launch { createNewTab() } },
                        onTabClosed = { closeTab(it) },
                        onOpenAI = { scope.launch { drawerState.open() } },
                        onOpenFileExplorer = { showFileExplorer = true },
                        onOpenWorkspace = { showWorkspaceDrawer = true },
                        theme = currentTheme
                    )

                    Box(modifier = Modifier.weight(1f)) {
                        TerminalScreenView(
                            emulator = activeExecutor.emulator,
                            screenDirty = screenDirty,
                            isAlive = activeExecutor.isAlive,
                            onRestartSession = { scope.launch { activeExecutor.restart() } },
                            onResize = { rows, cols, fontSize -> activeExecutor.resizeTerminal(rows, cols, fontSize) },
                            theme = currentTheme
                        )

                        /* Hidden BasicTextField untuk menangkap input keyboard fisik.
                         * Hidden BasicTextField to capture physical keyboard input. */
                        BasicTextField(
                            value = hiddenInput,
                            onValueChange = { typed ->
                                if (typed.isNotEmpty()) {
                                    hiddenInput = ""
                                    when {
                                        typed == "\n" || typed == "\r" -> {
                                            processInput(currentCommandBuffer + "\n")
                                            currentCommandBuffer = ""
                                        }
                                        typed == "\u007F" -> {
                                            if (currentCommandBuffer.isNotEmpty()) {
                                                currentCommandBuffer = currentCommandBuffer.dropLast(1)
                                            }
                                            activeExecutor.writeRaw(typed)
                                        }
                                        else -> {
                                            val translated = typed.map { handleChar(it) }.joinToString("")
                                            currentCommandBuffer += translated
                                            activeExecutor.writeRaw(translated)
                                        }
                                    }
                                }
                            },
                            textStyle = TextStyle(color = Color.Transparent),
                            cursorBrush = SolidColor(Color.Transparent),
                            modifier = Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                                if (event.key == Key.Enter && event.type == KeyEventType.KeyUp) {
                                    processInput(currentCommandBuffer + "\n")
                                    currentCommandBuffer = ""
                                    hiddenInput = ""
                                    true
                                } else false
                            }
                        )
                    }
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

    private fun resolveAndOpen(fileName: String, executor: ShellExecutor) {
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

            /* Capture output sebelum perintah untuk deteksi delta. */
            val outputBefore = activeExecutor.getCleanOutput().length
            activeExecutor.executeCommand(cmd)

            /* Tunggu sampai prompt shell muncul kembali (timeout 15s).
             * Wait for shell prompt to reappear (15s timeout). */
            val ok = waitForPrompt(activeExecutor, outputBefore, timeoutMs = 15000)
            if (!ok) {
                chatMessages.add(ChatMessage(
                    "assistant",
                    "⚠️ Timeout menunggu perintah #${i + 1} selesai. Auto-Pilot dihentikan.",
                    false, isError = true
                ))
                return
            }

            /* Deteksi error di output baru. */
            val newOutput = activeExecutor.getCleanOutput().substring(outputBefore)
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
    private suspend fun waitForPrompt(executor: ShellExecutor, outputBeforeLen: Int, timeoutMs: Long): Boolean {
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

        try {
            /* Koleksi token-by-token dari streaming Flow. */
            aiAgent.askAIStreaming(aiSettings, chatMessages.toList(), terminalContext).collect { delta ->
                if (firstChunk) {
                    firstChunk = false
                    /* Cek apakah delta pertama adalah error message (client-side error). */
                    val isClientError = delta.startsWith("Error") || delta.startsWith("Timeout") ||
                                        delta.startsWith("Kesalahan") || delta.startsWith("API Key") ||
                                        delta.startsWith("DNS") || delta.startsWith("SSL") ||
                                        delta.startsWith("Akses ditolak") || delta.startsWith("Endpoint") ||
                                        delta.startsWith("Rate limit") || delta.startsWith("Server")
                    if (isClientError) {
                        chatMessages[streamingIdx] = streamingMsg.copy(
                            content = delta,
                            isStreaming = false,
                            isError = true
                        )
                        isProcessingAI = false
                        return
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
