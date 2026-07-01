package com.tunnel.terminal

// Data class untuk menyimpan konfigurasi AI
data class AISettings(
    var providerName: String = "OpenAI",
    var baseUrl: String = "https://api.openai.com/v1",
    var apiKey: String = "",
    var modelName: String = "gpt-4o-mini"
)

// Preset provider untuk memudahkan pengguna
object AIProviders {
    val presets = listOf(
        AISettings("OpenAI", "https://api.openai.com/v1", "", "gpt-4o-mini"),
        AISettings("DeepSeek", "https://api.deepseek.com/v1", "", "deepseek-chat"),
        AISettings("Groq", "https://api.groq.com/openai/v1", "", "llama3-8b-8192"),
        AISettings("OpenRouter", "https://openrouter.ai/api/v1", "", "anthropic/claude-3-haiku"), // OpenRouter bisa akses Claude/Gemini
        AISettings("Gemini (OpenAI Compat)", "https://generativelanguage.googleapis.com/v1beta/openai", "", "gemini-1.5-flash"),
        AISettings("Local (Ollama)", "http://localhost:11434/v1", "", "llama3")
    )
}
