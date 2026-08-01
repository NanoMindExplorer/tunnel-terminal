package com.tunnel.terminal

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * McpServerConfig - Konfigurasi satu MCP server.
 * MCP server menyediakan tools yang bisa AI panggil.
 *
 * MCP = Model Context Protocol (Anthropic standard).
 */
data class McpServerConfig(
    val name: String,
    val transport: McpTransport,        // SSE atau HTTP
    val url: String,                     // server URL
    val apiKey: String = "",             // optional auth (loaded from SecureStorage)
    val enabled: Boolean = true
)

enum class McpTransport { SSE, HTTP }

/**
 * McpTool - Satu tool yang di-expose oleh MCP server.
 */
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: String              // JSON schema string
)

/**
 * McpManager - Manage MCP server connections.
 *
 * Phase 23: MCP (Model Context Protocol) support.
 * Wave-2: Encrypt API keys; harden URL allowlist; fail closed on http spoof.
 */
class McpManager(private val context: Context) {
    private val tag = "McpManager"
    /** Non-secret server metadata only (no API keys). */
    private val prefs = context.getSharedPreferences("TunnelMcp", Context.MODE_PRIVATE)

    private val _servers = mutableStateListOf<McpServerConfig>()
    val servers: List<McpServerConfig> get() = _servers.toList()

    private val _discoveredTools = mutableStateMapOf<String, List<McpTool>>()
    val discoveredTools: Map<String, List<McpTool>> get() = _discoveredTools.toMap()

    init {
        loadServers()
    }

