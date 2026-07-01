package com.tunnel.terminal

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val shellExecutors = mutableStateListOf<ShellExecutor>()
    private var activeExecutorId by mutableStateOf(0)
    private val aiAgent = AIAgent()
    
    private val chatMessages = mutableStateListOf<ChatMessage>()
    private var aiSettings by mutableStateOf(AISettings())
    private var isProcessingAI by mutableStateOf(false)
    
    private lateinit var snippetManager: SnippetManager
    private val snippetsState = mutableStateListOf<Snippet>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        snippetManager = SnippetManager(this)
        snippetsState.addAll(snippetManager.snippets)
        loadAISettings()
        
        val serviceIntent = Intent(this, TerminalForegroundService::class.java)
        startForegroundService(serviceIntent)

        lifecycleScope.launch { createNewTab() }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    TerminalApp()
                }
            }
        }
    }

    private fun loadAISettings() {
        val prefs = getSharedPreferences("TunnelAIPrefs", Context.MODE_PRIVATE)
        aiSettings = AISettings(
            providerName = prefs.getString("providerName", "OpenAI")!!,
            baseUrl = prefs.getString("baseUrl", "https://api.openai.com/v1")!!,
            apiKey = prefs.getString("apiKey", "")!!,
            modelName = prefs.getString("modelName", "gpt-4o-mini")!!
        )
    }

    private fun saveAISettings(newSettings: AISettings) {
        aiSettings = newSettings
        val prefs = getSharedPreferences("TunnelAIPrefs", Context.MODE_PRIVATE).edit()
        prefs.putString("providerName", newSettings.providerName)
        prefs.putString("baseUrl", newSettings.baseUrl)
        prefs.putString("apiKey", newSettings.apiKey)
        prefs.putString("modelName", newSettings.modelName)
        prefs.apply()
    }

    private fun saveSnippet(title: String, command: String) {
        snippetManager.add(title, command)
        snippetsState.clear()
        snippetsState.addAll(snippetManager.snippets)
    }

    private fun deleteSnippet(index: Int) {
        snippetManager.remove(index)
        snippetsState.clear()
        snippetsState.addAll(snippetManager.snippets)
    }

    private suspend fun createNewTab() {
        val newExecutor = ShellExecutor()
        newExecutor.start()
        shellExecutors.add(newExecutor)
        activeExecutorId = newExecutor.id
    }

    private fun closeTab(id: Int) {
        shellExecutors.find { it.id == id }?.destroy()
        shellExecutors.removeAll { it.id == id }
        if (activeExecutorId == id) activeExecutorId = shellExecutors.firstOrNull()?.id ?: 0
        if (shellExecutors.isEmpty()) lifecycleScope.launch { createNewTab() }
    }

    override fun onDestroy() {
        super.onDestroy()
        shellExecutors.forEach { it.destroy() }
        stopService(Intent(this, TerminalForegroundService::class.java))
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun TerminalApp() {
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(modifier = Modifier.fillMaxHeight(0.9f).background(Color(0xFF1A1A1A))) {
                    AIChatPanel(
                        messages = chatMessages, settings = aiSettings, snippets = snippetsState,
                        onSettingsChanged = { saveAISettings(it) },
                        onSendPrompt = { prompt -> scope.launch { handleAIPrompt(prompt) } },
                        onRunCommand = { cmd ->
                            shellExecutors.find { it.id == activeExecutorId }?.executeCommand(cmd)
                            scope.launch { drawerState.close() }
                        },
                        onSaveSnippet = { title, cmd -> saveSnippet(title, cmd) },
                        onRunSnippet = { cmd ->
                            shellExecutors.find { it.id == activeExecutorId }?.executeCommand(cmd)
                            scope.launch { drawerState.close() }
                        },
                        onDeleteSnippet = { deleteSnippet(it) },
                        onClose = { scope.launch { drawerState.close() } }
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
            
            // Hidden text field to capture keyboard input
            var hiddenInput by remember { mutableStateOf("") }

            LaunchedEffect(activeExecutor.lastCommandOutput.value) {
                val lastOut = activeExecutor.lastCommandOutput.value.lowercase()
                if (lastOut.contains("error") || lastOut.contains("not found")) {
                    if (!isProcessingAI && chatMessages.lastOrNull()?.role != "assistant") {
                        chatMessages.add(ChatMessage("assistant", "Saya mendeteksi error. Klik 🛠 untuk meminta solusi.", false))
                    }
                }
            }

            fun handleExtraKey(key: String) {
                val ansiCode = when (key) {
                    "ESC" -> "\u001B"
                    "TAB" -> "\t"
                    "↑" -> "\u001B[A"
                    "↓" -> "\u001B[B"
                    "→" -> "\u001B[C"
                    "←" -> "\u001B[D"
                    "CTRL" -> "" // Butuh kombinasi tombol, diabaikan sementara
                    "ALT" -> ""
                    else -> key
                }
                if (ansiCode.isNotEmpty()) {
                    activeExecutor.writeRaw(ansiCode)
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    TabBar(
                        tabs = tabsData, activeTabId = activeExecutorId,
                        onTabSelected = { activeExecutorId = it },
                        onNewTab = { lifecycleScope.launch { createNewTab() } },
                        onTabClosed = { closeTab(it) },
                        onOpenAI = { scope.launch { drawerState.open() } }
                    )

                    Box(modifier = Modifier.weight(1f)) {
                        // Render Matriks Terminal
                        TerminalScreenView(emulator = activeExecutor.emulator, screenDirty = screenDirty)
                        
                        // BasicTextField transparan untuk menangkap keyboard Android
                        BasicTextField(
                            value = hiddenInput,
                            onValueChange = { 
                                if (it.isNotEmpty()) {
                                    // Langsung kirim keystroke ke PTY
                                    activeExecutor.writeRaw(it)
                                    hiddenInput = "" // Kosongkan kembali
                                }
                            },
                            textStyle = TextStyle(color = Color.Transparent), // Teks transparan
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Transparent), // Kursor transparan
                            modifier = Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                                if (event.key == Key.Enter && event.type == KeyEventType.KeyUp) {
                                    activeExecutor.writeRaw("\n")
                                    hiddenInput = ""
                                    true
                                } else false
                            }
                        )
                    }
                    ExtraKeysBar(onKeyPressed = { handleExtraKey(it) })
                }

                FloatingActionButton(
                    onClick = {
                        scope.launch {
                            handleAIPrompt("Perbaiki error ini.")
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

    private suspend fun handleAIPrompt(prompt: String) {
        if (isProcessingAI) return
        isProcessingAI = true
        
        chatMessages.add(ChatMessage("user", prompt, false))
        val activeExecutor = shellExecutors.find { it.id == activeExecutorId }
        val context = if (activeExecutor?.lastCommandOutput?.value.isNullOrEmpty()) {
            "Terminal kosong."
        } else {
            "Output perintah terakhir:\n${activeExecutor?.lastCommandOutput?.value}"
        }
        
        val response = aiAgent.askAI(aiSettings, prompt, context)
        val bashRegex = Regex("```bash\\n([\\s\\S]*?)\\n```")
        val match = bashRegex.find(response)
        
        if (match != null) {
            val command = match.groupValues[1].trim()
            val explanation = response.substring(0, match.range.first).trim()
            if (explanation.isNotEmpty()) chatMessages.add(ChatMessage("assistant", explanation, false))
            chatMessages.add(ChatMessage("assistant", command, true))
        } else {
            chatMessages.add(ChatMessage("assistant", response, false))
        }
        isProcessingAI = false
    }
}
