package com.tunnel.terminal

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wave-25: User-editable AI Skills — instruction packs injected into every AI path
 * (chat, agent, auto-pilot, local / Ubuntu / SSH).
 *
 * Similar to Claude Code / Cursor rules: named skills with scopes, optional
 * keyword triggers, enable toggle, and priority ordering.
 */
data class AiSkill(
    val id: Long,
    val name: String,
    val description: String,
    val content: String,
    val enabled: Boolean = true,
    /** Scopes: always, chat, agent, local, ubuntu, ssh */
    val scopes: Set<String> = setOf("always"),
    /** Higher = earlier in prompt (more important). */
    val priority: Int = 50,
    /** If non-empty, skill auto-includes when user prompt contains any keyword (case-insensitive). */
    val triggerKeywords: List<String> = emptyList(),
    val isBuiltIn: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun matchesScope(sessionType: String, mode: String): Boolean {
        if (!enabled) return false
        if ("always" in scopes) return true
        if (mode in scopes) return true
        if (sessionType in scopes) return true
        return false
    }

    fun matchesKeywords(userText: String): Boolean {
        if (triggerKeywords.isEmpty()) return true /* no keyword gate */
        val lower = userText.lowercase()
        return triggerKeywords.any { kw -> kw.isNotBlank() && lower.contains(kw.lowercase()) }
    }
}