    private fun loadServers() {
        val json = prefs.getString("servers", "[]") ?: "[]"
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.getString("name")
                val transport = when (obj.optString("transport", "HTTP")) {
                    "SSE" -> McpTransport.SSE
                    else -> McpTransport.HTTP
                }
                /* Wave-2: API key from encrypted store; migrate legacy plaintext once. */
                var apiKey = SecureStorage.getMcpApiKey(context, name) ?: ""
                val legacyKey = obj.optString("apiKey", "")
                if (apiKey.isEmpty() && legacyKey.isNotEmpty()) {
                    try {
                        SecureStorage.storeMcpApiKey(context, name, legacyKey)
                        apiKey = legacyKey
                    } catch (e: Exception) {
                        Log.w(tag, "Could not migrate MCP key for $name: ${e.message}")
                        apiKey = legacyKey // best-effort in-memory only
                    }
                }
                _servers.add(McpServerConfig(
                    name = name,
                    transport = transport,
                    url = obj.getString("url"),
                    apiKey = apiKey,
                    enabled = obj.optBoolean("enabled", true)
                ))
            }
            /* Rewrite metadata without embedded keys. */
            saveServers()
        } catch (_: Exception) {
            _servers.clear()
        }
    }

    private fun saveServers() {
        val arr = JSONArray()
        _servers.forEach { s ->
            /* Wave-2: never persist apiKey in plaintext TunnelMcp prefs. */
            arr.put(JSONObject()
                .put("name", s.name)
                .put("transport", s.transport.name)
                .put("url", s.url)
                .put("enabled", s.enabled)
            )
            try {
                SecureStorage.storeMcpApiKey(context, s.name, s.apiKey.ifBlank { null })
            } catch (e: Exception) {
                Log.e(tag, "Failed to store MCP key for ${s.name}: ${e.message}")
            }
        }
        prefs.edit().putString("servers", arr.toString()).apply()
    }

    /**
     * Wave-2: Strict URL allowlist.
     * - https always ok for public hosts (except link-local metadata)
     * - http only for true loopback hosts (parsed host, not substring)
     */
    fun isAllowedMcpUrl(urlStr: String): Boolean {
        /* v8.5.0 fix (C4): Delegate ke NetworkPolicy.isAllowedMcpUrl.
         * Sebelumnya: hardcoded host list yang miss RFC1918 private ranges.
         * Sekarang: NetworkPolicy handle loopback + RFC1918 + cloud metadata block. */
        return NetworkPolicy.isAllowedMcpUrl(urlStr)
    }

    /** Add new MCP server. */
    fun addServer(config: McpServerConfig): Boolean {
        if (_servers.any { it.name == config.name }) return false
        if (!isAllowedMcpUrl(config.url)) {
            Log.w(tag, "Wave-2: Rejecting MCP server URL: ${config.url}")
            return false
        }
        _servers.add(config)
        saveServers()
        return true
    }

    /** Remove MCP server. */
    fun removeServer(name: String): Boolean {
        val removed = _servers.removeAll { it.name == name }
        _discoveredTools.remove(name)
        SecureStorage.removeMcpApiKey(context, name)
        if (removed) saveServers()
        return removed
    }

    /** Toggle server enabled. */
    fun toggleServer(name: String) {
        val idx = _servers.indexOfFirst { it.name == name }
        if (idx >= 0) {
            _servers[idx] = _servers[idx].copy(enabled = !_servers[idx].enabled)
            saveServers()
        }
    }

    /**
     * Discover tools dari semua enabled MCP servers.
     * Returns combined list of all tools.
     */
    suspend fun discoverAllTools(): List<McpTool> = withContext(Dispatchers.IO) {
        /* v9.3.0 fix (H-4): Parallel discovery — semua server di-discover
         * concurrently via async/awaitAll. Sebelumnya: sequential loop,
         * 5 servers × 20s worst case = 100s. Sekarang: max 20s. */
        val enabledServers = _servers.filter { it.enabled }
        val results = enabledServers.map { server ->
            async {
                try {
                    val pingErr = pingServer(server)
                    if (pingErr != null) {
                        Log.w(tag, "MCP ${server.name} ping failed: $pingErr")
                        _discoveredTools[server.name] = emptyList()
                        return@async emptyList<McpTool>()
                    }
                    val tools = discoverTools(server)
                    _discoveredTools[server.name] = tools
                    Log.i(tag, "MCP ${server.name}: discovered ${tools.size} tools")
                    tools
                } catch (e: Exception) {
                    Log.w(tag, "Failed to discover tools from ${server.name}: ${e.message}")
                    _discoveredTools[server.name] = emptyList()
                    emptyList()
                }
            }
        }.awaitAll()
        results.flatten()
    }

    /** Discover tools dari satu MCP server via GET /tools. */
    private suspend fun discoverTools(server: McpServerConfig): List<McpTool> = withContext(Dispatchers.IO) {
        if (!isAllowedMcpUrl(server.url)) {
            Log.w(tag, "Skip discover — URL not allowed: ${server.url}")
            return@withContext emptyList()
        }
        val url = URL("${server.url.trimEnd('/')}/tools")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            if (server.apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${server.apiKey}")
            }
            connectTimeout = 10000
            readTimeout = 10000
        }

        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(tag, "MCP ${server.name} /tools returned $code")
                return@withContext emptyList()
            }
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val response = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) response.append(line)
            reader.close()

            val json = JSONObject(response.toString())
            val toolsArr = json.optJSONArray("tools") ?: return@withContext emptyList()
            val tools = mutableListOf<McpTool>()
            for (i in 0 until toolsArr.length()) {
                val t = toolsArr.getJSONObject(i)
                tools.add(McpTool(
                    name = t.optString("name", ""),
                    description = t.optString("description", ""),
                    inputSchema = t.optString("inputSchema", "{}")
                ))
            }
            tools
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Invoke MCP tool.
     * @param serverName MCP server name
     * @param toolName tool name
     * @param args tool arguments (JSON object string)
     * @return tool result
     */
    suspend fun invokeTool(serverName: String, toolName: String, args: String): String = withContext(Dispatchers.IO) {
        val server = _servers.find { it.name == serverName && it.enabled }
            ?: return@withContext "Error: MCP server '$serverName' not found or disabled"

        if (!isAllowedMcpUrl(server.url)) {
            return@withContext "Error: MCP server URL not allowed: ${server.url}"
        }

        val url = URL("${server.url.trimEnd('/')}/tools/$toolName/invoke")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (server.apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${server.apiKey}")
            }
            connectTimeout = 30000
            readTimeout = 30000
            doOutput = true
        }

        try {
            val body = JSONObject().put("arguments", JSONObject(args)).toString()
            val writer = OutputStreamWriter(conn.outputStream, Charsets.UTF_8)
            writer.write(body)
            writer.flush()
            writer.close()

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
            val response = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) response.append(line)
            reader.close()

            if (code !in 200..299) {
                return@withContext "MCP error ($code): ${response.toString().take(300)}"
            }

            val json = JSONObject(response.toString())
            json.optString("result", json.optString("content", response.toString()))
        } catch (e: Exception) {
            "MCP invoke error: ${e.message}"
        } finally {
            conn.disconnect()
        }
    }

    /** Generate system prompt section untuk AI — list all MCP tools. */
    fun generateSystemPromptSection(): String {
        if (_discoveredTools.isEmpty()) {
            return "\n\n## MCP TOOLS\n(no servers discovered — add HTTP MCP bridge URL in settings; " +
                "stdio MCP servers are not supported on Android yet)\n"
        }
        val sb = StringBuilder("\n\n## MCP TOOLS (HTTP bridge)\n\n")
        sb.append("Note: This app uses a simplified HTTP MCP bridge (GET /tools, POST /tools/{name}/invoke). ")
        sb.append("Full MCP stdio/JSON-RPC is not available on Android.\n\n")
        _discoveredTools.forEach { (serverName, tools) ->
            if (tools.isNotEmpty()) {
                sb.append("Server: $serverName\n")
                tools.forEach { tool ->
                    sb.append("- mcp.${serverName}.${tool.name}: ${tool.description}\n")
                }
                sb.append("\n")
            }
        }
        sb.append("To call MCP tool: <tool_call>{\"tool\":\"mcp.servername.toolname\",\"args\":{...}}</tool_call>\n")
        return sb.toString()
    }

    /**
     * Wave-6: Health-check a server URL before discover.
     * Returns null if OK, or an error message.
     */
    suspend fun pingServer(server: McpServerConfig): String? = withContext(Dispatchers.IO) {
        if (!isAllowedMcpUrl(server.url)) return@withContext "URL not allowed: ${server.url}"
        try {
            val url = URL(server.url.trimEnd('/'))
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                if (server.apiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${server.apiKey}")
                }
            }
            try {
                val code = conn.responseCode
                /* v9.1.0 fix (H-3): 4xx (401/403/404) bukan "healthy" — user perlu
                 * tahu kalau API key salah atau endpoint tidak ada.
                 * Sebelumnya: 200..499 = null (OK). Sekarang: 200..299 = null (OK). */
                if (code in 200..299) null else "HTTP $code from ${server.url} (check API key/URL)"
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            "Unreachable: ${e.message}"
        }
    }
}
