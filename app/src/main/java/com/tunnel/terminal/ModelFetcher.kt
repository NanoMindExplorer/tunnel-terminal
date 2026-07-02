package com.tunnel.terminal

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * ModelInfo - representasi satu model dari /models endpoint.
 * Single model entry from /models endpoint.
 */
data class ModelInfo(
    val id: String,
    val name: String,
    val ownedBy: String,
    /** Heuristic: apakah model ini mendukung image input. */
    val supportsVision: Boolean
)

/**
 * ModelFetcher - Fetch daftar model dari provider OpenAI-compatible.
 *
 * Phase 19: Implementasi /models endpoint fetcher.
 * Hampir semua provider OpenAI-compatible expose endpoint GET /models
 * yang return JSON: {"data": [{"id": "gpt-4o-mini", "owned_by": "openai", ...}]}
 *
 * Implementasi ini:
 * - GET {baseUrl}/models dengan Authorization: Bearer {apiKey}
 * - Parse JSON response
 * - Heuristic deteksi vision-capable model berdasarkan nama:
 *   - "gpt-4o", "gpt-4-vision", "vision" -> vision
 *   - "gemini-1.5", "gemini-pro-vision" -> vision
 *   - "claude-3" -> vision (semua Claude 3+ support vision)
 *   - "llama-3.2-11b", "llama-3.2-90b" -> vision (Llama 3.2 vision variants)
 *   - "pixtral" -> vision
 *   - lainnya -> non-vision
 */
object ModelFetcher {
    private const val TAG = "ModelFetcher"

    /**
     * Fetch daftar model dari provider.
     * Fetch model list from provider.
     *
     * @param settings konfigurasi provider (baseUrl + apiKey)
     * @return list of ModelInfo, atau empty list jika gagal
     */
    suspend fun fetchModels(settings: AISettings): Result<List<ModelInfo>> = withContext(Dispatchers.IO) {
        if (settings.baseUrl.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Base URL kosong"))
        }
        /* Local providers tidak butuh API key. */
        val isLocal = settings.baseUrl.contains("localhost") || settings.baseUrl.contains("127.0.0.1")
        if (!isLocal && settings.apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("API Key kosong"))
        }

        var connection: HttpURLConnection? = null
        try {
            val apiUrl = "${settings.baseUrl.trimEnd('/')}/models"
            val url = URL(apiUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                if (settings.apiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
                }
                setRequestProperty("HTTP-Referer", "https://github.com/NanoMindExplorer/tunnel-terminal")
                setRequestProperty("X-Title", "Tunnel Terminal")
                setRequestProperty("User-Agent", "TunnelTerminal/3.2 (Android)")
                connectTimeout = 15000
                readTimeout = 15000
            }

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
                val errBody = response.toString().take(300)
                Log.e(TAG, "Fetch models error $responseCode: $errBody")
                return@withContext Result.failure(Exception("HTTP $responseCode: ${errBody.take(100)}"))
            }

            val json = JSONObject(response.toString())
            val dataArray = json.optJSONArray("data")
                ?: /* Beberapa provider return array langsung. Some providers return raw array. */
                return@withContext Result.failure(Exception("Format response tidak dikenali (no 'data' field)"))

            val models = mutableListOf<ModelInfo>()
            for (i in 0 until dataArray.length()) {
                val item = dataArray.optJSONObject(i) ?: continue
                val id = item.optString("id", "")
                if (id.isBlank()) continue
                val name = item.optString("id", id) /* Pakai id sebagai name fallback */
                val ownedBy = item.optString("owned_by", item.optString("owner", "unknown"))
                models.add(ModelInfo(
                    id = id,
                    name = name,
                    ownedBy = ownedBy,
                    supportsVision = detectVisionCapability(id)
                ))
            }

            /* Sort: model populer di atas.
             * Sort: popular models first. */
            models.sortWith(compareByDescending<ModelInfo> { popularityScore(it.id) }.thenBy { it.id })

            Log.i(TAG, "Fetched ${models.size} models from ${settings.providerName}")
            Result.success(models)
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "Timeout: ${e.message}")
            Result.failure(Exception("Timeout (15s). Provider lambat atau unreachable."))
        } catch (e: java.net.UnknownHostException) {
            Log.e(TAG, "DNS: ${e.message}")
            Result.failure(Exception("DNS gagal: ${e.message}. Cek Base URL."))
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.javaClass.simpleName}: ${e.message}")
            Result.failure(Exception("${e.javaClass.simpleName}: ${e.message}"))
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Heuristic deteksi apakah model mendukung image input berdasarkan nama.
     * Heuristic vision-capable model detection by name.
     */
    private fun detectVisionCapability(modelId: String): Boolean {
        val id = modelId.lowercase()
        /* OpenAI vision models. */
        if (id.contains("gpt-4o") || id.contains("gpt-4-vision") || id.contains("gpt-4-turbo")) return true
        /* Gemini (all 1.5+ support vision). */
        if (id.contains("gemini-1.5") || id.contains("gemini-pro-vision") || id.contains("gemini-2")) return true
        /* Claude 3+ (all support vision). */
        if (id.contains("claude-3") || id.contains("claude-3.5") || id.contains("claude-4")) return true
        /* Llama 3.2 vision variants. */
        if (id.contains("llama-3.2-11b") || id.contains("llama-3.2-90b") || id.contains("llama-3.2-vision")) return true
        /* Mistral pixtral. */
        if (id.contains("pixtral")) return true
        /* Qwen-VL variants. */
        if (id.contains("qwen-vl") || id.contains("qwen2-vl")) return true
        /* Phi-3 vision. */
        if (id.contains("phi-3-vision") || id.contains("phi-3.5-vision")) return true
        /* Generic "vision" keyword. */
        if (id.contains("vision") || id.contains("vl-") || id.contains("-vl")) return true
        return false
    }

    /**
     * Skor popularitas untuk sorting (model yang lebih umum didahulukan).
     * Popularity score for sorting.
     */
    private fun popularityScore(id: String): Int {
        val lower = id.lowercase()
        return when {
            lower.contains("gpt-4o-mini") -> 100
            lower.contains("gpt-4o") -> 95
            lower.contains("gpt-3.5-turbo") -> 90
            lower.contains("claude-3-5-sonnet") -> 88
            lower.contains("claude-3-haiku") -> 85
            lower.contains("gemini-1.5-flash") -> 82
            lower.contains("gemini-1.5-pro") -> 80
            lower.contains("deepseek-chat") -> 78
            lower.contains("llama3-8b") -> 75
            lower.contains("llama3-70b") -> 73
            lower.contains("mistral-small") -> 70
            lower.contains("mistral-large") -> 68
            else -> 50
        }
    }
}
