package com.tunnel.terminal

import org.junit.Test
import org.junit.Assert.*
import java.net.URL

/**
 * v9.0.0: Unit tests for NetworkPolicy (v8.5.0 fix C4).
 * Tests: isLocalOrPrivate, isCloudMetadata, enforceHttpsOrThrow, isAllowedMcpUrl.
 */
class NetworkPolicyTest {

    // === isLocalOrPrivate ===

    @Test fun `localhost is local`() {
        assertTrue(NetworkPolicy.isLocalOrPrivate(URL("http://localhost:8080")))
    }

    @Test fun `127 dot 0 dot 0 dot 1 is local`() {
        assertTrue(NetworkPolicy.isLocalOrPrivate(URL("http://127.0.0.1:11434")))
    }

    @Test fun `127 dot 0 dot 0 dot 2 is local (loopback range)`() {
        assertTrue(NetworkPolicy.isLocalOrPrivate(URL("http://127.0.0.2:8080")))
    }

    @Test fun `10 dot 0 dot 2 dot 2 emulator host is local`() {
        assertTrue(NetworkPolicy.isLocalOrPrivate(URL("http://10.0.2.2:3000")))
    }

    @Test fun `IPv6 loopback is local`() {
        assertTrue(NetworkPolicy.isLocalOrPrivate(URL("http://[::1]:8080")))
    }

    @Test fun `0 dot 0 dot 0 dot 0 bind-all is local`() {
        assertTrue(NetworkPolicy.isLocalOrPrivate(URL("http://0.0.0.0:8080")))
    }

    @Test fun `RFC1918 10.x is local`() {
        assertTrue(NetworkPolicy.isLocalOrPrivate(URL("http://10.1.2.3:11434")))
    }

    @Test fun `RFC1918 192 dot 168 x is local`() {
        assertTrue(NetworkPolicy.isLocalOrPrivate(URL("http://192.168.1.10:11434")))
    }

    @Test fun `RFC1918 172 dot 16 x is local`() {
        assertTrue(NetworkPolicy.isLocalOrPrivate(URL("http://172.16.0.1:8080")))
    }

    @Test fun `RFC1918 172 dot 31 x is local`() {
        assertTrue(NetworkPolicy.isLocalOrPrivate(URL("http://172.31.255.255:8080")))
    }

    @Test fun `public IP is NOT local`() {
        assertFalse(NetworkPolicy.isLocalOrPrivate(URL("http://8.8.8.8:443")))
    }

    @Test fun `public domain is NOT local`() {
        assertFalse(NetworkPolicy.isLocalOrPrivate(URL("https://api.openai.com")))
    }

    // === isCloudMetadata ===

    @Test fun `AWS metadata endpoint is cloud metadata`() {
        assertTrue(NetworkPolicy.isCloudMetadata(URL("http://169.254.169.254/latest/meta-data/")))
    }

    @Test fun `GCP metadata endpoint is cloud metadata`() {
        assertTrue(NetworkPolicy.isCloudMetadata(URL("http://metadata.google.internal")))
    }

    @Test fun `localhost is NOT cloud metadata`() {
        assertFalse(NetworkPolicy.isCloudMetadata(URL("http://localhost:8080")))
    }

    // === enforceHttpsOrThrow ===

    @Test fun `HTTPS external URL does not throw`() {
        // Should not throw
        NetworkPolicy.enforceHttpsOrThrow(URL("https://api.openai.com/v1/chat/completions"))
    }

    @Test fun `HTTP localhost does not throw`() {
        NetworkPolicy.enforceHttpsOrThrow(URL("http://localhost:11434/v1"))
    }

    @Test fun `HTTP 192 dot 168 does not throw`() {
        NetworkPolicy.enforceHttpsOrThrow(URL("http://192.168.1.10:11434/v1"))
    }

    @Test fun `HTTP external URL throws`() {
        assertThrows(java.io.IOException::class.java) {
            NetworkPolicy.enforceHttpsOrThrow(URL("http://api.openai.com/v1/chat/completions"))
        }
    }

    @Test fun `HTTP public IP throws`() {
        assertThrows(java.io.IOException::class.java) {
            NetworkPolicy.enforceHttpsOrThrow(URL("http://8.8.8.8:443"))
        }
    }

    // === isAllowedMcpUrl ===

    @Test fun `HTTPS MCP URL is allowed`() {
        assertTrue(NetworkPolicy.isAllowedMcpUrl("https://mcp.example.com/tools"))
    }

    @Test fun `HTTP localhost MCP URL is allowed`() {
        assertTrue(NetworkPolicy.isAllowedMcpUrl("http://localhost:3000/tools"))
    }

    @Test fun `HTTP 192 dot 168 MCP URL is allowed`() {
        assertTrue(NetworkPolicy.isAllowedMcpUrl("http://192.168.1.5:3000/tools"))
    }

    @Test fun `HTTP external MCP URL is NOT allowed`() {
        assertFalse(NetworkPolicy.isAllowedMcpUrl("http://mcp.example.com/tools"))
    }

    @Test fun `AWS metadata MCP URL is NOT allowed`() {
        assertFalse(NetworkPolicy.isAllowedMcpUrl("https://169.254.169.254/latest/meta-data/"))
    }

    @Test fun `internal domain MCP URL is NOT allowed`() {
        assertFalse(NetworkPolicy.isAllowedMcpUrl("https://metadata.google.internal"))
    }

    @Test fun `invalid URL is NOT allowed`() {
        assertFalse(NetworkPolicy.isAllowedMcpUrl("not-a-url"))
    }

    @Test fun `ftp URL is NOT allowed`() {
        assertFalse(NetworkPolicy.isAllowedMcpUrl("ftp://localhost:21"))
    }
}
