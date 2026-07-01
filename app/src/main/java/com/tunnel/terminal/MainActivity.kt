package com.tunnel.terminal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    // Menyimpan history terminal
    val terminalHistory = remember { mutableStateListOf("Tunnel Terminal v1.0 (Phase 1)", "Type 'help' to begin.") }
    var inputText by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    // Auto scroll ke bawah saat ada teks baru
    LaunchedEffect(terminalHistory.size) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
            .verticalScroll(scrollState)
    ) {
        // Menampilkan History
        terminalHistory.forEach { line ->
            androidx.compose.material3.Text(
                text = line,
                color = Color(0xFF00FF00), // Hijau neon
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp
            )
        }

        // Area Input Pengguna
        Row(modifier = Modifier.fillMaxWidth()) {
            androidx.compose.material3.Text(
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
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
