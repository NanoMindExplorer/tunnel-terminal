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
class AIAgent {
    private val tag = "AIAgent"

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
        taskPlan: String = ""
    ): Flow<String> = callbackFlow {
        if (!isConfigured(settings)) {
            trySend(configErrorMessage(settings))
            channel.close()
            return@callbackFlow
        }

        var connection: HttpURLConnection? = null
        try {
            connection = openConnection(settings, streaming = true)
            val requestBody = buildRequestBody(
                settings, conversation, terminalContext, streaming = true,
                sessionType = sessionType, environmentDescription = environmentDescription,
                projectContext = projectContext, taskPlan = taskPlan
            )
            writeRequest(connection, requestBody)

            val responseCode = connection.responseCode
            val inputStream = if (responseCode in 200..299) connection.inputStream else connection.errorStream

            if (responseCode !in 200..299) {
                val errBody = readAll(inputStream).take(800)
                Log.e(tag, "API error $responseCode (streaming): $errBody")
                trySend(formatHttpError(responseCode, errBody))
                channel.close()
                return@callbackFlow
            }

            /* Parse SSE stream baris demi baris.
             * Parse SSE stream line by line. */
            val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
            var line: String?

            /* Phase 59 fix (B-1): Accumulator untuk native tool_calls deltas.
             * API mengirim tool_calls dalam chunks — index, id, name (first chunk),
             * arguments (accumulated across chunks). Kumpulkan semua, lalu di akhir
             * stream convert ke <tool_call> tags supaya compatible dengan parser lama. */
            val toolCallAccumulator = mutableMapOf<Int, org.json.JSONObject>()

            while (reader.readLine().also { line = it } != null) {
                if (!isActive) break  // consumer cancelled

                val raw = line ?: continue
                if (raw.isEmpty() || raw.startsWith(":")) continue  // SSE comment/heartbeat
                if (!raw.startsWith("data:")) continue

                val data = raw.removePrefix("data:").trim()
                if (data == "[DONE]") break

                try {
                    val json = JSONObject(data)
                    val choices = json.optJSONArray("choices") ?: continue
                    if (choices.length() == 0) continue
                    val choiceObj = choices.getJSONObject(0)
                    val delta = choiceObj.optJSONObject("delta")

                    // Phase 59: Cek native tool_calls di delta
                    val toolCalls = delta?.optJSONArray("tool_calls")
                    if (toolCalls != null) {
                        for (i in 0 until toolCalls.length()) {
                            val tc = toolCalls.getJSONObject(i)
                            val idx = tc.optInt("index", 0)
                            val existing = toolCallAccumulator[idx] ?: org.json.JSONObject()
                            // First chunk has id + function.name
                            tc.optString("id", "").takeIf { it.isNotEmpty() }?.let { existing.put("id", it) }
                            tc.optJSONObject("function")?.let { fn ->
                                fn.optString("name", "").takeIf { it.isNotEmpty() }?.let {
                                    existing.put("name", it)
                                }
                                // Arguments are accumulated across chunks
                                val prevArgs = existing.optString("arguments", "")
                                val newArgs = fn.optString("arguments", "")
                                existing.put("arguments", prevArgs + newArgs)
                            }
                            toolCallAccumulator[idx] = existing
                        }
                    }

                    // Text content (normal streaming)
                    val content = delta?.optString("content") ?: ""
                    if (content.isNotEmpty()) {
                        trySend(content)
                    }
                } catch (e: Exception) {
                    /* Partial JSON atau format lain - skip, jangan crash stream. */
                    Log.w(tag, "Skip SSE line: ${e.message}")
                }
            }
            reader.close()

            /* Phase 59 fix (B-1): Setelah stream selesai, kalau ada tool_calls
             * yang terakumulasi, convert ke <tool_call> tags dan emit sebagai text.
             * Ini "bridge" approach — native tool_calls di-convert ke format yang
             * sudah bisa di-parse oleh AiToolCall.parseFromResponse().
             *
             * Keuntungan: arguments sudah di-decode oleh API provider sebagai JSON
             * yang valid — TIDAK ADA risiko escaping/parsing error seperti text-tag. */
            if (toolCallAccumulator.isNotEmpty()) {
                val sb = StringBuilder()
                for ((_, tc) in toolCallAccumulator.toSortedMap()) {
                    val name = tc.optString("name", "")
                    val argsStr = tc.optString("arguments", "{}")
                    // Parse arguments JSON, lalu rebuild sebagai tool_call tag
                    try {
                        val argsJson = org.json.JSONObject(argsStr)
                        val toolCallJson = org.json.JSONObject()
                            .put("tool", name)
                            .put("args", argsJson)
                        sb.append("<tool_call>").append(toolCallJson.toString()).append("</tool_call>\n")
                    } catch (e: Exception) {
                        Log.w(tag, "Gagal parse accumulated tool_call args: ${e.message}")
                        // Fallback: emit as-is
                        sb.append("<tool_call>{\"tool\":\"$name\",\"args\":$argsStr}</tool_call>\n")
                    }
                }
                if (sb.isNotEmpty()) {
                    trySend(sb.toString())
                    Log.i(tag, "Native tool_calls converted to text-tag: ${toolCallAccumulator.size} calls")
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            trySend("Timeout (${settings.requestTimeoutMs}ms). Provider lambat atau unreachable.")
        } catch (e: java.net.UnknownHostException) {
            trySend("DNS gagal: ${e.message}. Cek koneksi internet atau Base URL.")
        } catch (e: javax.net.ssl.SSLException) {
            trySend("SSL/TLS error: ${e.message}.")
        } catch (e: Exception) {
            Log.e(tag, "Streaming error: ${e.javaClass.simpleName}: ${e.message}")
            trySend("Kesalahan streaming (${e.javaClass.simpleName}): ${e.message ?: "tidak diketahui"}")
        } finally {
            connection?.disconnect()
            channel.close()
        }

        awaitClose {
            /* Consumer cancelled - disconnect. */
            try { connection?.disconnect() } catch (_: Exception) {}
        }
    }.flowOn(Dispatchers.IO)

    /* ─── Helpers ─── */

    private fun isConfigured(settings: AISettings): Boolean {
        /* Local providers (Ollama, LM Studio) tidak butuh API key. */
        val isLocal = settings.baseUrl.contains("localhost") ||
                      settings.baseUrl.contains("127.0.0.1")
        return settings.apiKey.isNotBlank() || isLocal
    }

    private fun configErrorMessage(settings: AISettings): String {
        return "API Key belum diset. Buka tab Settings (drawer kanan) untuk konfigurasi."
    }

    private fun openConnection(settings: AISettings, streaming: Boolean): HttpURLConnection {
        val apiUrl = "${settings.baseUrl.trimEnd('/')}/chat/completions"
        val url = URL(apiUrl)
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            setRequestProperty("HTTP-Referer", "https://github.com/NanoMindExplorer/tunnel-terminal")
            setRequestProperty("X-Title", "Tunnel Terminal")
            setRequestProperty("User-Agent", "TunnelTerminal/4.8.0 (Android)")
            /* Accept: text/event-stream penting untuk SSE. */
            if (streaming) {
                setRequestProperty("Accept", "text/event-stream")
            }
            connectTimeout = settings.requestTimeoutMs
            /* Streaming: read timeout 0 = unlimited (chunked). */
            /* BUG-35 fix: Beri readTimeout terbatas (120s) untuk streaming, bukan 0 (unlimited).
             * Jika provider macet di tengah stream, koneksi akan timeout, bukan hang selamanya. */
            readTimeout = if (streaming) 120000 else settings.requestTimeoutMs
            doOutput = true
        }
    }

    private fun buildRequestBody(
        settings: AISettings,
        conversation: List<ChatMessage>,
        terminalContext: String,
        streaming: Boolean,
        sessionType: String = "local",
        environmentDescription: String = "",
        projectContext: String = "",
        taskPlan: String = ""
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
                Anda berjalan di Ubuntu 24.04 via proot (bash shell).
                Command tersedia: apt, apt-get, git, python3, nodejs, npm, vim, htop, curl, wget, build-essential, semua tool Ubuntu.
                sudo tidak perlu (proot sudah fake-root dengan -0).
                Untuk install package: DEBIAN_FRONTEND=noninteractive apt-get install -y <package>
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
            Anda adalah 'Tunnel Auto-Pilot', agen AI otonom untuk terminal Android (Tunnel Terminal v6.1.0).
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

            ## PENYIMPANAN FILE — SATU-SATUNYA JALUR YANG PASTI BERHASIL

            Workspace project (path relatif, otomatis masuk folder privat app) adalah
            SATU-SATUNYA lokasi yang bisa ditulis LANGSUNG — baik lewat tool write_file
            MAUPUN lewat command shell (cat, echo >, dst via run_command).

            ~/storage/shared (folder yang dibuka lewat setup-storage) TIDAK BISA ditulis
            langsung lewat shell command atau write_file, walau user sudah menjalankan
            setup-storage — ini batasan Android Scoped Storage, bukan soal izin yang kurang.
            Kalau user minta file disimpan ke folder pribadi mereka (Download/Documents),
            SELALU:
            1. Buat file itu dulu di workspace (path relatif biasa).
            2. Beri tahu user file sudah siap di workspace, dan sarankan pakai fitur
               Export/Share di File Explorer aplikasi untuk memindahkannya — JANGAN
               coba jalankan cp/cat langsung ke ~/storage/shared, itu akan selalu gagal.

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
            if (cleanContext.isNotBlank() || environmentDescription.isNotBlank() || projectContext.isNotBlank() || taskPlan.isNotBlank()) {
                val contextParts = mutableListOf<String>()
                if (environmentDescription.isNotBlank()) {
                    contextParts.add("Lingkungan terminal aktif: $environmentDescription")
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
         * schema — tidak perlu regex parsing, tidak ada risiko escaping error. */
        if (settings.supportsToolCalling) {
            requestBody.put("tools", TOOL_SCHEMA)
            requestBody.put("tool_choice", "auto")
        }

        return requestBody.toString()
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
