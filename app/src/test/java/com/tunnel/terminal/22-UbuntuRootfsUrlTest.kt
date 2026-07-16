package com.tunnel.terminal

import org.junit.Assert.*
import org.junit.Test

/**
 * Wave-22: Ubuntu rootfs URL list + SHA256SUMS parse rules (pure).
 */
class UbuntuRootfsUrlTest {

    @Test
    fun `arm64 urls prefer 24_04_4 and stay on cdimage`() {
        val urls = ProotBootstrap.ROOTFS_URLS_ARM64
        assertTrue(urls.isNotEmpty())
        assertTrue(urls.first().contains("24.04.4"))
        assertTrue(urls.all { it.startsWith("https://cdimage.ubuntu.com/") })
        assertTrue(urls.all { it.endsWith("-arm64.tar.gz") })
        /* At least one path-alias under /24.04/release/ for resilience. */
        assertTrue(urls.any { it.contains("/24.04/release/") })
    }

    @Test
    fun `amd64 urls mirror arm64 structure`() {
        val arm = ProotBootstrap.ROOTFS_URLS_ARM64.map { it.replace("arm64", "amd64") }
        assertEquals(arm, ProotBootstrap.ROOTFS_URLS_AMD64)
    }

    @Test
    fun `min tarball size rejects html error pages`() {
        assertTrue(ProotBootstrap.MIN_TARBALL_BYTES >= 15L * 1024 * 1024)
        assertTrue(ProotBootstrap.MIN_FREE_BYTES >= 800L * 1024 * 1024)
    }

    @Test
    fun `sha256sums star-filename format parses`() {
        // Official format: <hex> *filename
        val line = "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2 *ubuntu-base-24.04.4-base-arm64.tar.gz"
        val parts = line.trim().split(Regex("\\s+"), limit = 2)
        val hex = parts[0]
        val name = parts[1].removePrefix("*").trim()
        assertEquals(64, hex.length)
        assertEquals("ubuntu-base-24.04.4-base-arm64.tar.gz", name)
        assertTrue(hex.matches(Regex("[0-9a-fA-F]{64}")))
    }

    @Test
    fun `gzip magic bytes check`() {
        val gzip = byteArrayOf(0x1f.toByte(), 0x8b.toByte(), 0x08)
        assertEquals(0x1f, gzip[0].toInt() and 0xff)
        assertEquals(0x8b, gzip[1].toInt() and 0xff)
        val html = "<!DOCTYPE html>".toByteArray()
        assertFalse(html[0] == 0x1f.toByte() && html[1] == 0x8b.toByte())
    }
}
