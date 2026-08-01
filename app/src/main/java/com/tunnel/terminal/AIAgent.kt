package com.tunnel.terminal

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Model pesan chat.
 * Chat message model.
 *
 * Phase 18: Tambah isStreaming flag untuk indikasi pesan sedang di-stream.
 * Tambah conversationRole untuk multi-turn memory (role yang dikirim ke AI).
 * Phase 19: Tambah images field untuk AI image vision (base64-encoded).
 */
data class ChatMessage(
    val role: String,                  // "user" / "assistant" - untuk display
    val content: String,
    val isCommand: Boolean = false,
    val commands: List<String> = emptyList(),
    val isError: Boolean = false,
    val isStreaming: Boolean = false,  // true jika sedang di-stream (token-by-token)
    val conversationRole: String = role, // role untuk dikirim ke AI ("system"/"user"/"assistant")
    /** Phase 19: List of base64-encoded images attached to this message (untuk vision models). */
    val images: List<String> = emptyList()
)

/**
 * AIAgent - Client untuk OpenAI-compatible chat completions API.
 *
 * Phase 18 (Streaming + Multi-turn):
 * - askAIStreaming(): Returns Flow<String> yang emit token-by-token via SSE parsing
 * - Konsumsi SSE chunks: data: {json}\n\n ... data: [DONE]
 * - Multi-turn conversation: kirim list pesan sebelumnya, bukan hanya user prompt terakhir
 *
 * Phase 21: askAI() (non-streaming) dihapus — dead code, tidak pernah dipanggil.
 * Semua request AI sekarang via askAIStreaming().
 *
 * Mendukung semua provider OpenAI-compatible:
 * OpenAI, DeepSeek, Groq, OpenRouter, Gemini (OpenAI-compat), Anthropic (OpenAI-compat), Ollama.
 */
