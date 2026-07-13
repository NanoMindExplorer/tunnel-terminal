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
        /* Local providers tidak butuh API key — parse host, not substring. */
        val base = settings.baseUrl.trimEnd('/')
        val parsed = try { URL(if (base.contains("://")) base else "https://$base") } catch (e: Exception) {
            return@withContext Result.failure(IllegalArgumentException("Base URL invalid: ${e.message}"))
        }
        val host = parsed.host.lowercase()
        val isLocal = host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2" || host == "::1"
        if (!isLocal && settings.apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("API Key kosong"))
        }

        var connection: HttpURLConnection? = null
        try {
            /* Wave-5: Anthropic native has no OpenAI /models — skip gracefully. */
            if (settings.isAnthropicNative) {
                return@withContext Result.success(
                    listOf(
                        ModelInfo("claude-3-5-sonnet-latest", "Claude 3.5 Sonnet", "anthropic", true),
                        ModelInfo("claude-3-5-haiku-latest", "Claude 3.5 Haiku", "anthropic", true),
                        ModelInfo("claude-3-opus-latest", "Claude 3 Opus", "anthropic", true),
                        ModelInfo("claude-sonnet-4-20250514", "Claude Sonnet 4", "anthropic", true)
                    )
                )
            }
            val apiUrl = "$base/models"
            val url = URL(apiUrl)
            /* Wave-5: same HTTPS policy as chat completions. */
            if (!url.protocol.equals("https", ignoreCase = true) && !isLocal) {
                return@withContext Result.failure(
                    IllegalArgumentException("Security: only HTTPS allowed for external model list")
                )
            }
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                if (settings.apiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
                }
                setRequestProperty("HTTP-Referer", "https://github.com/NanoMindExplorer/tunnel-terminal")
                setRequestProperty("X-Title", "Tunnel Terminal")
                setRequestProperty("User-Agent", "TunnelTerminal/${BuildConfig.VERSION_NAME} (Android)")
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
        /* BUG-32 fix: Updated vision detection untuk model naming 2026. */
        /* OpenAI: gpt-4o, gpt-4-turbo, gpt-4-vision. */
        if (id.contains("gpt-4o") || id.contains("gpt-4-vision") || id.contains("gpt-4-turbo")) return true
        /* Gemini: 1.5+, 2.0+, pro-vision. */
        if (id.contains("gemini-1.5") || id.contains("gemini-2") || id.contains("gemini-pro-vision")) return true
        /* Claude: 3+, 4+, sonnet, opus, haiku (all support vision). */
        if (id.contains("claude-3") || id.contains("claude-4") || id.contains("claude-opus") ||
            id.contains("claude-sonnet") || id.contains("claude-haiku")) return true
        /* Llama 3.2 vision variants. */
        if (id.contains("llama-3.2-11b") || id.contains("llama-3.2-90b") || id.contains("llama-3.2-vision")) return true
        /* Mistral pixtral. */
        if (id.contains("pixtral")) return true
        /* Qwen-VL variants. */
        if (id.contains("qwen-vl") || id.contains("qwen2-vl") || id.contains("qwen2.5-vl")) return true
        /* Phi-3/4 vision. */
        if (id.contains("phi-3-vision") || id.contains("phi-3.5-vision") || id.contains("phi-4-vision")) return true
        /* Generic "vision"/"vl" keyword. */
        if (id.contains("vision") || id.contains("vl-") || id.contains("-vl") ||
            id.contains("multimodal") || id.contains("mm-")) return true
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
