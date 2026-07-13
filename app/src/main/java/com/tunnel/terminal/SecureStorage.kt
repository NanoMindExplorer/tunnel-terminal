package com.tunnel.terminal

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * SecureStorage — Wrapper untuk EncryptedSharedPreferences.
 *
 * Phase 41 fix (CRIT-01): API key & kredensial SSH sebelumnya disimpan plaintext
 * di SharedPreferences biasa.
 *
 * Wave-2: Fail-closed — jangan fallback ke plaintext prefs. Kalau Keystore /
 * EncryptedSharedPreferences gagal, throw agar caller menolak simpan secret.
 */
object SecureStorage {
    private const val TAG = "SecureStorage"

    /** Nama file encrypted prefs untuk AI settings. */
    private const val AI_PREFS_NAME = "TunnelAIPrefs_secure"
    /** Nama file plaintext prefs lama (untuk migrasi). */
    private const val AI_PREFS_LEGACY = "TunnelAIPrefs"
    /** Nama file encrypted prefs untuk SSH credentials. */
    private const val SSH_PREFS_NAME = "TunnelSshCredentials_secure"
    /** Encrypted prefs for MCP API keys (Wave-2). */
    private const val MCP_KEYS_PREFS = "TunnelMcpKeys_secure"
    /** Nama file plaintext prefs lama untuk SSH host keys (tetap plaintext — bukan secret). */
    const val SSH_HOSTKEYS_PREFS = "TunnelSshHostKeys"

    /** Wave-2: true only when last getEncryptedPrefs succeeded. */
    @Volatile
    var isEncryptionAvailable: Boolean = true
        private set

    /** Wave-2: last failure reason (for UI toast). */
    @Volatile
    var lastEncryptionError: String? = null
        private set

    /**
     * Buat MasterKey untuk EncryptedSharedPreferences.
     * Key disimpan di Android Keystore (hardware-backed di device yang support).
     */
    private fun getMasterKey(context: Context): MasterKey {
        return MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    /**
     * Buat instance EncryptedSharedPreferences.
     * Wave-2: fail-closed — throw SecurityException instead of plaintext fallback.
     */
    fun getEncryptedPrefs(context: Context, fileName: String): SharedPreferences {
        return try {
            val prefs = EncryptedSharedPreferences.create(
                context,
                fileName,
                getMasterKey(context),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            isEncryptionAvailable = true
            lastEncryptionError = null
            prefs
        } catch (e: Exception) {
            isEncryptionAvailable = false
            lastEncryptionError = e.message
            Log.e(TAG, "EncryptedSharedPreferences gagal untuk $fileName (fail-closed): ${e.message}")
            throw SecurityException(
                "Secure storage unavailable — refusing to store secrets in plaintext. ${e.message}",
                e
            )
        }
    }

    /** Get encrypted SharedPreferences untuk AI settings. */
    fun getAIPrefs(context: Context): SharedPreferences =
        getEncryptedPrefs(context, AI_PREFS_NAME)

    /** Get encrypted SharedPreferences untuk SSH credentials. */
    fun getSshCredsPrefs(context: Context): SharedPreferences =
        getEncryptedPrefs(context, SSH_PREFS_NAME)

    /** Wave-2: Encrypted store for MCP server API keys. */
    fun getMcpKeysPrefs(context: Context): SharedPreferences =
        getEncryptedPrefs(context, MCP_KEYS_PREFS)

    /**
     * Migrasi satu kali: pindahkan apiKey dari plaintext prefs lama ke encrypted prefs.
     * Wave-2: if encryption fails, leave legacy key in place and log (do not wipe).
     */
    fun migrateAICredentials(context: Context) {
        val legacyPrefs = context.getSharedPreferences(AI_PREFS_LEGACY, Context.MODE_PRIVATE)
        val legacyApiKey = legacyPrefs.getString("apiKey", null)

        if (!legacyApiKey.isNullOrEmpty()) {
            try {
                val securePrefs = getAIPrefs(context)
                val existingSecureKey = securePrefs.getString("apiKey", null)

                if (existingSecureKey.isNullOrEmpty()) {
                    securePrefs.edit().putString("apiKey", legacyApiKey).apply()
                    Log.i(TAG, "Migrasi apiKey dari plaintext ke encrypted prefs sukses")
                }

                legacyPrefs.edit().remove("apiKey").apply()
                Log.i(TAG, "apiKey lama dihapus dari plaintext prefs")
            } catch (e: Exception) {
                Log.e(TAG, "Migrasi apiKey gagal (secure storage unavailable): ${e.message}")
            }
        }
    }

    fun storeSshCredential(context: Context, hostKeyId: String, password: String?, passphrase: String?) {
        val prefs = getSshCredsPrefs(context)
        val editor = prefs.edit()
        if (password != null) {
            editor.putString("${hostKeyId}_password", password)
        } else {
            editor.remove("${hostKeyId}_password")
        }
        if (passphrase != null) {
            editor.putString("${hostKeyId}_passphrase", passphrase)
        } else {
            editor.remove("${hostKeyId}_passphrase")
        }
        editor.apply()
    }

    fun getSshPassword(context: Context, hostKeyId: String): String? =
        getSshCredsPrefs(context).getString("${hostKeyId}_password", null)

    fun getSshPassphrase(context: Context, hostKeyId: String): String? =
        getSshCredsPrefs(context).getString("${hostKeyId}_passphrase", null)

    fun removeSshCredentials(context: Context, hostKeyId: String) {
        getSshCredsPrefs(context).edit()
            .remove("${hostKeyId}_password")
            .remove("${hostKeyId}_passphrase")
            .apply()
    }

    fun storeMcpApiKey(context: Context, serverName: String, apiKey: String?) {
        val prefs = getMcpKeysPrefs(context)
        if (apiKey.isNullOrEmpty()) {
            prefs.edit().remove("mcp_$serverName").apply()
        } else {
            prefs.edit().putString("mcp_$serverName", apiKey).apply()
        }
    }

    fun getMcpApiKey(context: Context, serverName: String): String? =
        try {
            getMcpKeysPrefs(context).getString("mcp_$serverName", null)
        } catch (_: Exception) {
            null
        }

    fun removeMcpApiKey(context: Context, serverName: String) {
        try {
            getMcpKeysPrefs(context).edit().remove("mcp_$serverName").apply()
        } catch (_: Exception) { /* ignore */ }
    }
}
