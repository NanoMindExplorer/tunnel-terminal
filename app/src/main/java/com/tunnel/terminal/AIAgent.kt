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

data class ChatMessage(val role: String, val content: String, val isCommand: Boolean = false, val commands: List<String> = emptyList())

class AIAgent {
    suspend fun askAI(settings: AISettings, userPrompt: String, terminalContext: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val apiUrl = "${settings.baseUrl.trimEnd('/')}/chat/completions"
                val url = URL(apiUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
                connection.setRequestProperty("HTTP-Referer", "https://github.com/NanoMindExplorer/tunnel-terminal")
                connection.setRequestProperty("X-Title", "Tunnel Terminal")
                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                connection.doOutput = true

                // Sistem Prompt Revolusioner: Auto-Pilot Agent
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
                """.trimIndent()

                val messagesArray = JSONArray()
                messagesArray.put(JSONObject().put("role", "system").put("content", systemPrompt))
                val contextPrompt = "Konteks Terminal:\n$terminalContext\n\nPermintaan: $userPrompt"
                messagesArray.put(JSONObject().put("role", "user").put("content", contextPrompt))

                val requestBody = JSONObject()
                    .put("model", settings.modelName)
                    .put("messages", messagesArray)
                    .put("temperature", 0.2) // Lebih presisi untuk kode
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
                jsonResponse.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            } catch (e: Exception) {
                "Kesalahan Koneksi: ${e.message}"
            }
        }
    }
}
