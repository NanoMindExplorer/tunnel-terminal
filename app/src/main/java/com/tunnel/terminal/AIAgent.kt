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
    // Format OpenAI API (Bisa diganti Base URL ke OpenRouter, Groq, dll)
    private val apiUrl = "https://api.openai.com/v1/chat/completions"
    private val model = "gpt-3.5-turbo" // Bisa diubah ke gpt-4o-mini atau lainnya

    suspend fun askAI(apiKey: String, userPrompt: String, terminalContext: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(apiUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                connection.doOutput = true

                // System prompt yang menginstruksikan AI bertindak sebagai Copilot
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
                
                // Tambahkan konteks terminal
                val contextPrompt = "Konteks Terminal Saat Ini:\n$terminalContext\n\nPermintaan Pengguna: $userPrompt"
                messagesArray.put(JSONObject().put("role", "user").put("content", contextPrompt))

                val requestBody = JSONObject()
                    .put("model", model)
                    .put("messages", messagesArray)
                    .put("temperature", 0.7)
                    .toString()

                // Kirim request
                val writer = OutputStreamWriter(connection.outputStream)
                writer.write(requestBody)
                writer.flush()
                writer.close()

                // Baca response
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
                    return@withContext "Error API ($responseCode): ${response.toString().take(200)}"
                }

                // Parse JSON response
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