class AIAgent(
    /* Phase 60 fix (audit B-2): Referensi ke McpManager supaya tools MCP
     * bisa ditambahkan dinamis ke TOOL_SCHEMA per-request. Sebelumnya,
     * migrasi Phase 59 ke native tool-calling diam-diam menghilangkan
     * kemampuan AI memanggil tool MCP — kodenya masih ada di AiToolCall,
     * tapi AI tidak tahu mereka ada karena tidak terdaftar di TOOL_SCHEMA. */
    private var mcpManager: McpManager? = null
) {
    private val tag = "AIAgent"

    /** Phase 60 fix (audit B-2): Update McpManager reference (dipanggil
     * saat user connect/disconnect MCP server setelah AIAgent dibuat). */
    fun setMcpManager(mgr: McpManager?) { mcpManager = mgr }

    /**
     * Phase 59 fix (B-1): Tool schema untuk native API tool_calling.
     * Dikirim sebagai `tools` array di request body. Hanya dipakai kalau
     * settings.supportsToolCalling = true.
     *
     * Schema ini mendeskripsikan semua tool yang tersedia dengan parameter
     * yang benar (JSON Schema) — API provider akan menjamin format respons
     * sesuai schema, tidak perlu regex parsing.
     */
    private val TOOL_SCHEMA = org.json.JSONArray().apply {
        // read_file
        put(org.json.JSONObject().put("type", "function").put("function", org.json.JSONObject()
            .put("name", "read_file").put("description", "Baca isi file. Path relatif otomatis masuk workspace.")
            .put("parameters", org.json.JSONObject()
                .put("type", "object")
                .put("properties", org.json.JSONObject().put("path", org.json.JSONObject().put("type", "string").put("description", "Path file")))
                .put("required", org.json.JSONArray().put("path"))
            )
        ))
        // write_file
        put(org.json.JSONObject().put("type", "function").put("function", org.json.JSONObject()
            .put("name", "write_file").put("description", "Tulis file (full overwrite). Gunakan untuk file BARU.")
            .put("parameters", org.json.JSONObject()
                .put("type", "object")
                .put("properties", org.json.JSONObject()
                    .put("path", org.json.JSONObject().put("type", "string").put("description", "Path file"))
                    .put("content", org.json.JSONObject().put("type", "string").put("description", "Isi file lengkap"))
                )
                .put("required", org.json.JSONArray().put("path").put("content"))
            )
        ))
        // edit_file
        put(org.json.JSONObject().put("type", "function").put("function", org.json.JSONObject()
            .put("name", "edit_file").put("description", "Edit parsial file (cari & ganti). old_string harus match persis 1 kali.")
            .put("parameters", org.json.JSONObject()
                .put("type", "object")
                .put("properties", org.json.JSONObject()
                    .put("path", org.json.JSONObject().put("type", "string"))
                    .put("old_string", org.json.JSONObject().put("type", "string").put("description", "Teks yang akan diganti (harus match persis 1 kali)"))
                    .put("new_string", org.json.JSONObject().put("type", "string").put("description", "Teks pengganti"))
                )
                .put("required", org.json.JSONArray().put("path").put("old_string").put("new_string"))
            )
        ))
        // delete_file
        put(org.json.JSONObject().put("type", "function").put("function", org.json.JSONObject()
            .put("name", "delete_file").put("description", "Hapus file.")
            .put("parameters", org.json.JSONObject()
                .put("type", "object")
                .put("properties", org.json.JSONObject().put("path", org.json.JSONObject().put("type", "string")))
                .put("required", org.json.JSONArray().put("path"))
            )
        ))
        // list_files
        put(org.json.JSONObject().put("type", "function").put("function", org.json.JSONObject()
            .put("name", "list_files").put("description", "List direktori. Default: workspace root.")
            .put("parameters", org.json.JSONObject()
                .put("type", "object")
                .put("properties", org.json.JSONObject().put("dir", org.json.JSONObject().put("type", "string").put("description", "Direktori (opsional, default = workspace)")))
            )
        ))
        // run_command
        put(org.json.JSONObject().put("type", "function").put("function", org.json.JSONObject()
            .put("name", "run_command").put("description", "Jalankan command di terminal aktif.")
            .put("parameters", org.json.JSONObject()
                .put("type", "object")
                .put("properties", org.json.JSONObject().put("cmd", org.json.JSONObject().put("type", "string").put("description", "Command shell")))
                .put("required", org.json.JSONArray().put("cmd"))
            )
        ))
        // plan_task
        put(org.json.JSONObject().put("type", "function").put("function", org.json.JSONObject()
            .put("name", "plan_task").put("description", "Set rencana tugas di awal tugas kompleks.")
            .put("parameters", org.json.JSONObject()
                .put("type", "object")
                .put("properties", org.json.JSONObject().put("steps", org.json.JSONObject().put("type", "array").put("items", org.json.JSONObject().put("type", "string")).put("description", "Array langkah rencana")))
                .put("required", org.json.JSONArray().put("steps"))
            )
        ))
        // update_task_status
        put(org.json.JSONObject().put("type", "function").put("function", org.json.JSONObject()
            .put("name", "update_task_status").put("description", "Update status langkah rencana.")
            .put("parameters", org.json.JSONObject()
                .put("type", "object")
                .put("properties", org.json.JSONObject()
                    .put("step_id", org.json.JSONObject().put("type", "integer"))
                    .put("status", org.json.JSONObject().put("type", "string").put("enum", org.json.JSONArray().put("PENDING").put("IN_PROGRESS").put("DONE").put("FAILED")))
                )
                .put("required", org.json.JSONArray().put("step_id").put("status"))
            )
        ))
        // Phase 60 fix (audit B-2): search_files — sebelumnya hilang dari TOOL_SCHEMA,
        // kodenya tetap ada di AiToolCall.execute() tapi AI tidak tahu tool ini ada.
        put(org.json.JSONObject().put("type", "function").put("function", org.json.JSONObject()
            .put("name", "search_files").put("description", "Cari file berdasarkan pattern nama di workspace.")
            .put("parameters", org.json.JSONObject()
                .put("type", "object")
                .put("properties", org.json.JSONObject()
                    .put("pattern", org.json.JSONObject().put("type", "string").put("description", "Pattern regex nama file"))
                    .put("dir", org.json.JSONObject().put("type", "string").put("description", "Direktori search (opsional, default workspace root)"))
                )
                .put("required", org.json.JSONArray().put("pattern"))
            )
        ))
        // Phase 60 fix (audit B-2): get_terminal_output — sebelumnya hilang dari TOOL_SCHEMA.
        put(org.json.JSONObject().put("type", "function").put("function", org.json.JSONObject()
            .put("name", "get_terminal_output").put("description", "Ambil output terminal terkini.")
            .put("parameters", org.json.JSONObject()
                .put("type", "object")
                .put("properties", org.json.JSONObject())
            )
        ))
        // Wave-4: grep_content — content search inside files
        put(org.json.JSONObject().put("type", "function").put("function", org.json.JSONObject()
            .put("name", "grep_content").put("description", "Cari teks di dalam isi file (content grep), bukan nama file.")
            .put("parameters", org.json.JSONObject()
                .put("type", "object")
                .put("properties", org.json.JSONObject()
                    .put("pattern", org.json.JSONObject().put("type", "string").put("description", "Regex pattern di isi file"))
                    .put("dir", org.json.JSONObject().put("type", "string").put("description", "Direktori search (opsional)"))
                    .put("max_results", org.json.JSONObject().put("type", "integer").put("description", "Maks hasil (default 40)"))
                )
                .put("required", org.json.JSONArray().put("pattern"))
            )
        ))
    }

    /**
     * Kirim prompt ke AI dengan STREAMING via Server-Sent Events.
     * Send prompt with SSE streaming. Returns Flow that emits content deltas.
     *
     * Setiap token/chunk dipancarkan sebagai String. Flow selesai ketika
     * server kirim `data: [DONE]` atau stream closed.
     *
     * Emits content deltas as String. Flow completes on `[DONE]` or stream close.
     *
     * @param settings konfigurasi AI provider
     * @param conversation list pesan multi-turn
     * @param terminalContext output terminal terakhir (ANSI stripped)
     * @return Flow<String> yang emit token-by-token
     */
    fun askAIStreaming(
        settings: AISettings,
        conversation: List<ChatMessage>,
        terminalContext: String,
        sessionType: String = "local",
        environmentDescription: String = "",
        projectContext: String = "",
        taskPlan: String = "",
        /* Wave-25: User AI skills markdown block. */
        skillsContext: String = ""
    ): Flow<String> = callbackFlow {
        if (!isConfigured(settings)) {
            trySend(configErrorMessage(settings))
            channel.close()
            return@callbackFlow
        }

        val t0 = System.currentTimeMillis()
        var responseChars = 0
        var requestChars = 0
        var success = false
        var errorMsg: String? = null
        val apiStyle = if (settings.isAnthropicNative) "anthropic" else "openai"

        var connection: HttpURLConnection? = null
        try {
            connection = openConnection(settings, streaming = true)
            val requestBody = if (settings.isAnthropicNative) {
                buildAnthropicRequestBody(
                    settings, conversation, terminalContext, streaming = true,
                    sessionType = sessionType, environmentDescription = environmentDescription,
                    projectContext = projectContext, taskPlan = taskPlan,
                    skillsContext = skillsContext
                )
            } else {
                buildRequestBody(
                    settings, conversation, terminalContext, streaming = true,
                    sessionType = sessionType, environmentDescription = environmentDescription,
                    projectContext = projectContext, taskPlan = taskPlan,
                    skillsContext = skillsContext
                )
            }
            requestChars = requestBody.length
            writeRequest(connection, requestBody)

            val responseCode = connection.responseCode
            val inputStream = if (responseCode in 200..299) connection.inputStream else connection.errorStream

            if (responseCode !in 200..299) {
                val errBody = readAll(inputStream).take(800)
                Log.e(tag, "API error $responseCode (streaming): $errBody")
                errorMsg = "HTTP $responseCode"
                trySend(formatHttpError(responseCode, errBody))
                channel.close()
                return@callbackFlow
            }

            val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
            var line: String?
            val toolCallAccumulator = mutableMapOf<Int, org.json.JSONObject>()
            /* Wave-5: Anthropic tool_use partial JSON by content block index. */
            val anthropicToolBlocks = mutableMapOf<Int, Pair<String, StringBuilder>>()

            while (reader.readLine().also { line = it } != null) {
                if (!isActive) break

                val raw = line ?: continue
                if (raw.isEmpty() || raw.startsWith(":")) continue
                if (raw.startsWith("event:")) continue // Anthropic SSE event lines
                if (!raw.startsWith("data:")) continue

                val data = raw.removePrefix("data:").trim()
                if (data == "[DONE]") break

                try {
                    val json = JSONObject(data)

                    if (settings.isAnthropicNative) {
                        when (json.optString("type")) {
                            "content_block_delta" -> {
                                val delta = json.optJSONObject("delta")
                                when (delta?.optString("type")) {
                                    "text_delta" -> {
                                        val t = delta.optString("text", "")
                                        if (t.isNotEmpty()) {
                                            responseChars += t.length
                                            trySend(t)
                                        }
                                    }
                                    "input_json_delta" -> {
                                        val idx = json.optInt("index", 0)
                                        val partial = delta.optString("partial_json", "")
                                        anthropicToolBlocks[idx]?.second?.append(partial)
                                    }
                                }
                            }
                            "content_block_start" -> {
                                val block = json.optJSONObject("content_block")
                                if (block?.optString("type") == "tool_use") {
                                    val idx = json.optInt("index", 0)
                                    val name = block.optString("name", "")
                                    anthropicToolBlocks[idx] = name to StringBuilder()
                                }
                            }
                            "message_stop", "error" -> { /* end handled after loop */ }
                        }
                    } else {
                        val choices = json.optJSONArray("choices") ?: continue
                        if (choices.length() == 0) continue
                        val choiceObj = choices.getJSONObject(0)
                        val delta = choiceObj.optJSONObject("delta")

                        val toolCalls = delta?.optJSONArray("tool_calls")
                        if (toolCalls != null) {
                            for (i in 0 until toolCalls.length()) {
                                val tc = toolCalls.getJSONObject(i)
                                val idx = tc.optInt("index", 0)
                                val existing = toolCallAccumulator[idx] ?: org.json.JSONObject()
                                tc.optString("id", "").takeIf { it.isNotEmpty() }?.let { existing.put("id", it) }
                                tc.optJSONObject("function")?.let { fn ->
                                    fn.optString("name", "").takeIf { it.isNotEmpty() }?.let {
                                        existing.put("name", it)
                                    }
                                    val prevArgs = existing.optString("arguments", "")
                                    val newArgs = fn.optString("arguments", "")
                                    existing.put("arguments", prevArgs + newArgs)
                                }
                                toolCallAccumulator[idx] = existing
                            }
                        }

                        val content = delta?.optString("content") ?: ""
                        if (content.isNotEmpty()) {
                            responseChars += content.length
                            trySend(content)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Skip SSE line: ${e.message}")
                }
            }
            reader.close()

            /* Emit accumulated tool calls as <tool_call> tags (OpenAI + Anthropic). */
            if (settings.isAnthropicNative && anthropicToolBlocks.isNotEmpty()) {
                val sb = StringBuilder()
                for ((_, pair) in anthropicToolBlocks.toSortedMap()) {
                    val name = pair.first
                    val argsStr = pair.second.toString().ifBlank { "{}" }
                    try {
                        val argsJson = org.json.JSONObject(argsStr)
                        val toolCallJson = org.json.JSONObject().put("tool", name).put("args", argsJson)
                        sb.append("<tool_call>").append(toolCallJson.toString()).append("</tool_call>\n")
                    } catch (e: Exception) {
                        sb.append("<tool_call>{\"tool\":\"$name\",\"args\":$argsStr}</tool_call>\n")
                    }
                }
                if (sb.isNotEmpty()) {
                    responseChars += sb.length
                    trySend(sb.toString())
                    Log.i(tag, "Anthropic tool_use converted: ${anthropicToolBlocks.size}")
                }
            } else if (toolCallAccumulator.isNotEmpty()) {
                val sb = StringBuilder()
                for ((_, tc) in toolCallAccumulator.toSortedMap()) {
                    val name = tc.optString("name", "")
                    val argsStr = tc.optString("arguments", "{}")
                    try {
                        val argsJson = org.json.JSONObject(argsStr)
                        val toolCallJson = org.json.JSONObject()
                            .put("tool", name)
                            .put("args", argsJson)
                        sb.append("<tool_call>").append(toolCallJson.toString()).append("</tool_call>\n")
                    } catch (e: Exception) {
                        Log.w(tag, "Gagal parse accumulated tool_call args: ${e.message}")
                        sb.append("<tool_call>{\"tool\":\"$name\",\"args\":$argsStr}</tool_call>\n")
                    }
                }
                if (sb.isNotEmpty()) {
                    responseChars += sb.length
                    trySend(sb.toString())
                    Log.i(tag, "Native tool_calls converted to text-tag: ${toolCallAccumulator.size} calls")
                }
            }
            success = true
        } catch (e: java.net.SocketTimeoutException) {
            errorMsg = "timeout"
            trySend("Timeout (${settings.requestTimeoutMs}ms). Provider lambat atau unreachable.")
        } catch (e: java.net.UnknownHostException) {
            errorMsg = "dns"
            trySend("DNS gagal: ${e.message}. Cek koneksi internet atau Base URL.")
        } catch (e: javax.net.ssl.SSLException) {
            errorMsg = "ssl"
            trySend("SSL/TLS error: ${e.message}.")
        } catch (e: Exception) {
            errorMsg = e.javaClass.simpleName
            Log.e(tag, "Streaming error: ${e.javaClass.simpleName}: ${e.message}")
            trySend("Kesalahan streaming (${e.javaClass.simpleName}): ${e.message ?: "tidak diketahui"}")
        } finally {
            AiMetrics.record(
                AiMetrics.RequestStat(
                    timestampMs = System.currentTimeMillis(),
                    provider = settings.providerName,
                    model = settings.modelName,
                    latencyMs = System.currentTimeMillis() - t0,
                    requestChars = requestChars,
                    responseChars = responseChars,
                    apiStyle = apiStyle,
                    success = success,
                    error = errorMsg
                )
            )
            connection?.disconnect()
            channel.close()
        }

        awaitClose {
            try { connection?.disconnect() } catch (_: Exception) {}
        }
    }.flowOn(Dispatchers.IO)

    /* ─── Helpers ─── */

    private fun isConfigured(settings: AISettings): Boolean {
        /* v8.5.0 fix (C4): Pakai NetworkPolicy.isLocalOrPrivate untuk cek local.
         * Sebelumnya: substring match "localhost"/"127.0.0.1" — miss 10.0.2.2,
         * 192.168.x, ::1, 0.0.0.0. Sekarang: RFC1918 + loopback + emulator host. */
        val isLocal = try {
            NetworkPolicy.isLocalOrPrivate(URL(settings.baseUrl))
        } catch (_: Exception) { false }
        return settings.apiKey.isNotBlank() || isLocal
    }

    private fun configErrorMessage(settings: AISettings): String {
        return "API Key belum diset. Buka tab Settings (drawer kanan) untuk konfigurasi."
    }

    private fun openConnection(settings: AISettings, streaming: Boolean): HttpURLConnection {
        val base = settings.baseUrl.trimEnd('/')
        val apiUrl = if (settings.isAnthropicNative) {
            /* Wave-5: Anthropic Messages endpoint. */
            if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"
        } else {
            "$base/chat/completions"
        }
        val url = URL(apiUrl)
        enforceHttpsOrLocal(url)

        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (settings.isAnthropicNative) {
                setRequestProperty("x-api-key", settings.apiKey)
                setRequestProperty("anthropic-version", "2023-06-01")
            } else {
                setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            }
            setRequestProperty("HTTP-Referer", "https://github.com/NanoMindExplorer/tunnel-terminal")
            setRequestProperty("X-Title", "Tunnel Terminal")
            setRequestProperty("User-Agent", "TunnelTerminal/${com.tunnel.terminal.BuildConfig.VERSION_NAME} (Android)")
            if (streaming) {
                setRequestProperty("Accept", "text/event-stream")
            }
            connectTimeout = settings.requestTimeoutMs
            readTimeout = if (streaming) 120000 else settings.requestTimeoutMs
            doOutput = true
        }
    }

    /** Shared HTTPS check for OpenAI + Anthropic + ModelFetcher.
     *  v8.5.0 fix (C4): Delegate ke NetworkPolicy.enforceHttpsOrThrow. */
    internal fun enforceHttpsOrLocal(url: URL) {
        NetworkPolicy.enforceHttpsOrThrow(url)
    }

    private fun buildRequestBody(
        settings: AISettings,
        conversation: List<ChatMessage>,
        terminalContext: String,
        streaming: Boolean,
        sessionType: String = "local",
        environmentDescription: String = "",
        projectContext: String = "",
        taskPlan: String = "",
        skillsContext: String = ""
    ): String {
        /* Phase 40 fix (H2+H3): System prompt yang session-aware.
         * OLD BUGS:
         *  H2: Typo "Anda adalah .Tunnel Auto-Pilot." (harusnya 'Tunnel Auto-Pilot')
         *      + nested parens yang rancu + version outdated (v4.8 → v6.1.0)
         *  H3: System prompt bilang "Tidak ada apt" tapi user di tab Ubuntu BISA apt.
         *      AI tidak tahu tab mana yang aktif → salah informasi.
         * FIX: Build shellInfo section berdasarkan sessionType (local/ssh/ubuntu). */
        val shellInfo = when (sessionType) {
            "ubuntu" -> """
                Anda berjalan di Ubuntu 24.04 via proot (bash shell), cwd default /root.
                Command: apt-get, dpkg, git, python3, pip, curl, wget, gcc, make, dll (setelah di-install).
                sudo TIDAK perlu (proot fake-root -0).
                Install: DEBIAN_FRONTEND=noninteractive apt-get install -y <package>
                write_file path relatif → /root/ di guest (bukan workspace Android).
                Setelah write_file "x.py", jalankan: python3 x.py  (di /root).
                JANGAN pakai path /data/data/... di run_command.
                systemctl TIDAK ada — jalankan servis dengan & .
            """.trimIndent()
            "ssh" -> """
                Anda berjalan di remote SSH shell. Tanya user distribusi apa yang dipakai sebelum rekomendasi package manager (apt/yum/pacman/dnf).
                Default asumsi: bash shell, sudo tersedia jika user bilang root.
            """.trimIndent()
            else -> """
                Anda berjalan di /system/bin/sh Android (bukan bash), jadi hindari bash-ism.
                Command tersedia: ls, cd, cat, echo, mkdir, rm, cp, mv, pwd, ps, kill, df, du, head, tail, grep, sed, awk.
                Tidak ada: apt, yum, brew, pacman. Tidak ada sudo.
            """.trimIndent()
        }

        val systemPrompt = """
            Anda adalah 'Tunnel Auto-Pilot', agen AI otonom untuk terminal Android (Tunnel Terminal ${com.tunnel.terminal.BuildConfig.VERSION_NAME}).
            Tugas Anda adalah menyelesaikan tujuan pengguna dengan rangkaian perintah shell ATAU tool calls.

            $shellInfo

            ## NON-INTERACTIVE FLAGS (PENTING)
            Selalu tambahkan flag non-interaktif untuk command yang mungkin meminta konfirmasi:
            - apt: DEBIAN_FRONTEND=noninteractive apt-get install -y <package>
            - pip: pip install --no-input <package>
            - rm: rm -f (jangan tanya konfirmasi)
            - cp/mv: cp -f, mv -f (overwrite tanpa tanya)
            - Jangan pernah jalankan command yang mungkin menunggu input user (vim, nano, top, less)
              tanpa background/timer — gunakan echo + pipe atau redirect untuk non-interactive.
            - Untuk command yang butuh yes/no: echo "y" | <command> atau pakai flag -y/--yes.

            ## Phase 46 (Pilar 3) — INSTRUKSI KHUSUS UBUNTU (proot)
            Jika lingkungan aktif adalah Ubuntu (proot):
            - SELALU tambahkan flag -y untuk apt-get install/remove/upgrade
              (mis. "apt-get install -y nodejs"), supaya tidak menunggu konfirmasi yang tidak bisa kamu jawab.
            - Package manager yang benar adalah apt-get/dpkg. JANGAN sarankan "pkg install"
              (itu Termux, bukan environment ini) atau "yum/dnf" (itu RHEL-based, bukan Ubuntu).
            - systemctl/service TIDAK berfungsi (tidak ada systemd) — servis dijalankan sebagai
              proses biasa dengan & (mis. "nginx -g 'daemon off;' &").
            - Jika hasil command berstatus "kemungkinan menunggu input" (POSSIBLY_WAITING_FOR_INPUT),
              JANGAN kirim command baru menebak jawabannya — jelaskan ke user apa yang terlihat
              di output dan minta arahan.

            ## RESPONSE FORMAT

            Anda bisa memberikan MULTIPLE perintah dalam respons Anda. Format WAJIB setiap perintah:
            ```bash
            perintah_1
            ```
            ```bash
            perintah_2
            ```

            Jangan memberikan penjelasan panjang. Cukup berikan perintah-perintah yang perlu dijalankan.

            ## AI TOOL CALLS (Phase 22)

            ${AiToolCall.SYSTEM_PROMPT_TOOLS}
            ${mcpManager?.generateSystemPromptSection() ?: ""}

            ## PRIORITAS: FILE/PROGRAM YANG DIMINTA USER vs CODE BLOCK PENJELASAN

            Kalau user meminta kamu MEMBUAT, MENULIS, atau MEMBANGUN sebuah file/program
            (mis. "buat program X", "tulis file Y"), kamu WAJIB pakai tool write_file
            untuk MENYIMPAN kode itu — JANGAN PERNAH menampilkan isi lengkap file yang
            diminta sebagai code block di respons chat, walau kodenya pendek.
            Chat cuma untuk konfirmasi singkat setelah tool call berhasil, mis:
            "File demo.py sudah dibuat di workspace, siap dijalankan."

            Instruksi "code blocks untuk multi-line code" di bagian MARKDOWN di
            bawah ini HANYA berlaku untuk cuplikan kode PENDEK dalam penjelasan
            (mis. contoh sintaks, potongan untuk didiskusikan) — BUKAN untuk file
            lengkap yang diminta user sebagai deliverable.

            ## PENYIMPANAN FILE (Wave-19/23)

            Bergantung TAB AKTIF:
            - Local: path relatif → workspace Android.
            - Ubuntu: path relatif → /root di guest proot (file langsung terlihat bash Ubuntu).
            - Download perangkat: setup-storage + path absolut SAF / prefix storage/.

            Saat Ubuntu aktif, SELALU tulis file kerja ke path relatif atau /root/…
            lalu jalankan dengan run_command di tab Ubuntu yang sama.

            ## MARKDOWN

            Gunakan markdown untuk formatting response:
            - **bold** untuk emphasis
            - `inline code` untuk command/file names
            - ```code blocks``` untuk multi-line code
            - ## headers untuk struktur
            - - bullet lists
            - > blockquotes untuk notes

            Response Anda akan dirender sebagai markdown di UI.
        """.trimIndent()

        val messagesArray = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))

            /* Multi-turn: kirim seluruh conversation history (max 20 pesan terakhir
             * untuk hindari token bloat). Filter pesan error & streaming-in-progress.
             * Multi-turn: send conversation history (cap 20 to avoid token bloat). */
            val history = conversation
                .filter { !it.isError && !it.isStreaming && !it.isCommand && (it.content.isNotBlank() || it.images.isNotEmpty()) }
                .takeLast(20)
            history.forEach { msg ->
                /* Phase 19: Multi-modal message format untuk vision.
                 * Jika ada images, format content sebagai array of parts (text + image_url).
                 * Format: {"role":"user","content":[{"type":"text","text":"..."},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}]}
                 *
                 * Multi-modal message format for vision models. */
                if (msg.images.isNotEmpty()) {
                    val contentArray = JSONArray()
                    /* Text part (wajib ada meski kosong). */
                    contentArray.put(JSONObject().put("type", "text").put("text", msg.content))
                    /* Image parts. */
                    msg.images.forEach { base64 ->
                        contentArray.put(JSONObject()
                            .put("type", "image_url")
                            .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$base64"))
                        )
                    }
                    put(JSONObject().put("role", msg.conversationRole).put("content", contentArray))
                } else {
                    /* Text-only message (format lama). */
                    put(JSONObject().put("role", msg.conversationRole).put("content", msg.content))
                }
            }

            /* Tambahkan terminal context sebagai pesan system tambahan jika ada.
             * Phase 46 (Pilar 2): Sertakan environmentDescription di baris paling atas
             * sebelum output terminal, supaya AI tahu persis lingkungan aktif.
             * Phase 50 fix (B-5): Sertakan projectContext (git state, manifests, file tree)
             * supaya AI tahu struktur project tanpa user perlu @mention manual.
             * Append terminal context as additional system message if present. */
            val cleanContext = stripAnsi(terminalContext).take(1500)
            if (cleanContext.isNotBlank() || environmentDescription.isNotBlank() ||
                projectContext.isNotBlank() || taskPlan.isNotBlank() || skillsContext.isNotBlank()
            ) {
                val contextParts = mutableListOf<String>()
                if (environmentDescription.isNotBlank()) {
                    contextParts.add("Lingkungan terminal aktif: $environmentDescription")
                }
                if (skillsContext.isNotBlank()) {
                    contextParts.add(skillsContext)
                }
                if (projectContext.isNotBlank()) {
                    contextParts.add(projectContext)
                }
                if (taskPlan.isNotBlank()) {
                    contextParts.add(taskPlan)
                }
                if (cleanContext.isNotBlank()) {
                    contextParts.add("Konteks Terminal saat ini:\n$cleanContext")
                }
                put(JSONObject().put("role", "system").put("content", contextParts.joinToString("\n\n")))
            }
        }

        val requestBody = JSONObject()
            .put("model", settings.modelName)
            .put("messages", messagesArray)
            .put("temperature", settings.temperature)
            .put("max_tokens", settings.maxTokens)
            .put("stream", streaming)

        /* Phase 59 fix (B-1): Tambah tools array + tool_choice kalau provider
         * mendukung native tool_calling. API akan menjamin format respons sesuai
         * schema — tidak perlu regex parsing, tidak ada risiko escaping error.
         *
         * Phase 60 fix (audit B-2): Build tool schema dinamis — gabungkan
         * TOOL_SCHEMA statis dengan tool MCP yang aktif (kalau ada MCP server
         * connected). Sebelumnya, MCP tools tidak terdaftar di schema → AI
         * tidak pernah memanggilnya walau kodenya tetap ada di AiToolCall. */
        if (settings.supportsToolCalling) {
            val fullToolSchema = if (mcpManager != null) {
                /* Copy TOOL_SCHEMA statis, lalu append tool MCP aktif. */
                val dynamic = org.json.JSONArray(TOOL_SCHEMA.toString())
                try {
                    val mcpTools = mcpManager!!.discoveredTools
                    if (mcpTools.isNotEmpty()) {
                        mcpTools.forEach { (serverName, tools) ->
                            tools.forEach { mcpTool ->
                                try {
                                    /* Phase 60 fix (audit Bug #4): Validate MCP tool schema. */
                                    val paramsSchema = org.json.JSONObject(
                                        mcpTool.inputSchema.ifBlank { "{}" }
                                    )
                                    if (!paramsSchema.has("type")) {
                                        paramsSchema.put("type", "object")
                                    }
                                    if (paramsSchema.optString("type") == "object" &&
                                        !paramsSchema.has("properties")) {
                                        paramsSchema.put("properties", org.json.JSONObject())
                                    }
                                    dynamic.put(org.json.JSONObject()
                                        .put("type", "function")
                                        .put("function", org.json.JSONObject()
                                            .put("name", "mcp.$serverName.${mcpTool.name}")
                                            .put("description", mcpTool.description.ifBlank { "MCP tool: ${mcpTool.name}" })
                                            .put("parameters", paramsSchema)
                                        )
                                    )
                                } catch (e: Exception) {
                                    Log.w(tag, "Skip MCP tool $serverName/${mcpTool.name}: ${e.message}")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Gagal aggregate MCP tools: ${e.message}")
                }
                dynamic
            } else {
                TOOL_SCHEMA
            }
            requestBody.put("tools", fullToolSchema)
            requestBody.put("tool_choice", "auto")
        }

        return requestBody.toString()
    }

    /**
     * Wave-5: Anthropic Messages API body.
     * system is a top-level string; messages only user/assistant; tools use input_schema.
     */
    private fun buildAnthropicRequestBody(
        settings: AISettings,
        conversation: List<ChatMessage>,
        terminalContext: String,
        streaming: Boolean,
        sessionType: String,
        environmentDescription: String,
        projectContext: String,
        taskPlan: String,
        skillsContext: String = ""
    ): String {
        /* Reuse system prompt construction via OpenAI builder's shell section logic. */
        val openAiBody = JSONObject(buildRequestBody(
            settings, conversation, terminalContext, streaming,
            sessionType, environmentDescription, projectContext, taskPlan, skillsContext
        ))
        val messagesIn = openAiBody.getJSONArray("messages")
        val systemParts = mutableListOf<String>()
        val anthropicMessages = JSONArray()

        for (i in 0 until messagesIn.length()) {
            val m = messagesIn.getJSONObject(i)
            val role = m.optString("role")
            when (role) {
                "system" -> {
                    val c = m.opt("content")
                    when (c) {
                        is String -> if (c.isNotBlank()) systemParts.add(c)
                        is JSONArray -> {
                            /* Multimodal system rare — flatten text parts. */
                            for (j in 0 until c.length()) {
                                val part = c.optJSONObject(j)
                                if (part?.optString("type") == "text") {
                                    systemParts.add(part.optString("text"))
                                }
                            }
                        }
                    }
                }
                "user", "assistant" -> {
                    val content = m.opt("content")
                    val out = JSONObject().put("role", role)
                    when (content) {
                        is String -> out.put("content", content)
                        is JSONArray -> {
                            /* Convert OpenAI image parts to Anthropic image blocks. */
                            val blocks = JSONArray()
                            for (j in 0 until content.length()) {
                                val part = content.getJSONObject(j)
                                when (part.optString("type")) {
                                    "text" -> blocks.put(
                                        JSONObject().put("type", "text").put("text", part.optString("text"))
                                    )
                                    "image_url" -> {
                                        val url = part.optJSONObject("image_url")?.optString("url") ?: ""
                                        val b64 = url.substringAfter("base64,", "")
                                        if (b64.isNotBlank()) {
                                            blocks.put(
                                                JSONObject()
                                                    .put("type", "image")
                                                    .put("source", JSONObject()
                                                        .put("type", "base64")
                                                        .put("media_type", "image/jpeg")
                                                        .put("data", b64)
                                                    )
                                            )
                                        }
                                    }
                                }
                            }
                            out.put("content", blocks)
                        }
                        else -> out.put("content", content?.toString() ?: "")
                    }
                    anthropicMessages.put(out)
                }
            }
        }

        /* Anthropic requires at least one message and alternating roles — ensure first is user. */
        if (anthropicMessages.length() == 0) {
            anthropicMessages.put(JSONObject().put("role", "user").put("content", "(empty)"))
        }

        val body = JSONObject()
            .put("model", settings.modelName)
            .put("max_tokens", settings.maxTokens)
            .put("temperature", settings.temperature)
            .put("stream", streaming)
            .put("messages", anthropicMessages)
        if (systemParts.isNotEmpty()) {
            body.put("system", systemParts.joinToString("\n\n"))
        }

        if (settings.supportsToolCalling) {
            val tools = JSONArray()
            for (i in 0 until TOOL_SCHEMA.length()) {
                val item = TOOL_SCHEMA.getJSONObject(i)
                val fn = item.optJSONObject("function") ?: continue
                tools.put(
                    JSONObject()
                        .put("name", fn.optString("name"))
                        .put("description", fn.optString("description"))
                        .put("input_schema", fn.optJSONObject("parameters") ?: JSONObject().put("type", "object"))
                )
            }
            /* MCP tools */
            try {
                mcpManager?.discoveredTools?.forEach { (serverName, mcpTools) ->
                    mcpTools.forEach { mcpTool ->
                        val params = try {
                            JSONObject(mcpTool.inputSchema.ifBlank { "{}" })
                        } catch (_: Exception) {
                            JSONObject().put("type", "object")
                        }
                        if (!params.has("type")) params.put("type", "object")
                        tools.put(
                            JSONObject()
                                .put("name", "mcp.$serverName.${mcpTool.name}")
                                .put("description", mcpTool.description.ifBlank { mcpTool.name })
                                .put("input_schema", params)
                        )
                    }
                }
            } catch (_: Exception) {}
            if (tools.length() > 0) body.put("tools", tools)
        }

        return body.toString()
    }

    private fun writeRequest(connection: HttpURLConnection, body: String) {
        val writer = OutputStreamWriter(connection.outputStream, Charsets.UTF_8)
        writer.write(body)
        writer.flush()
        writer.close()
    }

    private fun readAll(inputStream: java.io.InputStream): String {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        val sb = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            sb.append(line).append('\n')
        }
        reader.close()
        return sb.toString()
    }

    /** Strip ANSI escape codes dari string. Strip ANSI escape codes. */
    private fun stripAnsi(text: String): String {
        if (text.isEmpty()) return text
        val regex = Regex("\u001B\\[[;?0-9]*[A-Za-z]|\u001B\\][^\u0007]*\u0007|\u001B[=>78cHM()*+]")
        return regex.replace(text, "").replace(Regex("\u0007"), "")
    }

    private fun formatHttpError(code: Int, body: String): String {
        return when (code) {
            401 -> "API Key tidak valid atau kadaluarsa (HTTP 401). Periksa Settings."
            403 -> "Akses ditolak (HTTP 403). API Key mungkin tidak punya hak akses ke model ini."
            404 -> "Endpoint tidak ditemukan (HTTP 404). Cek Base URL dan Model Name."
            429 -> "Rate limit tercapai (HTTP 429). Tunggu sebentar lalu coba lagi."
            500, 502, 503 -> "Server provider bermasalah (HTTP $code). Coba lagi nanti."
            else -> "Error API (HTTP $code): ${body.take(300)}"
        }
    }
}
