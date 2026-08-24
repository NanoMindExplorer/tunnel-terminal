package com.tunnel.terminal

import org.junit.Assert.*
import org.junit.Test

class KeepAlivePolicyTest {

    @Test
    fun `detects keep-alive commands`() {
        assertTrue(KeepAlivePolicy.isKeepAliveCommand("keep-alive"))
        assertTrue(KeepAlivePolicy.isKeepAliveCommand("keep-alive battery"))
        assertTrue(KeepAlivePolicy.isKeepAliveCommand("izin-status"))
        assertTrue(KeepAlivePolicy.isKeepAliveCommand("permission-status"))
        assertFalse(KeepAlivePolicy.isKeepAliveCommand("ls"))
        assertFalse(KeepAlivePolicy.isKeepAliveCommand("storage-status"))
    }

    @Test
    fun `commandAction maps subcommands`() {
        assertEquals(KeepAlivePolicy.Action.STATUS, KeepAlivePolicy.commandAction("keep-alive"))
        assertEquals(KeepAlivePolicy.Action.STATUS, KeepAlivePolicy.commandAction("izin-status"))
        assertEquals(KeepAlivePolicy.Action.BATTERY, KeepAlivePolicy.commandAction("keep-alive battery"))
        assertEquals(KeepAlivePolicy.Action.BATTERY, KeepAlivePolicy.commandAction("keep-alive baterai"))
        assertEquals(KeepAlivePolicy.Action.OEM_AUTOSTART, KeepAlivePolicy.commandAction("keep-alive autostart"))
        assertEquals(KeepAlivePolicy.Action.NOTIFICATIONS, KeepAlivePolicy.commandAction("keep-alive notif"))
        assertEquals(KeepAlivePolicy.Action.BOOT_ON, KeepAlivePolicy.commandAction("keep-alive boot on"))
        assertEquals(KeepAlivePolicy.Action.BOOT_OFF, KeepAlivePolicy.commandAction("keep-alive boot off"))
    }

    @Test
    fun `oem list covers major killers`() {
        val vendors = KeepAlivePolicy.OEM_AUTOSTART_TARGETS.map { it.vendor.lowercase() }.joinToString(" ")
        assertTrue(vendors.contains("xiaomi"))
        assertTrue(vendors.contains("oppo"))
        assertTrue(vendors.contains("vivo"))
        assertTrue(vendors.contains("huawei"))
        assertTrue(vendors.contains("samsung"))
        assertTrue(KeepAlivePolicy.OEM_AUTOSTART_TARGETS.size >= 6)
    }
}
