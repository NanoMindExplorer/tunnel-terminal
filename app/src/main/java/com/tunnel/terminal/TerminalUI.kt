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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TabBar(
    tabs: List<Pair<Int, Int>>, activeTabId: Int,
    onTabSelected: (Int) -> Unit, onNewTab: () -> Unit,
    onTabClosed: (Int) -> Unit, onOpenAI: () -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().background(Color(0xFF1E1E1E)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically
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
        horizontalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(horizontal = 4.dp)
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
fun TerminalScreenView(emulator: TerminalEmulator, screenDirty: Int) {
    val scrollState = rememberScrollState()
    Column(modifier = Modifier.fillMaxSize().padding(8.dp).verticalScroll(scrollState)) {
        for (row in 0 until emulator.rows) {
            val annotatedString = buildAnnotatedString {
                for (col in 0 until emulator.cols) {
                    val cell = emulator.screen[row][col]
                    withStyle(SpanStyle(color = cell.color)) {
                        append(cell.char)
                    }
                }
            }
            Text(text = annotatedString, fontFamily = FontFamily.Monospace, fontSize = 14.sp, modifier = Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIChatPanel(
    messages: List<ChatMessage>, settings: AISettings, snippets: List<Snippet>,
    onSettingsChanged: (AISettings) -> Unit, onSendPrompt: (String) -> Unit,
    onRunCommand: (String) -> Unit, onRunAutoPilot: (List<String>) -> Unit,
    onSaveSnippet: (String, String) -> Unit, onRunSnippet: (String) -> Unit,
    onDeleteSnippet: (Int) -> Unit, onClose: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(0) }
    val scrollState = rememberScrollState()
    var expandedProvider by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf<String?>(null) }
    var snippetTitle by remember { mutableStateOf("") }

    if (showSaveDialog != null) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = null },
            title = { Text("Simpan ke Workflow") },
            text = {
                Column {
                    Text("Perintah: ${showSaveDialog}")
                    OutlinedTextField(value = snippetTitle, onValueChange = { snippetTitle = it }, label = { Text("Nama Workflow") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            },
            confirmButton = { Button(onClick = { if (snippetTitle.isNotEmpty()) onSaveSnippet(snippetTitle, showSaveDialog!!); snippetTitle = ""; showSaveDialog = null }) { Text("Simpan") } },
            dismissButton = { Button(onClick = { showSaveDialog = null }) { Text("Batal") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A1A))) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Tunnel Auto-Pilot", color = Color.White, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
            Button(onClick = onClose, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))) { Text("X", color = Color.White) }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { selectedTab = 0 }, colors = ButtonDefaults.buttonColors(containerColor = if (selectedTab == 0) Color(0xFF6200EE) else Color(0xFF333333))) { Text("Chat") }
            Button(onClick = { selectedTab = 1 }, colors = ButtonDefaults.buttonColors(containerColor = if (selectedTab == 1) Color(0xFF6200EE) else Color(0xFF333333))) { Text("Workflows") }
            Button(onClick = { selectedTab = 2 }, colors = ButtonDefaults.buttonColors(containerColor = if (selectedTab == 2) Color(0xFF6200EE) else Color(0xFF333333))) { Text("Settings") }
        }

        if (selectedTab == 0) {
            Column(modifier = Modifier.weight(1f).padding(16.dp).verticalScroll(scrollState)) {
                messages.forEach { msg ->
                    val color = if (msg.role == "user") Color(0xFF00FF00) else Color.White
                    Text("${if (msg.role == "user") "Anda" else "AI"}:", color = color, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                    Text(msg.content, color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 8.dp))
                    
                    if (msg.commands.size > 1) {
                        // UI untuk Auto-Pilot (Multiple Commands)
                        Text("🚀 Rangkaian Auto-Pilot (${msg.commands.size} langkah):", color = Color(0xFFFFEB3B), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Row(modifier = Modifier.padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onRunAutoPilot(msg.commands) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BCD4))) { Text("Run Auto-Pilot") }
                        }
                    } else if (msg.isCommand) {
                        // UI untuk Single Command
                        Row(modifier = Modifier.padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onRunCommand(msg.content) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE))) { Text("▶ Run") }
                            Button(onClick = { showSaveDialog = msg.content }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))) { Text("💾 Save") }
                        }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = inputText, onValueChange = { inputText = it }, modifier = Modifier.weight(1f), textStyle = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace), placeholder = { Text("Minta AI menyelesaikan tugas...", color = Color.Gray) })
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { if (inputText.isNotEmpty()) { onSendPrompt(inputText); inputText = "" } }) { Text("Kirim") }
            }
        } else if (selectedTab == 1) {
            Column(modifier = Modifier.weight(1f).padding(16.dp).verticalScroll(scrollState)) {
                if (snippets.isEmpty()) {
                    Text("Belum ada workflow tersimpan.", color = Color.Gray, fontSize = 14.sp)
                } else {
                    snippets.forEachIndexed { index, snippet ->
                        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2B2B))) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(snippet.title, color = Color(0xFF00FF00), fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                                Text(snippet.command, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
                                Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { onRunSnippet(snippet.command) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE))) { Text("▶ Run") }
                                    Button(onClick = { onDeleteSnippet(index) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))) { Text("Hapus") }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Column(modifier = Modifier.weight(1f).padding(16.dp).verticalScroll(scrollState)) {
                Text("Provider:", color = Color.Gray, fontSize = 12.sp)
                Box {
                    OutlinedTextField(value = settings.providerName, onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth().clickable { expandedProvider = true }, textStyle = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace), trailingIcon = { Text("▼", color = Color.White) })
                    DropdownMenu(expanded = expandedProvider, onDismissRequest = { expandedProvider = false }) {
                        AIProviders.presets.forEach { preset ->
                            DropdownMenuItem(text = { Text(preset.providerName) }, onClick = { onSettingsChanged(preset.copy(apiKey = settings.apiKey)); expandedProvider = false })
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
