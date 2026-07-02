package com.tunnel.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * SshConnectDialog - Form untuk koneksi SSH baru.
 * Form for new SSH connection.
 *
 * Phase 21: SSH client UI. User input host/port/username/password (atau private key path).
 * On Connect, callback dipanggil dengan SshConnectionConfig.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshConnectDialog(
    theme: TerminalTheme,
    onConnect: (SshConnectionConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var privateKeyPath by remember { mutableStateOf("") }
    var privateKeyPassphrase by remember { mutableStateOf("") }
    var useKeyAuth by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.95f).background(theme.uiBg),
        title = {
            Text(
                "🔌 SSH Connection",
                color = theme.uiText,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                /* Connection name (optional, untuk tab label). */
                Text("Name (optional):", color = theme.uiTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = theme.uiText, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    placeholder = { Text("my-server", color = theme.uiTextMuted, fontSize = 11.sp) },
                    singleLine = true
                )

                Text("Host:", color = theme.uiTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = theme.uiText, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    placeholder = { Text("192.168.1.1 atau example.com", color = theme.uiTextMuted, fontSize = 11.sp) },
                    singleLine = true
                )

                Text("Port:", color = theme.uiTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = theme.uiText, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )

                Text("Username:", color = theme.uiTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = theme.uiText, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    placeholder = { Text("root", color = theme.uiTextMuted, fontSize = 11.sp) },
                    singleLine = true
                )

                /* Auth method toggle. */
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Checkbox(
                        checked = useKeyAuth,
                        onCheckedChange = { useKeyAuth = it },
                        colors = CheckboxDefaults.colors(checkedColor = theme.uiAccent)
                    )
                    Text(
                        "Use private key (instead of password)",
                        color = theme.uiText,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                if (!useKeyAuth) {
                    Text("Password:", color = theme.uiTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(color = theme.uiText, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true
                    )
                } else {
                    Text("Private Key Path:", color = theme.uiTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    OutlinedTextField(
                        value = privateKeyPath,
                        onValueChange = { privateKeyPath = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(color = theme.uiText, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        placeholder = { Text("/sdcard/id_rsa atau ~/home/.ssh/id_rsa", color = theme.uiTextMuted, fontSize = 11.sp) },
                        singleLine = true
                    )
                    Text("Key Passphrase (optional):", color = theme.uiTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    OutlinedTextField(
                        value = privateKeyPassphrase,
                        onValueChange = { privateKeyPassphrase = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(color = theme.uiText, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                }

                error?.let {
                    Text(
                        "⚠ $it",
                        color = Color(0xFFFF8A80),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    /* Validate. */
                    when {
                        host.isBlank() -> error = "Host tidak boleh kosong"
                        username.isBlank() -> error = "Username tidak boleh kosong"
                        !useKeyAuth && password.isBlank() -> error = "Password tidak boleh kosong"
                        useKeyAuth && privateKeyPath.isBlank() -> error = "Private key path tidak boleh kosong"
                        port.isBlank() || port.toIntOrNull()?.let { it < 1 || it > 65535 } != false -> error = "Port harus 1-65535"
                        else -> {
                            error = null
                            onConnect(
                                SshConnectionConfig(
                                    host = host.trim(),
                                    port = port.toInt(),
                                    username = username.trim(),
                                    password = password,
                                    privateKeyPath = privateKeyPath.trim(),
                                    privateKeyPassphrase = privateKeyPassphrase,
                                    name = name.ifBlank { "$username@$host" }
                                )
                            )
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = theme.uiAccent)
            ) { Text("Connect", color = theme.uiText) }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = theme.uiSurface)
            ) { Text("Cancel", color = theme.uiText) }
        }
    )
}
