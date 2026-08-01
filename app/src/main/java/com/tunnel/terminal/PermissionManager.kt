package com.tunnel.terminal

import android.content.Context

/**
 * PermissionManager - Manage permission untuk AI tool calls.
 *
 * Phase 22: Permission flow (like Claude Code).
 * User approve/deny sebelum AI run destructive tools.
 *
 * Phase 43 fix (HIGH-05): Permission scope PER-SESSION (tab).
 * OLD BUG: "Always Allow" untuk write_file bersifat GLOBAL — user yang approve
 * di tab Local ikut approve di tab SSH/Ubuntu (konteks risiko berbeda).
 * FIX: Sertakan sessionId di key permission: "tool_<sessionId>_<tool>".
 * Saat pindah tab, permission "Always Allow" dari tab lain tidak berlaku.
 *
 * v9.2.0 fix (H-1c): Extracted from AiToolCall.kt untuk modularitas.
 */
class PermissionManager(context: Context) {
    private val prefs = context.getSharedPreferences("TunnelPermissions", Context.MODE_PRIVATE)

    /** Permission state per tool. */
    enum class PermissionState { ASK, ALWAYS_ALLOW, ALWAYS_DENY }

    /** BUG-01 fix: Tools yang TIDAK boleh "Always Allow" — terlalu berbahaya
     * jika AI di-inject via indirect prompt injection. */
    private val alwaysDenyAlwaysAllow = setOf("run_command", "delete_file")

    /** Session ID aktif saat ini. Diperbarui saat user pindah tab. */
    @Volatile
    private var activeSessionId: Int = 0

    /** Update session ID aktif (dipanggil saat user pindah tab). */
    fun setActiveSession(sessionId: Int) {
        activeSessionId = sessionId
    }

    /** Build permission key dengan scope session. */
    private fun permissionKey(tool: String): String = "tool_${activeSessionId}_$tool"

    /** Get permission state for tool (scoped ke session aktif). */
    fun getPermission(tool: String): PermissionState {
        val state = prefs.getString(permissionKey(tool), PermissionState.ASK.name)
        return runCatching { PermissionState.valueOf(state!!) }.getOrDefault(PermissionState.ASK)
    }

    /** Set permission state for tool (scoped ke session aktif).
     * BUG-01 fix: Tolak ALWAYS_ALLOW untuk run_command/delete_file. */
    fun setPermission(tool: String, state: PermissionState) {
        val effectiveState = if (tool in alwaysDenyAlwaysAllow && state == PermissionState.ALWAYS_ALLOW) {
            PermissionState.ASK // Degrade ke ASK — terlalu berbahaya untuk blanket allow
        } else {
            state
        }
        prefs.edit().putString(permissionKey(tool), effectiveState.name).apply()
    }

    /** Check if tool call needs permission prompt. */
    fun needsPrompt(call: AiToolCall): Boolean {
        if (call.isReadOnly) return false
        val state = getPermission(call.tool)
        /* Wave-7: ALWAYS_DENY skips prompt (caller should treat as denied). */
        if (state == PermissionState.ALWAYS_DENY) return false
        // BUG-01 fix: run_command dan delete_file SELALU butuh prompt (unless denied)
        if (call.tool in alwaysDenyAlwaysAllow) return true
        return state == PermissionState.ASK
    }

    /** Check if tool call is pre-approved. */
    fun isApproved(call: AiToolCall): Boolean {
        if (call.isReadOnly) return true
        val state = getPermission(call.tool)
        if (state == PermissionState.ALWAYS_DENY) return false
        // BUG-01 fix: run_command dan delete_file tidak pernah pre-approved
        if (call.tool in alwaysDenyAlwaysAllow) return false
        return state == PermissionState.ALWAYS_ALLOW
    }

    /** BUG-01 fix: Check apakah tool boleh di-"Always Allow". */
    fun canAlwaysAllow(tool: String): Boolean = tool !in alwaysDenyAlwaysAllow

    /** Reset all permissions to ASK (untuk session aktif). */
    fun resetAll() {
        /* Phase 43 fix: Hanya reset permission untuk session aktif, bukan semua session. */
        val keysToRemove = prefs.all.keys.filter { it.startsWith("tool_${activeSessionId}_") }
        val editor = prefs.edit()
        keysToRemove.forEach { editor.remove(it) }
        editor.apply()
    }
}
