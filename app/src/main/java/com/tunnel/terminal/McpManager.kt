package com.tunnel.terminal

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
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
    val apiKey: String = "",             // optional auth
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
 * MCP adalah standard protocol dari Anthropic untuk AI tool interoperability.
 * Server MCP (filesystem, git, database, dll) expose tools via HTTP/SSE.
 * Tunnel Terminal bisa connect ke multiple MCP servers dan aggregate tools.
 *
 * Cara kerja:
 * 1. User tambah MCP server (URL + optional API key)
 * 2. Tunnel Terminal fetch /tools dari server
 * 3. AI system prompt di-update dengan daftar tools tersedia
 * 4. Saat AI call MCP tool, Tunnel Terminal forward ke server
 *
 * NOTE: Full MCP spec complex. Ini simplified HTTP-based implementation.
 */
class McpManager(private val context: Context) {
    private val tag = "McpManager"
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
                val transport = when (obj.optString("transport", "HTTP")) {
                    "SSE" -> McpTransport.SSE
                    else -> McpTransport.HTTP
                }
                _servers.add(McpServerConfig(
                    name = obj.getString("name"),
                    transport = transport,
                    url = obj.getString("url"),
                    apiKey = obj.optString("apiKey", ""),
                    enabled = obj.optBoolean("enabled", true)
                ))
            }
        } catch (_: Exception) {
            _servers.clear()
        }
    }

    private fun saveServers() {
        val arr = JSONArray()
        _servers.forEach { s ->
            arr.put(JSONObject()
                .put("name", s.name)
                .put("transport", s.transport.name)
                .put("url", s.url)
                .put("apiKey", s.apiKey)
                .put("enabled", s.enabled)
            )
        }
        prefs.edit().putString("servers", arr.toString()).apply()
    }

    /** Add new MCP server. */
    fun addServer(config: McpServerConfig): Boolean {
        if (_servers.any { it.name == config.name }) return false
        _servers.add(config)
        saveServers()
        return true
    }

    /** Remove MCP server. */
    fun removeServer(name: String): Boolean {
        val removed = _servers.removeAll { it.name == name }
        _discoveredTools.remove(name)
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
        val allTools = mutableListOf<McpTool>()
        for (server in _servers.filter { it.enabled }) {
            try {
                val tools = discoverTools(server)
                _discoveredTools[server.name] = tools
                allTools.addAll(tools)
            } catch (e: Exception) {
                Log.w(tag, "Failed to discover tools from ${server.name}: ${e.message}")
                _discoveredTools[server.name] = emptyList()
            }
        }
        allTools
    }

    /** Discover tools dari satu MCP server via GET /tools. */
    private suspend fun discoverTools(server: McpServerConfig): List<McpTool> = withContext(Dispatchers.IO) {
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
        if (_discoveredTools.isEmpty()) return ""
        val sb = StringBuilder("\n\n## MCP TOOLS (external servers)\n\n")
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
}
