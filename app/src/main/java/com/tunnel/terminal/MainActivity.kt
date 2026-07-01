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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    TerminalScreen()
                }
            }
        }
    }
}

@Composable
fun TerminalScreen() {
    val terminalHistory = remember { 
        mutableStateListOf(
            "Tunnel Terminal v1.0 (Phase 1)", 
            "Mesin eksekusi aktif. Ketik 'ls', 'pwd', atau 'date' lalu tekan Enter.", 
            ""
        ) 
    }
    var inputText by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    // Auto scroll ke bawah saat ada teks baru
    LaunchedEffect(terminalHistory.size) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    fun executeCommand(command: String) {
        // Tambahkan perintah yang diketik ke history
        terminalHistory.add("tunnel@android:~$ $command")
        inputText = ""

        // Perintah bawaan (built-in)
        if (command == "clear") {
            terminalHistory.clear()
            return
        }

        // Eksekusi perintah shell di background thread
        scope.launch {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val errorReader = BufferedReader(InputStreamReader(process.errorStream))
                
                // Baca output sukses
                val output = withContext(Dispatchers.IO) {
                    var text = ""
                    var line = reader.readLine()
                    while (line != null) {
                        text += line + "\n"
                        line = reader.readLine()
                    }
                    
                    // Baca output error
                    var error = ""
                    line = errorReader.readLine()
                    while (line != null) {
                        error += line + "\n"
                        line = errorReader.readLine()
                    }
                    
                    Pair(text, error)
                }
                
                // Tampilkan output ke layar
                if (output.first.isNotEmpty()) {
                    output.first.trim().split("\n").forEach { terminalHistory.add(it) }
                }
                if (output.second.isNotEmpty()) {
                    output.second.trim().split("\n").forEach { terminalHistory.add(it) }
                }
                
            } catch (e: Exception) {
                terminalHistory.add("Error: ${e.message}")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
            .verticalScroll(scrollState)
    ) {
        // Menampilkan History Terminal
        terminalHistory.forEach { line ->
            Text(
                text = line,
                color = Color(0xFF00FF00), // Hijau neon
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp
            )
        }

        // Area Input Pengguna
        Row(
            modifier = Modifier.fillMaxWidth(), 
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "tunnel@android:~$ ",
                color = Color(0xFF00FF00),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp
            )
            BasicTextField(
                value = inputText,
                onValueChange = { inputText = it },
                textStyle = TextStyle(
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                // Tangkap tombol Enter dari keyboard fisik/soft keyboard
                modifier = Modifier
                    .fillMaxWidth()
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.Enter && event.type == KeyEventType.KeyUp) {
                            executeCommand(inputText)
                            true // Konsumsi event
                        } else {
                            false
                        }
                    }
            )
        }
    }
}
