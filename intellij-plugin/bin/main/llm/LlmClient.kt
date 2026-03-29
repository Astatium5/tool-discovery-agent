package llm

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.TimeUnit
import java.io.File

/**
 * HTTP client for LLM chat completions using OpenAI-compatible API.
 *
 * Supports multiple providers:
 * - Alibaba Qwen (qwen3.5-plus, qwen3-max, qwen3-coder)
 * - Zhipu (glm-5, glm-4.7)
 * - Kimi (kimi-k2.5)
 * - MiniMax (MiniMax-M2.5)
 *
 * Configuration is loaded from:
 * 1. Environment variables (LLM_BASE_URL, LLM_MODEL, LLM_API_KEY)
 * 2. .env file in the project root
 * 3. Default values
 *
 * Usage:
 * ```
 * // Using environment variables or .env file
 * val client = LlmClient()
 *
 * // Custom configuration (overrides env vars)
 * val client = LlmClient(
 *     baseUrl = "https://coding-intl.dashscope.aliyuncs.com/v1",
 *     model = "qwen3-max-2026-01-23",
 *     apiKey = "your-api-key"
 * )
 * ```
 */
class LlmClient(
    private val baseUrl: String = loadEnvVar("LLM_BASE_URL", "https://coding-intl.dashscope.aliyuncs.com/v1"),
    private val model: String = loadEnvVar("LLM_MODEL", "MiniMax-M2.5"),
    private val apiKey: String = loadEnvVar("LLM_API_KEY", "")
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val jsonParser = Json { ignoreUnknownKeys = true }

    /**
     * Send a list of messages and get the assistant's reply.
     * Each message is a map with "role" and "content" keys.
     */
    fun chat(messages: List<Map<String, String>>): String {
        val messagesJson = messages.joinToString(",\n    ") { msg ->
            """{"role": "${msg["role"]}", "content": ${msg["content"]?.jsonEscape() ?: "\"\"" }}"""
        }

        val body = """
        {
            "model": "$model",
            "messages": [
                $messagesJson
            ]
        }
        """.trimIndent()

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

        val response = http.newCall(request).execute()
        val responseBody = response.body?.string()
            ?: throw RuntimeException("Empty response from LLM server")

        if (!response.isSuccessful) {
            throw RuntimeException("LLM server returned ${response.code}: $responseBody")
        }

        return extractContent(responseBody)
    }

    /**
     * Convenience: send a system prompt + user prompt, get the reply.
     */
    fun chatStructured(systemPrompt: String, userPrompt: String): String {
        return chat(listOf(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to userPrompt)
        ))
    }

    /**
     * Extract the assistant message content from the JSON response.
     *
     * Uses JSON parsing instead of regex to avoid catastrophic backtracking
     * / StackOverflowError on very large model outputs.
     */
    private fun extractContent(json: String): String {
        val root = try {
            jsonParser.parseToJsonElement(json) as? JsonObject
        } catch (e: Exception) {
            throw RuntimeException("Invalid JSON from LLM response: ${e.message}. Body: ${json.take(300)}")
        } ?: throw RuntimeException("LLM response root is not an object: ${json.take(300)}")

        val choices = root["choices"] as? JsonArray
            ?: throw RuntimeException("No 'choices' in LLM response: ${json.take(300)}")
        val firstChoice = choices.firstOrNull() as? JsonObject
            ?: throw RuntimeException("Empty 'choices' in LLM response: ${json.take(300)}")
        val message = firstChoice["message"] as? JsonObject
            ?: throw RuntimeException("No 'message' in first choice: ${json.take(300)}")
        val content = message["content"]
            ?: throw RuntimeException("No 'content' in message: ${json.take(300)}")

        return contentToString(content)
    }

    /**
     * Supports both OpenAI-style string content and multi-part content arrays.
     */
    private fun contentToString(content: JsonElement): String {
        return when (content) {
            is JsonPrimitive -> content.content
            is JsonArray -> content.joinToString("") { part ->
                when (part) {
                    is JsonPrimitive -> part.content
                    is JsonObject -> {
                        // Handles content parts like {"type":"text","text":"..."}
                        val text = part["text"]
                        when (text) {
                            is JsonPrimitive -> text.content
                            JsonNull, null -> ""
                            else -> text.toString()
                        }
                    }
                    else -> part.toString()
                }
            }
            else -> content.toString()
        }
    }

    private fun String.jsonEscape(): String {
        val escaped = this
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    companion object {
        /**
         * Load an environment variable from:
         * 1. System environment variables
         * 2. .env file in the project root
         * 3. Default value
         */
        private fun loadEnvVar(name: String, default: String): String {
            // First check system environment
            val sysEnv = System.getenv(name)
            if (!sysEnv.isNullOrBlank()) {
                return sysEnv
            }

            // Then check .env file
            val envFile = File(".env")
            if (envFile.exists()) {
                val lines = envFile.readLines()
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("$name=")) {
                        return trimmed.substringAfter("$name=").trim()
                    }
                }
            }

            // Return default
            return default
        }
    }
}
