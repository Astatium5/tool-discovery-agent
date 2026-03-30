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
    fun create(
        apiKey: String,
        baseUrl: String = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        model: String = "qwen3.5-plus",
    ): ChatModel {
        return OpenAiChatModel.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .modelName(model)
            .responseFormat("json_object")
            .build()
    }
}
