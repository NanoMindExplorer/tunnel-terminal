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
 * di SharedPreferences biasa. Siapapun dengan akses root/adb backup bisa baca
 * langsung dari shared_prefs/*.xml. allowBackup=false hanya mencegah eksfiltrasi
 * lewat backup resmi Android, bukan lewat root/malware/forensik device hilang.
 *
 * FIX: Pakai EncryptedSharedPreferences (AES256-GCM) untuk semua data sensitif:
 *  - API key AI provider (TunnelAIPrefs:apiKey)
 *  - SSH password & passphrase (TunnelSshCredentials)
 *
 * Migrasi: Saat startup pertama setelah update, baca prefs lama plaintext →
 * tulis ulang ke encrypted store → hapus key lama dari plaintext prefs.
 */
object SecureStorage {
    private const val TAG = "SecureStorage"

    /** Nama file encrypted prefs untuk AI settings. */
    private const val AI_PREFS_NAME = "TunnelAIPrefs_secure"
    /** Nama file plaintext prefs lama (untuk migrasi). */
    private const val AI_PREFS_LEGACY = "TunnelAIPrefs"
    /** Nama file encrypted prefs untuk SSH credentials. */
    private const val SSH_PREFS_NAME = "TunnelSshCredentials_secure"
    /** Nama file plaintext prefs lama untuk SSH host keys (tetap plaintext — bukan secret). */
    const val SSH_HOSTKEYS_PREFS = "TunnelSshHostKeys"

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
     * Buat instance EncryptedSharedPreferences. Fallback ke plaintext prefs
     * kalau Keystore tidak tersedia (mis. emulator lama, device custom ROM corrupt).
     */
    fun getEncryptedPrefs(context: Context, fileName: String): SharedPreferences {
        return try {
            EncryptedSharedPreferences.create(
                context,
                fileName,
                getMasterKey(context),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "EncryptedSharedPreferences gagal untuk $fileName, fallback ke plaintext: ${e.message}")
            context.getSharedPreferences("${fileName}_fallback", Context.MODE_PRIVATE)
        }
    }

    /** Get encrypted SharedPreferences untuk AI settings. */
    fun getAIPrefs(context: Context): SharedPreferences =
        getEncryptedPrefs(context, AI_PREFS_NAME)

    /** Get encrypted SharedPreferences untuk SSH credentials. */
    fun getSshCredsPrefs(context: Context): SharedPreferences =
        getEncryptedPrefs(context, SSH_PREFS_NAME)

    /**
     * Migrasi satu kali: pindahkan apiKey dari plaintext prefs lama ke encrypted prefs.
     * Dipanggil saat startup pertama setelah update dari versi < 6.2.0.
     *
     * Setelah migrasi sukses, hapus key lama dari plaintext prefs supaya tidak
     * bisa dibaca oleh attacker yang punya akses ke shared_prefs lama.
     */
    fun migrateAICredentials(context: Context) {
        val legacyPrefs = context.getSharedPreferences(AI_PREFS_LEGACY, Context.MODE_PRIVATE)
        val legacyApiKey = legacyPrefs.getString("apiKey", null)

        if (!legacyApiKey.isNullOrEmpty()) {
            val securePrefs = getAIPrefs(context)
            val existingSecureKey = securePrefs.getString("apiKey", null)

            if (existingSecureKey.isNullOrEmpty()) {
                // Belum pernah dimigrasi — pindahkan.
                securePrefs.edit().putString("apiKey", legacyApiKey).apply()
                Log.i(TAG, "Migrasi apiKey dari plaintext ke encrypted prefs sukses")
            }

            // Hapus key lama dari plaintext prefs (selalu, bahkan kalau sudah pernah dimigrasi).
            legacyPrefs.edit().remove("apiKey").apply()
            Log.i(TAG, "apiKey lama dihapus dari plaintext prefs")
        }
    }

    /**
     * Migrasi SSH credentials (password/passphrase) dari config yang disimpan
     * di plaintext (kalau ada). Saat ini SSH credentials ada di SshConnectionConfig
     * yang disimpan via WorkspaceManager — migrasi terjadi saat load.
     */
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
}
