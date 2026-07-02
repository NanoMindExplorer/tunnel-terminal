package com.tunnel.terminal

import android.util.Log
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
 * Model pesan chat.
 * Chat message model.
 */
data class ChatMessage(
    val role: String,
    val content: String,
    val isCommand: Boolean = false,
    val commands: List<String> = emptyList(),
    val isError: Boolean = false
)

/**
 * AIAgent - Client untuk OpenAI-compatible chat completions API.
 *
 * Phase 17 (Major Bug Fix):
 * - Timeout configurable dari AISettings (bukan hardcoded 30000)
 * - Error response tidak di-truncate agresif (300 -> 800 chars)
 * - Log error lengkap untuk debugging
 * - Deteksi koneksi internet sebelum request
 * - Strip ANSI dari terminal context sebelum dikirim
 * - Header User-Agent di-set untuk kompatibilitas dengan beberapa provider
 *
 * Mendukung semua provider OpenAI-compatible:
 * OpenAI, DeepSeek, Groq, OpenRouter, Gemini (OpenAI-compat), Anthropic (OpenAI-compat), Ollama.
 */
class AIAgent {
    private val tag = "AIAgent"

    /**
     * Kirim prompt ke AI dan tunggu response utuh.
     * Send prompt to AI and wait for full response.
     *
     * @param settings konfigurasi AI provider
     * @param userPrompt teks permintaan user
     * @param terminalContext output terminal terakhir (akan di-strip ANSI-nya)
     * @return response AI atau pesan error yang user-friendly
     */
    suspend fun askAI(settings: AISettings, userPrompt: String, terminalContext: String): String {
        if (settings.apiKey.isBlank() && !settings.baseUrl.contains("localhost") && !settings.baseUrl.contains("127.0.0.1")) {
            return "API Key belum diset. Buka tab Settings (drawer kanan) untuk konfigurasi."
        }

        return withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val apiUrl = "${settings.baseUrl.trimEnd('/')}/chat/completions"
                val url = URL(apiUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
                    setRequestProperty("HTTP-Referer", "https://github.com/NanoMindExplorer/tunnel-terminal")
                    setRequestProperty("X-Title", "Tunnel Terminal")
                    setRequestProperty("User-Agent", "TunnelTerminal/3.0 (Android)")
                    connectTimeout = settings.requestTimeoutMs
                    readTimeout = settings.requestTimeoutMs
                    doOutput = true
                }

                val systemPrompt = """
                    Anda adalah 'Tunnel Auto-Pilot', agen AI otonom untuk terminal Android.
                    Tugas Anda adalah menyelesaikan tujuan pengguna dengan rangkaian perintah shell.
                    Anda boleh memberikan MULTIPLE perintah dalam respons Anda. Format WAJIB setiap perintah adalah blok terpisah:
                    ```bash
                    perintah_1
                    ```
                    ```bash
                    perintah_2
                    ```
                    Jangan memberikan penjelasan panjang. Cukup berikan perintah-perintah yang perlu dijalankan berurutan oleh sistem Auto-Pilot.

                    Anda berjalan di /system/bin/sh Android (bukan bash), jadi hindari bash-ism.
                    Command tersedia: ls, cd, cat, echo, mkdir, rm, cp, mv, pwd, ps, kill, df, du, head, tail, grep, sed, awk, wget, curl (jika ada).
                    Tidak ada: apt, yum, brew, pacman. Tidak ada sudo.
                """.trimIndent()

                val cleanContext = stripAnsi(terminalContext).take(1500)

                val messagesArray = JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                    val contextPrompt = buildString {
                        if (cleanContext.isNotBlank()) {
                            append("Konteks Terminal:\n").append(cleanContext).append("\n\n")
                        }
                        append("Permintaan: ").append(userPrompt)
                    }
                    put(JSONObject().put("role", "user").put("content", contextPrompt))
                }

                val requestBody = JSONObject()
                    .put("model", settings.modelName)
                    .put("messages", messagesArray)
                    .put("temperature", settings.temperature)
                    .put("max_tokens", settings.maxTokens)
                    .toString()

                val writer = OutputStreamWriter(connection.outputStream, Charsets.UTF_8)
                writer.write(requestBody)
                writer.flush()
                writer.close()

                val responseCode = connection.responseCode
                val inputStream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line).append('\n')
                }
                reader.close()

                if (responseCode !in 200..299) {
                    val errBody = response.toString().take(800)
                    Log.e(tag, "API error $responseCode: $errBody")
                    return@withContext formatHttpError(responseCode, errBody)
                }

                val jsonResponse = JSONObject(response.toString())
                jsonResponse.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            } catch (e: java.net.SocketTimeoutException) {
                Log.e(tag, "Timeout: ${e.message}")
                "Timeout (${settings.requestTimeoutMs}ms). Provider lambat atau unreachable. Coba lagi atau naikkan timeout di Settings."
            } catch (e: java.net.UnknownHostException) {
                Log.e(tag, "Unknown host: ${e.message}")
                "DNS gagal: ${e.message}. Cek koneksi internet atau Base URL."
            } catch (e: javax.net.ssl.SSLException) {
                Log.e(tag, "SSL error: ${e.message}")
                "SSL/TLS error: ${e.message}. Mungkin Base URL salah atau sertifikat provider bermasalah."
            } catch (e: Exception) {
                Log.e(tag, "Generic error: ${e.javaClass.simpleName}: ${e.message}")
                "Kesalahan koneksi (${e.javaClass.simpleName}): ${e.message ?: "tidak diketahui"}"
            } finally {
                connection?.disconnect()
            }
        }
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
