package com.tunnel.terminal

/**
 * Konfigurasi AI provider.
 * AI provider configuration.
 *
 * Phase 18: Properti diubah dari var ke val untuk immutability.
 * Phase 19: Tambah supportsVision flag untuk deteksi model image-capable.
 */
data class AISettings(
    val providerName: String = "OpenAI",
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val modelName: String = "gpt-4o-mini",
    val temperature: Double = 0.2,
    val maxTokens: Int = 2000,
    val requestTimeoutMs: Int = 30000,
    /** True jika model mendukung image input (gpt-4o, gemini-1.5-flash, dll). */
    val supportsVision: Boolean = false
)

/**
 * Preset provider untuk memudahkan konfigurasi.
 * Provider presets for quick setup.
 *
 * Phase 19: Tambah "Custom" preset agar user bebas masukin provider apapun.
 * User juga bisa fetch model list dari /models endpoint (lihat ModelFetcher).
 *
 * Catatan: Provider ditandai dengan jelas apakah menggunakan OpenAI-compatible API
 * atau native API. Untuk Claude/Gemini, kita hanya support via OpenAI-compat endpoint.
 */
object AIProviders {
    val presets = listOf(
        AISettings("OpenAI", "https://api.openai.com/v1", "", "gpt-4o-mini", supportsVision = true),
        AISettings("DeepSeek", "https://api.deepseek.com/v1", "", "deepseek-chat"),
        AISettings("Groq (Llama 3)", "https://api.groq.com/openai/v1", "", "llama3-8b-8192"),
        AISettings("OpenRouter (Multi)", "https://openrouter.ai/api/v1", "", "anthropic/claude-3-haiku", supportsVision = true),
        AISettings("Gemini (OpenAI Compat)", "https://generativelanguage.googleapis.com/v1beta/openai", "", "gemini-1.5-flash", supportsVision = true),
        AISettings("Anthropic (OpenAI Compat)", "https://api.anthropic.com/v1", "", "claude-3-5-sonnet-latest", supportsVision = true),
        AISettings("Mistral", "https://api.mistral.ai/v1", "", "mistral-small-latest"),
        AISettings("Together AI", "https://api.together.xyz/v1", "", "meta-llama/Llama-3-8b-chat-hf"),
        AISettings("Fireworks AI", "https://api.fireworks.ai/inference/v1", "", "accounts/fireworks/models/llama-v3-8b"),
        AISettings("Perplexity", "https://api.perplexity.ai", "", "llama-3-sonar-small-32k-online"),
        AISettings("Local (Ollama)", "http://localhost:11434/v1", "", "llama3"),
        AISettings("Local (LM Studio)", "http://localhost:1234/v1", "", "local-model"),
        /* Custom: user bebas masukin baseUrl + apiKey + model apapun.
         * Custom: user can enter any baseUrl + apiKey + model. */
        AISettings("Custom", "", "", "", supportsVision = false)
    )

    /** Default preset untuk provider baru. Default preset for new provider. */
    val default: AISettings = presets.first()
}
