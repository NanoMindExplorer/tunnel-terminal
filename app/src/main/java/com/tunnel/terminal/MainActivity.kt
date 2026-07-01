package com.tunnel.terminal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    // Menyimpan daftar sesi shell yang aktif
    private val shellExecutors = mutableStateListOf<ShellExecutor>()
    private var activeExecutorId by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Buat tab pertama saat aplikasi dibuka
        createNewTab()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    TerminalApp()
                }
            }
        }
    }

    private fun createNewTab() {
        val newExecutor = ShellExecutor()
        newExecutor.start()
        shellExecutors.add(newExecutor)
        activeExecutorId = newExecutor.id
    }

    override fun onDestroy() {
        super.onDestroy()
        shellExecutors.forEach { it.destroy() }
    }

    @Composable
    fun TerminalApp() {
        val activeExecutor = shellExecutors.find { it.id == activeExecutorId } ?: shellExecutors.first()
        val terminalHistory by activeExecutor.output.collectAsState()
        
        var inputText by remember(activeExecutorId) { mutableStateOf("") }
        val scrollState = rememberScrollState()
        val scope = rememberCoroutineScope()

        // Mapping tab untuk UI (ID, Nomor Tab)
        val tabsData = shellExecutors.mapIndexed { index, executor -> 
            Pair(executor.id, index + 1) 
        }

        LaunchedEffect(terminalHistory.size) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }

        fun handleExtraKey(key: String) {
            when (key) {
                "ESC" -> inputText = ""
                "TAB" -> inputText += "    " // Sementara beri 4 spasi
                "CTRL", "ALT" -> { /* Modifier keys, butuh PTY untuk berfungsi maksimal */ }
                "↑", "↓", "←", "→" -> { /* Arrow keys butuh PTY untuk navigasi cursor */ }
                else -> inputText += key // Untuk -, /, |
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // 1. Tab Bar di paling atas
            TabBar(
                tabs = tabsData,
                activeTabId = activeExecutorId,
                onTabSelected = { id -> activeExecutorId = id },
                onNewTab = { createNewTab() }
            )

            // 2. Area Terminal (Scrollable)
            Box(modifier = Modifier.weight(1f)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .verticalScroll(scrollState)
                ) {
                    terminalHistory.forEach { line ->
                        Text(
                            text = line,
                            color = Color(0xFF00FF00),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp
                        )
                    }
                    
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("tunnel@android:~$ ", color = Color(0xFF00FF00), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                        BasicTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            textStyle = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                            cursorBrush = SolidColor(Color.White),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onPreviewKeyEvent { event ->
                                    if (event.key == Key.Enter && event.type == KeyEventType.KeyUp) {
                                        if (inputText.trim() == "clear") {
                                            activeExecutor.clearScreen()
                                        } else {
                                            activeExecutor.executeCommand(inputText)
                                        }
                                        inputText = ""
                                        true
                                    } else false
                                }
                        )
                    }
                }
            }

            // 3. Extra Keys Bar di atas keyboard
            ExtraKeysBar(onKeyPressed = { handleExtraKey(it) })
        }
    }
}
