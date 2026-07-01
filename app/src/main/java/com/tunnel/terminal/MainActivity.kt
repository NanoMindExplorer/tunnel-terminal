package com.tunnel.terminal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
    private var aiSettings by mutableStateOf(AISettings()) // State untuk pengaturan AI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { createNewTab() }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    TerminalApp()
                }
            }
        }
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
                        messages = chatMessages,
                        settings = aiSettings,
                        onSettingsChanged = { newSettings -> aiSettings = newSettings },
                        onSendPrompt = { prompt -> scope.launch { handleAIPrompt(prompt) } },
                        onRunCommand = { cmd ->
                            shellExecutors.find { it.id == activeExecutorId }?.executeCommand(cmd)
                            scope.launch { drawerState.close() }
                        },
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

            val terminalHistory by activeExecutor.output.collectAsState()
            var inputText by remember(activeExecutorId) { mutableStateOf("") }
            val scrollState = rememberScrollState()
            val tabsData = shellExecutors.mapIndexed { index, executor -> Pair(executor.id, index + 1) }

            LaunchedEffect(terminalHistory.size) { scrollState.animateScrollTo(scrollState.maxValue) }

            fun handleExtraKey(key: String) {
                when (key) {
                    "ESC" -> inputText = ""
                    "TAB" -> inputText += "    "
                    else -> inputText += key
                }
            }

            Column(modifier = Modifier.fillMaxSize()) {
                TabBar(
                    tabs = tabsData, activeTabId = activeExecutorId,
                    onTabSelected = { id -> activeExecutorId = id },
                    onNewTab = { lifecycleScope.launch { createNewTab() } },
                    onTabClosed = { id -> closeTab(id) },
                    onOpenAI = { scope.launch { drawerState.open() } }
                )

                Box(modifier = Modifier.weight(1f)) {
                    Column(modifier = Modifier.fillMaxSize().padding(8.dp).verticalScroll(scrollState)) {
                        terminalHistory.forEach { line ->
                            Text(line, color = Color(0xFF00FF00), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("tunnel@android:~$ ", color = Color(0xFF00FF00), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                            BasicTextField(
                                value = inputText, onValueChange = { inputText = it },
                                textStyle = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                                cursorBrush = SolidColor(Color.White),
                                modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                                    if (event.key == Key.Enter && event.type == KeyEventType.KeyUp) {
                                        if (inputText.trim() == "clear") activeExecutor.clearScreen() else activeExecutor.executeCommand(inputText)
                                        inputText = ""
                                        true
                                    } else false
                                }
                            )
                        }
                    }
                }
                ExtraKeysBar(onKeyPressed = { handleExtraKey(it) })
            }
        }
    }

    private suspend fun handleAIPrompt(prompt: String) {
        chatMessages.add(ChatMessage("user", prompt, false))
        val activeExecutor = shellExecutors.find { it.id == activeExecutorId }
        val context = activeExecutor?.output?.value?.takeLast(5)?.joinToString("\n") ?: "Terminal kosong"
        
        // Gunakan setings yang dinamis
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
    }
}
