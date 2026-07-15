package com.tunnel.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Wave-25: CRUD UI for AI Skills inside the AI side panel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsPanel(
    theme: TerminalTheme,
    skills: List<AiSkill>,
    globalEnabled: Boolean,
    maxInjectChars: Int,
    onGlobalEnabledChange: (Boolean) -> Unit,
    onMaxCharsChange: (Int) -> Unit,
    onAdd: (name: String, description: String, content: String, scopes: Set<String>, priority: Int, keywords: String) -> Unit,
    onUpdate: (AiSkill) -> Unit,
    onDelete: (Long) -> Unit,
    onToggle: (Long, Boolean) -> Unit,
    onRestoreBuiltIns: () -> Unit,
    compact: Boolean = true
) {
    var editing by remember { mutableStateOf<AiSkill?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var confirmDeleteId by remember { mutableStateOf<Long?>(null) }
    val scroll = rememberScrollState()
    val hPad = if (compact) 10.dp else 16.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = hPad, vertical = 8.dp)
    ) {
        Text(
            "AI Skills",
            color = theme.uiText,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Instruksi khusus yang di-inject ke Chat, Agent, dan semua sesi (Local/Ubuntu/SSH).",
            color = theme.uiTextMuted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
        )

        /* Global settings */
        Card(
            colors = CardDefaults.cardColors(containerColor = theme.uiSurface),
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Skills global",
                        color = theme.uiText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = globalEnabled,
                        onCheckedChange = onGlobalEnabledChange,
                        colors = SwitchDefaults.colors(checkedTrackColor = theme.uiAccent)
                    )
                }
                Text(
                    if (globalEnabled) "Aktif — skill di-inject ke AI" else "OFF — tidak ada skill di-inject",
                    color = theme.uiTextMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Budget inject: $maxInjectChars chars",
                    color = theme.uiText,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Slider(
                    value = maxInjectChars.toFloat(),
                    onValueChange = { onMaxCharsChange(it.toInt()) },
                    valueRange = 2000f..12000f,
                    steps = 9,
                    colors = SliderDefaults.colors(thumbColor = theme.uiAccent, activeTrackColor = theme.uiAccent)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onRestoreBuiltIns) {
                        Text("Restore built-in", color = theme.uiAccent, fontSize = 11.sp)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${skills.count { it.enabled }}/${skills.size} aktif",
                color = theme.uiTextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Button(
                onClick = { showCreate = true },
                colors = ButtonDefaults.buttonColors(containerColor = theme.uiAccent),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("+ Skill", fontSize = 12.sp, color = Color.White)
            }
        }

        if (skills.isEmpty()) {
            Text(
                "Belum ada skill. Ketuk + Skill atau Restore built-in.",
                color = theme.uiTextMuted,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        skills.forEach { skill ->
            Card(
                colors = CardDefaults.cardColors(containerColor = theme.uiSurface),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clickable { editing = skill }
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                skill.name + if (skill.isBuiltIn) " · built-in" else "",
                                color = theme.uiText,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            if (skill.description.isNotBlank()) {
                                Text(
                                    skill.description,
                                    color = theme.uiTextMuted,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 2
                                )
                            }
                        }
                        Switch(
                            checked = skill.enabled,
                            onCheckedChange = { onToggle(skill.id, it) },
                            colors = SwitchDefaults.colors(checkedTrackColor = theme.uiAccent)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "scope: ${skill.scopes.joinToString { SkillManager.scopeLabel(it) }} · prio ${skill.priority}" +
                            if (skill.triggerKeywords.isNotEmpty()) " · kw: ${skill.triggerKeywords.joinToString()}" else "",
                        color = theme.uiTextMuted,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        skill.content.take(120) + if (skill.content.length > 120) "…" else "",
                        color = theme.uiText.copy(alpha = 0.85f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 6.dp)
                    ) {
                        TextButton(
                            onClick = { editing = skill },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text("Edit", color = theme.uiAccent, fontSize = 11.sp)
                        }
                        TextButton(
                            onClick = { confirmDeleteId = skill.id },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text("Hapus", color = Color(0xFFFF5252), fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        SkillEditorDialog(
            theme = theme,
            title = "Skill baru",
            initial = null,
            onDismiss = { showCreate = false },
            onSave = { name, desc, content, scopes, prio, kws ->
                onAdd(name, desc, content, scopes, prio, kws)
                showCreate = false
            }
        )
    }
    editing?.let { skill ->
        SkillEditorDialog(
            theme = theme,
            title = "Edit skill",
            initial = skill,
            onDismiss = { editing = null },
            onSave = { name, desc, content, scopes, prio, kws ->
                onUpdate(
                    skill.copy(
                        name = name,
                        description = desc,
                        content = content,
                        scopes = scopes,
                        priority = prio,
                        triggerKeywords = kws.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    )
                )
                editing = null
            }
        )
    }
    confirmDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDeleteId = null },
            title = { Text("Hapus skill?", color = theme.uiText) },
            text = {
                Text(
                    skills.firstOrNull { it.id == id }?.name ?: "Skill",
                    color = theme.uiTextMuted
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(id)
                    confirmDeleteId = null
                }) { Text("Hapus", color = Color(0xFFFF5252)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteId = null }) {
                    Text("Batal", color = theme.uiTextMuted)
                }
            },
            containerColor = theme.uiBg
        )
    }
}

@Composable
private fun SkillEditorDialog(
    theme: TerminalTheme,
    title: String,
    initial: AiSkill?,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String, content: String, scopes: Set<String>, priority: Int, keywordsCsv: String) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var content by remember { mutableStateOf(initial?.content ?: "") }
    var scopes by remember {
        mutableStateOf(initial?.scopes?.toMutableSet() ?: mutableSetOf("always"))
    }
    var priority by remember { mutableFloatStateOf((initial?.priority ?: 50).toFloat()) }
    var keywords by remember {
        mutableStateOf(initial?.triggerKeywords?.joinToString(", ") ?: "")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.96f),
        containerColor = theme.uiBg,
        title = {
            Text(title, color = theme.uiText, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    label = { Text("Nama", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = theme.uiText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    ),
                    colors = fieldColors(theme)
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(200) },
                    label = { Text("Deskripsi singkat", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = theme.uiText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    ),
                    colors = fieldColors(theme)
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it.take(8000) },
                    label = { Text("Isi skill (instruksi AI)", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                    minLines = 5,
                    maxLines = 12,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = theme.uiText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    ),
                    colors = fieldColors(theme)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("Scope aktif:", color = theme.uiTextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                SkillManager.ALL_SCOPES.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { sc ->
                            FilterChip(
                                selected = sc in scopes,
                                onClick = {
                                    scopes = scopes.toMutableSet().also { set ->
                                        if (sc in set) {
                                            if (set.size > 1) set.remove(sc)
                                        } else set.add(sc)
                                    }
                                },
                                label = {
                                    Text(SkillManager.scopeLabel(sc), fontSize = 10.sp)
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Priority: ${priority.toInt()} (lebih tinggi = lebih dulu)",
                    color = theme.uiText,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Slider(
                    value = priority,
                    onValueChange = { priority = it },
                    valueRange = 0f..100f,
                    colors = SliderDefaults.colors(
                        thumbColor = theme.uiAccent,
                        activeTrackColor = theme.uiAccent
                    )
                )
                OutlinedTextField(
                    value = keywords,
                    onValueChange = { keywords = it.take(200) },
                    label = { Text("Trigger keywords (opsional, pisah koma)", fontSize = 11.sp) },
                    placeholder = { Text("python, docker, nginx", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = theme.uiText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    ),
                    colors = fieldColors(theme)
                )
                Text(
                    "Kosong = selalu ikut jika scope cocok. Isi = hanya jika prompt user mengandung kata.",
                    color = theme.uiTextMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank() && content.isNotBlank()) {
                        onSave(
                            name.trim(),
                            description.trim(),
                            content.trim(),
                            scopes.toSet(),
                            priority.toInt(),
                            keywords
                        )
                    }
                },
                enabled = name.isNotBlank() && content.isNotBlank()
            ) {
                Text("Simpan", color = theme.uiAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal", color = theme.uiTextMuted)
            }
        }
    )
}

@Composable
private fun fieldColors(theme: TerminalTheme) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = theme.uiAccent,
    unfocusedBorderColor = theme.uiSurface,
    cursorColor = theme.uiAccent,
    focusedTextColor = theme.uiText,
    unfocusedTextColor = theme.uiText,
    focusedLabelColor = theme.uiTextMuted,
    unfocusedLabelColor = theme.uiTextMuted
)
