package com.tunnel.terminal

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Phase 51 fix (C-5): Unit tests untuk PermissionManager.
 *
 * Regression test untuk BUG-01: run_command/delete_file TIDAK boleh "Always Allow"
 * (security-critical — cegah prompt-injection eskalasi ke eksekusi command arbitrer).
 *
 * Plus test Phase 43 HIGH-05: permission scope per-session.
 */
class PermissionManagerTest {

    private lateinit var permissionManager: PermissionManager

    @Before
    fun setup() {
        // Use mock SharedPreferences (in-memory)
        val mockPrefs = MockSharedPreferences()
        permissionManager = PermissionManager(mockContext(mockPrefs))
        permissionManager.resetAll()
    }

    @Test
    fun `read-only tools are always approved`() {
        val call = AiToolCall("read_file", mapOf("path" to "test.txt"))
        assertTrue(permissionManager.isApproved(call))
        assertFalse(permissionManager.needsPrompt(call))
    }

    @Test
    fun `destructive tools need prompt by default`() {
        val call = AiToolCall("write_file", mapOf("path" to "test.txt", "content" to "hi"))
        assertFalse(permissionManager.isApproved(call))
        assertTrue(permissionManager.needsPrompt(call))
    }

    @Test
    fun `run_command always needs prompt even after always allow attempt`() {
        permissionManager.setPermission("run_command", PermissionManager.PermissionState.ALWAYS_ALLOW)
        val call = AiToolCall("run_command", mapOf("cmd" to "ls"))
        assertTrue("run_command should always need prompt", permissionManager.needsPrompt(call))
        assertFalse("run_command should never be pre-approved", permissionManager.isApproved(call))
    }

    @Test
    fun `delete_file always needs prompt even after always allow attempt`() {
        permissionManager.setPermission("delete_file", PermissionManager.PermissionState.ALWAYS_ALLOW)
        val call = AiToolCall("delete_file", mapOf("path" to "test.txt"))
        assertTrue("delete_file should always need prompt", permissionManager.needsPrompt(call))
        assertFalse("delete_file should never be pre-approved", permissionManager.isApproved(call))
    }

    @Test
    fun `write_file can be always allowed`() {
        permissionManager.setPermission("write_file", PermissionManager.PermissionState.ALWAYS_ALLOW)
        val call = AiToolCall("write_file", mapOf("path" to "test.txt", "content" to "hi"))
        assertTrue(permissionManager.isApproved(call))
        assertFalse(permissionManager.needsPrompt(call))
    }

    @Test
    fun `canAlwaysAllow returns false for run_command and delete_file`() {
        assertFalse(permissionManager.canAlwaysAllow("run_command"))
        assertFalse(permissionManager.canAlwaysAllow("delete_file"))
        assertTrue(permissionManager.canAlwaysAllow("write_file"))
    }

    @Test
    fun `always deny prevents execution`() {
        permissionManager.setPermission("write_file", PermissionManager.PermissionState.ALWAYS_DENY)
        val call = AiToolCall("write_file", mapOf("path" to "test.txt", "content" to "hi"))
        assertFalse(permissionManager.isApproved(call))
        assertFalse(permissionManager.needsPrompt(call))
    }

    @Test
    fun `never allow can be set for write_file`() {
        permissionManager.setPermission("write_file", PermissionManager.PermissionState.ALWAYS_DENY)
        assertEquals(
            PermissionManager.PermissionState.ALWAYS_DENY,
            permissionManager.getPermission("write_file")
        )
        // Session isolation: other session still ASK
        permissionManager.setActiveSession(99)
        assertEquals(
            PermissionManager.PermissionState.ASK,
            permissionManager.getPermission("write_file")
        )
    }

    @Test
    fun `always deny for run_command skips prompt and is not approved`() {
        permissionManager.setPermission("run_command", PermissionManager.PermissionState.ALWAYS_DENY)
        val call = AiToolCall("run_command", mapOf("cmd" to "ls"))
        assertFalse(permissionManager.isApproved(call))
        assertFalse(permissionManager.needsPrompt(call))
    }

    @Test
    fun `resetAll clears all permissions`() {
        permissionManager.setPermission("write_file", PermissionManager.PermissionState.ALWAYS_ALLOW)
        permissionManager.resetAll()
        val call = AiToolCall("write_file", mapOf("path" to "test.txt", "content" to "hi"))
        assertTrue(permissionManager.needsPrompt(call))
        assertFalse(permissionManager.isApproved(call))
    }

    @Test
    fun `permission scope is per-session (Phase 43 HIGH-05)`() {
        permissionManager.setActiveSession(1)
        permissionManager.setPermission("write_file", PermissionManager.PermissionState.ALWAYS_ALLOW)
        val call = AiToolCall("write_file", mapOf("path" to "test.txt", "content" to "hi"))
        assertTrue("Should be approved in session 1", permissionManager.isApproved(call))

        permissionManager.setActiveSession(2)
        assertFalse("Should NOT be approved in session 2", permissionManager.isApproved(call))
        assertTrue("Should need prompt in session 2", permissionManager.needsPrompt(call))
    }

    // --- Mock helpers ---

    private fun mockContext(prefs: SharedPreferences): Context {
        return object : android.content.ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        }
    }
}

/** Simple in-memory SharedPreferences for testing. */
class MockSharedPreferences : SharedPreferences {
    private val data = mutableMapOf<String, Any?>()

    override fun getAll(): Map<String, *> = data.toMap()
    override fun getString(key: String, defValue: String?): String? = (data[key] as? String) ?: defValue
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = (data[key] as? Set<String>) ?: defValues
    override fun getInt(key: String, defValue: Int): Int = (data[key] as? Int) ?: defValue
    override fun getLong(key: String, defValue: Long): Long = (data[key] as? Long) ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = (data[key] as? Float) ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
    override fun contains(key: String): Boolean = data.containsKey(key)

    override fun edit(): SharedPreferences.Editor = MockEditor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private inner class MockEditor : SharedPreferences.Editor {
        override fun putString(key: String, value: String?): SharedPreferences.Editor { data[key] = value; return this }
        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor { data[key] = values; return this }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor { data[key] = value; return this }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor { data[key] = value; return this }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor { data[key] = value; return this }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { data[key] = value; return this }
        override fun remove(key: String): SharedPreferences.Editor { data.remove(key); return this }
        override fun clear(): SharedPreferences.Editor { data.clear(); return this }
        override fun commit(): Boolean = true
        override fun apply() {}
    }
}
