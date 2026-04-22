package llm

import dev.langchain4j.model.chat.ChatModel
import java.io.File

/**
 * LLM configuration loaded from .env file with env var fallbacks.
 */
data class LlmConfig(
    val apiKey: String,
    val baseUrl: String,
    val model: String,
) {
    companion object {
        private const val DEFAULT_BASE_URL = "https://coding-intl.dashscope.aliyuncs.com/v1"
        private const val DEFAULT_MODEL = "kimi-k2.5"

        fun load(projectPath: String? = null): LlmConfig {
            val envConfig = loadEnvFile(projectPath)
            return LlmConfig(
                apiKey = envConfig["LLM_API_KEY"] ?: System.getenv("LLM_API_KEY") ?: "",
                baseUrl = envConfig["LLM_BASE_URL"] ?: System.getenv("LLM_BASE_URL") ?: DEFAULT_BASE_URL,
                model = envConfig["LLM_MODEL"] ?: System.getenv("LLM_MODEL") ?: DEFAULT_MODEL,
            )
        }

        fun loadFromEnv(): LlmConfig = LlmConfig(
            apiKey = System.getenv("LLM_API_KEY") ?: "",
            baseUrl = System.getenv("LLM_BASE_URL") ?: DEFAULT_BASE_URL,
            model = System.getenv("LLM_MODEL") ?: DEFAULT_MODEL,
        )

        private fun loadEnvFile(projectPath: String?): Map<String, String> {
            if (projectPath == null) return emptyMap()
            val envFile = File(projectPath, ".env")
            if (!envFile.exists()) return emptyMap()
            return envFile.readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .associate { line ->
                    val parts = line.split("=", limit = 2)
                    parts[0].trim() to parts.getOrElse(1) { "" }.trim()
                }
        }
    }

    fun hasApiKey(): Boolean = apiKey.isNotBlank()

    fun createChatModel(): ChatModel = LlmModel.create(apiKey, baseUrl, model)

    fun createChatModelForVision(): ChatModel = LlmModel.createForVision(apiKey, baseUrl, model)
}