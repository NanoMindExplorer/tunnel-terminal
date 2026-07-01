package com.tunnel.terminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class ChatMessage(val role: String, val content: String, val isCommand: Boolean = false)

class AIAgent {
    suspend fun askAI(settings: AISettings, userPrompt: String, terminalContext: String): String {
        return withContext(Dispatchers.IO) {
            try {
                // Gabungkan base URL dengan endpoint standar
                val apiUrl = "${settings.baseUrl.trimEnd('/')}/chat/completions"
                val url = URL(apiUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
                
                // Tambahan header untuk OpenRouter (Opsional, tidak berdampak jika pakai provider lain)
                connection.setRequestProperty("HTTP-Referer", "https://github.com/NanoMindExplorer/tunnel-terminal")
                connection.setRequestProperty("X-Title", "Tunnel Terminal")
                
                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                connection.doOutput = true

                val systemPrompt = """
                    Anda adalah 'Tunnel AI', asisten terminal copilot untuk perangkat Android.
                    Tugas Anda adalah membantu menulis perintah shell, mendebug error, dan menjelaskan kode.
                    Jika Anda memberikan perintah shell yang bisa dijalankan, SELALU bungkus dengan format blok kode bash: 
                    ```bash
                    perintah_disini
                    ```
                """.trimIndent()

                val messagesArray = JSONArray()
                messagesArray.put(JSONObject().put("role", "system").put("content", systemPrompt))
                val contextPrompt = "Konteks Terminal Saat Ini:\n$terminalContext\n\nPermintaan Pengguna: $userPrompt"
                messagesArray.put(JSONObject().put("role", "user").put("content", contextPrompt))

                val requestBody = JSONObject()
                    .put("model", settings.modelName)
                    .put("messages", messagesArray)
                    .put("temperature", 0.7)
                    .toString()

                val writer = OutputStreamWriter(connection.outputStream)
                writer.write(requestBody)
                writer.flush()
                writer.close()

                val responseCode = connection.responseCode
                val inputStream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                val reader = BufferedReader(InputStreamReader(inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()

                if (responseCode !in 200..299) {
                    return@withContext "Error API ($responseCode): ${response.toString().take(300)}"
                }

                val jsonResponse = JSONObject(response.toString())
                val aiMessage = jsonResponse.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")

                aiMessage
            } catch (e: Exception) {
                "Kesalahan Koneksi: ${e.message}"
            }
        }
    }
}
