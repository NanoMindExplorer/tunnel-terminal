package com.tunnel.terminal

/**
 * Konfigurasi AI provider.
 * AI provider configuration.
 *
 * Catatan Phase 17: Properti diubah dari var ke val untuk immutability.
 * Mutasi dilakukan via copy() yang sudah menjadi pattern di seluruh codebase.
 *
 * Note: Properties changed from var to val for immutability.
 */
data class AISettings(
    val providerName: String = "OpenAI",
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val modelName: String = "gpt-4o-mini",
    val temperature: Double = 0.2,
    val maxTokens: Int = 2000,
    val requestTimeoutMs: Int = 30000
)

/**
 * Preset provider untuk memudahkan konfigurasi.
 * Provider presets for quick setup.
 *
 * Catatan: Provider ditandai dengan jelas apakah menggunakan OpenAI-compatible API
 * atau native API. Untuk Claude/Gemini, kita hanya support via OpenAI-compat endpoint
 * (OpenRouter / Google's OpenAI-compat / Anthropic's OpenAI-compat), bukan native.
 *
 * Note: Claude/Gemini are supported only via OpenAI-compatible endpoints, not native APIs.
 */
object AIProviders {
    val presets = listOf(
        AISettings("OpenAI", "https://api.openai.com/v1", "", "gpt-4o-mini"),
        AISettings("DeepSeek", "https://api.deepseek.com/v1", "", "deepseek-chat"),
        AISettings("Groq (Llama 3)", "https://api.groq.com/openai/v1", "", "llama3-8b-8192"),
        AISettings("OpenRouter (Multi)", "https://openrouter.ai/api/v1", "", "anthropic/claude-3-haiku"),
        AISettings("Gemini (OpenAI Compat)", "https://generativelanguage.googleapis.com/v1beta/openai", "", "gemini-1.5-flash"),
        AISettings("Anthropic (OpenAI Compat)", "https://api.anthropic.com/v1", "", "claude-3-5-sonnet-latest"),
        AISettings("Local (Ollama)", "http://localhost:11434/v1", "", "llama3"),
        AISettings("Local (LM Studio)", "http://localhost:1234/v1", "", "local-model")
    )
}
