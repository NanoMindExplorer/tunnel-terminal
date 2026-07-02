package com.tunnel.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * FileExplorerPanel - Sidebar untuk browse file system tanpa ketik `cd`.
 *
 * Phase 19: File explorer drawer.
 * - Browse direktori (mulai dari home app, bisa navigate ke parent/child)
 * - Tap folder: masuk ke folder
 * - Tap file: panggil callback (biasanya buka di TunnelEditor)
 * - Path breadcrumb di atas
 * - Tombol home + parent + refresh
 *
 * Tidak butuh izin khusus untuk akses dalam app sandbox (filesDir/home).
 * Untuk akses /sdcard butuh setup-storage (SAF) dulu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileExplorerPanel(
    initialDir: File,
    theme: TerminalTheme,
    onFileOpen: (File) -> Unit,
    onFolderNavigate: (File) -> Unit,
    onClose: () -> Unit
) {
    var currentDir by remember { mutableStateOf(initialDir) }
    var dirEntries by remember { mutableStateOf(listOf<File>()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    /* Load directory contents. */
    fun loadDir(dir: File) {
        try {
            if (!dir.exists() || !dir.isDirectory) {
                errorMsg = "Bukan direktori atau tidak ada: ${dir.absolutePath}"
                return
            }
            if (!dir.canRead()) {
                errorMsg = "Tidak bisa baca direktori (permission denied): ${dir.absolutePath}"
                return
            }
            val files = dir.listFiles()?.toList()?.sortedWith(
                compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
            ) ?: emptyList()
            dirEntries = files
            currentDir = dir
            errorMsg = null
            onFolderNavigate(dir)
        } catch (e: Exception) {
            errorMsg = "Error: ${e.message}"
        }
    }

    /* Initial load. */
    LaunchedEffect(initialDir.absolutePath) {
        loadDir(initialDir)
    }

    Column(modifier = Modifier.fillMaxSize().background(theme.uiBg)) {
        /* Header dengan breadcrumb + tombol. */
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "File Explorer",
                color = theme.uiText,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Text("X", color = theme.uiText, fontSize = 14.sp)
            }
        }

        /* Breadcrumb / path display. */
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = { /* Home = app home dir */
                    val home = File(initialDir.absolutePath).let {
                        /* Cari parent sampai filesDir/home atau /data/data */
                        var p: File = it
                        while (p.parentFile != null && !p.absolutePath.endsWith("/home")) {
                            p = p.parentFile!!
                            if (p.absolutePath == "/") break
                        }
                        if (p.absolutePath.endsWith("/home")) p else initialDir
                    }
                    loadDir(home)
                },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(Icons.Default.Home, contentDescription = "Home", tint = theme.uiAccent, modifier = Modifier.size(16.dp))
            }
            IconButton(
                onClick = { currentDir.parentFile?.let { loadDir(it) } },
                enabled = currentDir.parentFile != null,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(Icons.Default.ArrowUpward, contentDescription = "Parent", tint = theme.uiAccent, modifier = Modifier.size(16.dp))
            }
            IconButton(
                onClick = { loadDir(currentDir) },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = theme.uiAccent, modifier = Modifier.size(16.dp))
            }
        }

        /* Current path. */
        Text(
            currentDir.absolutePath,
            color = theme.uiTextMuted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
        )

        Divider(color = theme.uiSurface, thickness = 1.dp)

        /* Error message if any. */
        if (errorMsg != null) {
            Text(
                errorMsg!!,
                color = Color(0xFFFF8A80),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(12.dp)
            )
        }

        /* File list. */
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            if (dirEntries.isEmpty() && errorMsg == null) {
                item {
                    Text(
                        "(empty directory)",
                        color = theme.uiTextMuted,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            items(dirEntries) { file ->
                FileEntryRow(
                    file = file,
                    theme = theme,
                    onClick = {
                        if (file.isDirectory) {
                            loadDir(file)
                        } else {
                            onFileOpen(file)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun FileEntryRow(
    file: File,
    theme: TerminalTheme,
    onClick: () -> Unit
) {
    val isDir = file.isDirectory
    val iconName: ImageVector = when {
        isDir -> Icons.Default.Folder
        file.extension.lowercase() in listOf("kt", "java", "py", "js", "ts", "sh", "cpp", "c", "h", "xml", "json", "yaml", "yml", "md", "txt") -> Icons.Default.Description
        file.extension.lowercase() in listOf("png", "jpg", "jpeg", "gif", "webp", "bmp") -> Icons.Default.Image
        else -> Icons.Default.InsertDriveFile
    }
    val iconColor = when {
        isDir -> theme.ansi.getOrElse(4) { Color(0xFF2196F3) }
        file.extension.lowercase() in listOf("kt", "java", "py", "js", "ts", "sh", "cpp", "c", "h") -> theme.ansi.getOrElse(2) { Color(0xFF4CAF50) }
        else -> theme.uiTextMuted
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(iconName, contentDescription = null, tint = iconColor, modifier = Modifier.size(18.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                file.name + if (isDir) "/" else "",
                color = if (isDir) theme.uiAccent else theme.uiText,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            if (!isDir) {
                val sizeStr = formatFileSize(file.length())
                Text(
                    "$sizeStr",
                    color = theme.uiTextMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)}MB"
        else -> "${bytes / (1024 * 1024 * 1024)}GB"
    }
}

/**
 * WorkspaceSessionDialog - Dialog untuk save/restore/delete workspace sessions.
 *
 * Phase 19: Save/restore tab sets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceSessionDialog(
    theme: TerminalTheme,
    sessions: List<WorkspaceSession>,
    currentTabCount: Int,
    onSaveSession: (String) -> Boolean,
    onRestoreSession: (WorkspaceSession) -> Unit,
    onDeleteSession: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newSessionName by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.95f).background(theme.uiBg),
        title = {
            Column {
                Text(
                    "💾 Workspace Sessions",
                    color = theme.uiText,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "Tab aktif: $currentTabCount",
                    color = theme.uiTextMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(scrollState)
            ) {
                /* Save current session section. */
                Text(
                    "Simpan sesi saat ini:",
                    color = theme.ansi.getOrElse(6) { Color(0xFF00BCD4) },
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = newSessionName,
                        onValueChange = { newSessionName = it },
                        modifier = Modifier.weight(1f),
                        textStyle = TextStyle(color = theme.uiText, fontFamily = FontFamily.Monospace),
                        placeholder = { Text("nama session", color = theme.uiTextMuted, fontSize = 11.sp) },
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            if (newSessionName.isNotBlank()) {
                                val ok = onSaveSession(newSessionName.trim())
                                if (ok) newSessionName = ""
                            }
                        },
                        enabled = newSessionName.isNotBlank() && currentTabCount > 0,
                        colors = ButtonDefaults.buttonColors(containerColor = theme.uiAccent),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text("Save", color = theme.uiText, fontSize = 11.sp)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Divider(color = theme.uiSurface, thickness = 1.dp)
                Spacer(modifier = Modifier.height(8.dp))

                /* Saved sessions list. */
                Text(
                    "Session tersimpan (${sessions.size}/20):",
                    color = theme.ansi.getOrElse(6) { Color(0xFF00BCD4) },
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (sessions.isEmpty()) {
                    Text(
                        "(belum ada session tersimpan)",
                        color = theme.uiTextMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                } else {
                    sessions.forEach { session ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            colors = CardDefaults.cardColors(containerColor = theme.uiSurface)
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        session.name,
                                        color = theme.ansi.getOrElse(2) { Color(0xFF4CAF50) },
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        "${session.tabCount} tab",
                                        color = theme.uiTextMuted,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Text(
                                    "Saved: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(session.createdAt))}",
                                    color = theme.uiTextMuted,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                if (session.workingDirs.isNotEmpty()) {
                                    Text(
                                        "Dirs: ${session.workingDirs.take(3).joinToString(", ")}${if (session.workingDirs.size > 3) "..." else ""}",
                                        color = theme.uiTextMuted,
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1
                                    )
                                }
                                Row(
                                    modifier = Modifier.padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { onRestoreSession(session) },
                                        colors = ButtonDefaults.buttonColors(containerColor = theme.uiAccent),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                    ) {
                                        Text("Restore", color = theme.uiText, fontSize = 11.sp)
                                    }
                                    Button(
                                        onClick = { onDeleteSession(session.name) },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                    ) {
                                        Text("Delete", color = Color.White, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = theme.uiSurface)
            ) { Text("Close", color = theme.uiText) }
        }
    )
}
