package com.tunnel.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TabBar(
    tabs: List<Pair<Int, Int>>,
    activeTabId: Int,
    onTabSelected: (Int) -> Unit,
    onNewTab: () -> Unit,
    onTabClosed: (Int) -> Unit,
    onOpenAI: () -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().background(Color(0xFF1E1E1E)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(tabs) { tab ->
            val isActive = tab.first == activeTabId
            Row(
                modifier = Modifier.background(if (isActive) Color(0xFF333333) else Color(0xFF222222), RoundedCornerShape(4.dp))
                    .clickable { onTabSelected(tab.first) }
                    .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Tab ${tab.second}  ", color = if (isActive) Color.White else Color.Gray, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Box(modifier = Modifier.clickable { onTabClosed(tab.first) }.padding(4.dp)) {
                    Text("X", color = Color(0xFFFF5252), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        item {
            Box(modifier = Modifier.background(Color(0xFF3A3A3A), RoundedCornerShape(4.dp)).clickable { onNewTab() }.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text("+", color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            }
        }
        item {
            Box(modifier = Modifier.background(Color(0xFF6200EE), RoundedCornerShape(4.dp)).clickable { onOpenAI() }.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text("AI", color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun ExtraKeysBar(onKeyPressed: (String) -> Unit) {
    val keys = listOf("ESC", "TAB", "CTRL", "ALT", "-", "/", "|", "↑", "↓", "←", "→")
    LazyRow(
        modifier = Modifier.fillMaxWidth().background(Color(0xFF2B2B2B)).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        items(keys) { key ->
            Box(
                modifier = Modifier.background(Color(0xFF3A3A3A), RoundedCornerShape(4.dp)).clickable { onKeyPressed(key) }.padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) { Text(key, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
        }
    }
}

@Composable
fun AIChatPanel(
    messages: List<ChatMessage>,
    settings: AISettings,
    onSettingsChanged: (AISettings) -> Unit,
    onSendPrompt: (String) -> Unit,
    onRunCommand: (String) -> Unit,
    onClose: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(0) } // 0: Chat, 1: Settings
    val scrollState = rememberScrollState()
    var expandedProvider by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A1A))) {
        // Header
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Tunnel AI Copilot", color = Color.White, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
            Button(onClick = onClose, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))) { Text("X", color = Color.White) }
        }

        // Tab Selector (Chat / Settings)
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { selectedTab = 0 }, colors = ButtonDefaults.buttonColors(containerColor = if (selectedTab == 0) Color(0xFF6200EE) else Color(0xFF333333))) { Text("Chat") }
            Button(onClick = { selectedTab = 1 }, colors = ButtonDefaults.buttonColors(containerColor = if (selectedTab == 1) Color(0xFF6200EE) else Color(0xFF333333))) { Text("Settings") }
        }

        if (selectedTab == 0) {
            // --- CHAT TAB ---
            Column(modifier = Modifier.weight(1f).padding(16.dp).verticalScroll(scrollState)) {
                messages.forEach { msg ->
                    val color = if (msg.role == "user") Color(0xFF00FF00) else Color.White
                    Text("${if (msg.role == "user") "Anda" else "AI"}:", color = color, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                    Text(msg.content, color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 16.dp))
                    if (msg.isCommand) {
                        Button(onClick = { onRunCommand(msg.content) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE)), modifier = Modifier.padding(bottom = 16.dp)) { Text("▶ Run Command") }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = inputText, onValueChange = { inputText = it }, modifier = Modifier.weight(1f), textStyle = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace), placeholder = { Text("Tanya AI...", color = Color.Gray) })
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { if (inputText.isNotEmpty()) { onSendPrompt(inputText); inputText = "" } }) { Text("Kirim") }
            }
        } else {
            // --- SETTINGS TAB ---
            Column(modifier = Modifier.weight(1f).padding(16.dp).verticalScroll(scrollState)) {
                Text("Provider:", color = Color.Gray, fontSize = 12.sp)
                Box {
                    OutlinedTextField(
                        value = settings.providerName, onValueChange = {}, readOnly = true,
                        modifier = Modifier.fillMaxWidth().clickable { expandedProvider = true },
                        textStyle = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace),
                        trailingIcon = { Text("▼", color = Color.White) }
                    )
                    DropdownMenu(expanded = expandedProvider, onDismissRequest = { expandedProvider = false }) {
                        AIProviders.presets.forEach { preset ->
                            DropdownMenuItem(text = { Text(preset.providerName) }, onClick = {
                                onSettingsChanged(preset.copy(apiKey = settings.apiKey)) // Jaga API Key saat ganti preset
                                expandedProvider = false
                            })
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                
                Text("Base URL:", color = Color.Gray, fontSize = 12.sp)
                OutlinedTextField(value = settings.baseUrl, onValueChange = { onSettingsChanged(settings.copy(baseUrl = it)) }, modifier = Modifier.fillMaxWidth(), textStyle = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace))
                Spacer(modifier = Modifier.height(16.dp))

                Text("Model Name:", color = Color.Gray, fontSize = 12.sp)
                OutlinedTextField(value = settings.modelName, onValueChange = { onSettingsChanged(settings.copy(modelName = it)) }, modifier = Modifier.fillMaxWidth(), textStyle = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace))
                Spacer(modifier = Modifier.height(16.dp))

                Text("API Key:", color = Color.Gray, fontSize = 12.sp)
                OutlinedTextField(value = settings.apiKey, onValueChange = { onSettingsChanged(settings.copy(apiKey = it)) }, modifier = Modifier.fillMaxWidth(), textStyle = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace), placeholder = { Text("sk-...", color = Color.Gray) })
            }
        }
    }
}
