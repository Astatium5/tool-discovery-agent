package llm

import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.openai.OpenAiChatModel

/**
 * Generic LLM model factory using OpenAI-compatible API.
 *
 * Works with any provider that exposes an OpenAI-compatible endpoint:
 * Alibaba Cloud Coding Plan Lite, OpenAI, Azure OpenAI, etc.
 */
object LlmModel {
    private const val DEFAULT_BASE_URL = "https://coding-intl.dashscope.aliyuncs.com/v1"
    private const val DEFAULT_MODEL = "kimi-k2.5"

    /**
     * Create a chat model for general use (no forced JSON format).
     * The TreeReasoner parses JSON from response manually.
     */
    fun create(
        apiKey: String,
        baseUrl: String = DEFAULT_BASE_URL,
        model: String = DEFAULT_MODEL,
    ): ChatModel {
        return OpenAiChatModel.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .modelName(model)
            // NO responseFormat - let model output naturally, parse JSON manually
            .build()
    }

    /**
     * Create a chat model for vision/screenshot analysis (plain text output).
     * VisionReasoner expects plain text format, not JSON.
     */
    fun createForVision(
        apiKey: String,
        baseUrl: String = DEFAULT_BASE_URL,
        model: String = DEFAULT_MODEL,
    ): ChatModel {
        return OpenAiChatModel.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .modelName(model)
            // NO responseFormat - allows plain text output
            .build()
    }
}