class SkillManager(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _skills = mutableListOf<AiSkill>()
    private var nextId = 1L
    private var seeded = false

    val skills: List<AiSkill> get() = _skills.sortedWith(
        compareByDescending<AiSkill> { it.priority }.thenBy { it.name.lowercase() }
    )

    companion object {
        private const val PREFS = "TunnelAiSkills"
        private const val KEY_SKILLS = "skills_v1"
        private const val KEY_SEEDED = "seeded_v1"
        private const val KEY_GLOBAL_ENABLED = "global_enabled"
        private const val KEY_MAX_CHARS = "max_chars"
        private const val MAX_SKILLS = 80
        const val DEFAULT_MAX_CHARS = 6000

        val ALL_SCOPES = listOf("always", "chat", "agent", "local", "ubuntu", "ssh")

        fun scopeLabel(scope: String): String = when (scope) {
            "always" -> "Selalu"
            "chat" -> "Chat AI"
            "agent" -> "Agent"
            "local" -> "Terminal Local"
            "ubuntu" -> "Ubuntu"
            "ssh" -> "SSH"
            else -> scope
        }
    }

    var globalEnabled: Boolean
        get() = prefs.getBoolean(KEY_GLOBAL_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_GLOBAL_ENABLED, value).apply()

    /** Cap total skill text injected into one prompt (token budget). */
    var maxInjectChars: Int
        get() = prefs.getInt(KEY_MAX_CHARS, DEFAULT_MAX_CHARS).coerceIn(1000, 20000)
        set(value) = prefs.edit().putInt(KEY_MAX_CHARS, value.coerceIn(1000, 20000)).apply()

    init {
        load()
        if (!seeded || _skills.isEmpty()) {
            seedBuiltIns()
        }
    }

    private fun load() {
        seeded = prefs.getBoolean(KEY_SEEDED, false)
        val json = prefs.getString(KEY_SKILLS, "[]") ?: "[]"
        try {
            val arr = JSONArray(json)
            var maxId = 0L
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optLong("id", 0L).let { if (it == 0L) System.currentTimeMillis() + i else it }
                val scopesArr = o.optJSONArray("scopes")
                val scopes = mutableSetOf<String>()
                if (scopesArr != null) {
                    for (j in 0 until scopesArr.length()) scopes.add(scopesArr.getString(j))
                }
                if (scopes.isEmpty()) scopes.add("always")
                val kwArr = o.optJSONArray("keywords")
                val kws = mutableListOf<String>()
                if (kwArr != null) {
                    for (j in 0 until kwArr.length()) kws.add(kwArr.getString(j))
                }
                _skills.add(
                    AiSkill(
                        id = id,
                        name = o.optString("name", "Skill"),
                        description = o.optString("description", ""),
                        content = o.optString("content", ""),
                        enabled = o.optBoolean("enabled", true),
                        scopes = scopes,
                        priority = o.optInt("priority", 50),
                        triggerKeywords = kws,
                        isBuiltIn = o.optBoolean("builtIn", false),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                        updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
                    )
                )
                if (id > maxId) maxId = id
            }
            nextId = maxId + 1
        } catch (_: Exception) {
            _skills.clear()
        }
    }

    private fun save() {
        val arr = JSONArray()
        _skills.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("description", s.description)
                    .put("content", s.content)
                    .put("enabled", s.enabled)
                    .put("scopes", JSONArray(s.scopes.toList()))
                    .put("priority", s.priority)
                    .put("keywords", JSONArray(s.triggerKeywords))
                    .put("builtIn", s.isBuiltIn)
                    .put("createdAt", s.createdAt)
                    .put("updatedAt", s.updatedAt)
            )
        }
        prefs.edit()
            .putString(KEY_SKILLS, arr.toString())
            .putBoolean(KEY_SEEDED, true)
            .apply()
        seeded = true
    }

    private fun seedBuiltIns() {
        val now = System.currentTimeMillis()
        val builtins = listOf(
            AiSkill(
                id = nextId++,
                name = "Ubuntu Proot Expert",
                description = "Path /root, apt non-interactive, no systemd",
                content = """
                    Saat sesi Ubuntu (proot):
                    - Path relatif write_file → /root/ di guest
                    - run_command di bash Ubuntu; jangan pakai path /data/data/...
                    - apt: DEBIAN_FRONTEND=noninteractive apt-get install -y <pkg>
                    - Tidak ada systemctl — jalankan servis dengan &
                    - Workspace Android tersedia di /mnt/workspace
                """.trimIndent(),
                enabled = true,
                scopes = setOf("ubuntu", "agent"),
                priority = 90,
                isBuiltIn = true,
                createdAt = now,
                updatedAt = now
            ),
            AiSkill(
                id = nextId++,
                name = "Android Local Shell",
                description = "Toybox/sh limits — no apt/sudo",
                content = """
                    Saat sesi Local Android shell:
                    - Shell = /system/bin/sh (toybox), bukan bash penuh
                    - Tidak ada apt/yum/brew/sudo
                    - Path relatif write_file → workspace app Android
                    - Gunakan tool write_file/read_file, jangan andalkan cat > untuk file besar
                """.trimIndent(),
                enabled = true,
                scopes = setOf("local"),
                priority = 80,
                isBuiltIn = true,
                createdAt = now,
                updatedAt = now
            ),
            AiSkill(
                id = nextId++,
                name = "Terminal Safety",
                description = "Non-interactive flags, avoid TUI hang",
                content = """
                    Selalu non-interactive:
                    - apt/pip/npm: flag -y / --yes / --no-input
                    - Jangan jalankan vim/nano/less/top interaktif tanpa exit path
                    - rm/cp/mv: gunakan -f bila overwrite disengaja
                    - Jangan rm -rf / atau path sistem kritis
                """.trimIndent(),
                enabled = true,
                scopes = setOf("always"),
                priority = 85,
                isBuiltIn = true,
                createdAt = now,
                updatedAt = now
            ),
            AiSkill(
                id = nextId++,
                name = "Code Deliverable",
                description = "Always write files via tools, not chat-only code blocks",
                content = """
                    Jika user minta membuat file/program:
                    1. WAJIB write_file (atau edit_file) — jangan hanya code block di chat
                    2. Setelah menulis, verifikasi dengan run_command (python/ls/cat head)
                    3. Konfirmasi path file yang dibuat (guest path di Ubuntu)
                """.trimIndent(),
                enabled = true,
                scopes = setOf("chat", "agent"),
                priority = 75,
                isBuiltIn = true,
                createdAt = now,
                updatedAt = now
            ),
            AiSkill(
                id = nextId++,
                name = "SSH Remote",
                description = "Ask distro; prefer run_command / SFTP",
                content = """
                    Saat sesi SSH:
                    - Tanya distribusi bila belum jelas (apt vs dnf vs pacman)
                    - File I/O lewat tools SFTP bila tersedia
                    - Jangan asumsikan path Android
                """.trimIndent(),
                enabled = true,
                scopes = setOf("ssh"),
                priority = 80,
                isBuiltIn = true,
                createdAt = now,
                updatedAt = now
            )
        )
        /* Don't wipe user skills if re-seed — only add missing built-in names. */
        val existingNames = _skills.map { it.name }.toSet()
        builtins.forEach { b ->
            if (b.name !in existingNames) _skills.add(b)
        }
        save()
    }

    fun get(id: Long): AiSkill? = _skills.firstOrNull { it.id == id }

    fun add(
        name: String,
        description: String,
        content: String,
        scopes: Set<String> = setOf("always"),
        priority: Int = 50,
        triggerKeywords: List<String> = emptyList(),
        enabled: Boolean = true
    ): AiSkill? {
        if (_skills.size >= MAX_SKILLS) return null
        val now = System.currentTimeMillis()
        val skill = AiSkill(
            id = nextId++,
            name = name.trim().ifBlank { "Skill ${nextId}" },
            description = description.trim(),
            content = content.trim(),
            enabled = enabled,
            scopes = if (scopes.isEmpty()) setOf("always") else scopes,
            priority = priority.coerceIn(0, 100),
            triggerKeywords = triggerKeywords.map { it.trim() }.filter { it.isNotEmpty() },
            isBuiltIn = false,
            createdAt = now,
            updatedAt = now
        )
        _skills.add(skill)
        save()
        return skill
    }

    fun update(skill: AiSkill): Boolean {
        val idx = _skills.indexOfFirst { it.id == skill.id }
        if (idx < 0) return false
        _skills[idx] = skill.copy(updatedAt = System.currentTimeMillis())
        save()
        return true
    }

    fun setEnabled(id: Long, enabled: Boolean): Boolean {
        val s = get(id) ?: return false
        return update(s.copy(enabled = enabled))
    }

    fun remove(id: Long): Boolean {
        val removed = _skills.removeAll { it.id == id }
        if (removed) save()
        return removed
    }

    /** Re-add missing built-in skills without deleting custom ones. */
    fun restoreBuiltIns() {
        seedBuiltIns()
    }

    fun clearCustomSkills() {
        _skills.removeAll { !it.isBuiltIn }
        save()
    }

    /**
     * Build markdown section for system/context injection.
     *
     * @param sessionType local | ubuntu | ssh
     * @param mode chat | agent
     * @param userPrompt current user text (for keyword triggers)
     */
    fun buildSkillsContext(
        sessionType: String,
        mode: String,
        userPrompt: String = ""
    ): String {
        if (!globalEnabled) return ""
        val selected = skills.filter { skill ->
            skill.enabled &&
                skill.matchesScope(sessionType, mode) &&
                (skill.triggerKeywords.isEmpty() || skill.matchesKeywords(userPrompt))
        }
        if (selected.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("## AI SKILLS AKTIF (ikuti instruksi ini)")
        var used = sb.length
        val budget = maxInjectChars
        for (skill in selected) {
            val block = buildString {
                appendLine()
                appendLine("### Skill: ${skill.name}")
                if (skill.description.isNotBlank()) appendLine("(${skill.description})")
                appendLine(skill.content)
            }
            if (used + block.length > budget) {
                sb.appendLine()
                sb.appendLine("… (${selected.size} skills; dipotong budget $budget char)")
                break
            }
            sb.append(block)
            used += block.length
        }
        return sb.toString().trimEnd()
    }

    fun summaryLine(): String {
        val on = skills.count { it.enabled }
        val total = skills.size
        return if (!globalEnabled) "Skills OFF ($total tersimpan)"
        else "Skills $on/$total aktif · max ${maxInjectChars}c"
    }
}
