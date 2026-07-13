package com.tunnel.terminal

import android.content.Context

/**
 * Wave-9: Read/clear TOFU host keys used by SshShellExecutor.
 */
object SshHostKeyStore {
    data class Entry(val hostPort: String, val fingerprint: String)

    fun list(context: Context): List<Entry> {
        val prefs = context.getSharedPreferences(SecureStorage.SSH_HOSTKEYS_PREFS, Context.MODE_PRIVATE)
        return prefs.all.mapNotNull { (k, v) ->
            val fp = v as? String ?: return@mapNotNull null
            if (fp.isBlank()) return@mapNotNull null
            Entry(k, fp.take(120))
        }.sortedBy { it.hostPort }
    }

    fun clearAll(context: Context) {
        context.getSharedPreferences(SecureStorage.SSH_HOSTKEYS_PREFS, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    fun remove(context: Context, hostPort: String) {
        context.getSharedPreferences(SecureStorage.SSH_HOSTKEYS_PREFS, Context.MODE_PRIVATE)
            .edit().remove(hostPort).apply()
    }

    fun formatList(context: Context): String {
        val entries = list(context)
        if (entries.isEmpty()) return "(no saved SSH host keys)"
        return entries.joinToString("\n") { e ->
            "${e.hostPort}\n  ${e.fingerprint}"
        }
    }
}
