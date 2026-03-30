package llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Logs LLM prompts and responses to sent_prompt directory for debugging and analysis.
 *
 * Each session creates a separate subdirectory under sent_prompt/.
 * Each LLM interaction is saved as a JSON file with:
 * - Timestamp and call ID
 * - Caller information (class.method)
 * - Request (model, messages)
 * - Response (raw and parsed)
 * - Context (intent, iteration, action history)
 * - Duration
 *
 * Directory structure:
 * sent_prompt/
 *   session_2026-03-28_23-47-38/
 *     001_LLMReasoner_decide.json
 *     002_LLMReasoner_decide.json
 *     ...
 *   session_2026-03-29_10-30-00/
 *     001_LLMReasoner_decide.json
 *     ...
 */
class PromptLogger(
    private val baseDir: String = "sent_prompt"
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private var callCounter = 0
    
    // Session timestamp - created once when logger is instantiated
    private val sessionTimestamp: String = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
        .withZone(ZoneId.systemDefault())
        .format(Instant.now())
    
    // Session directory path
    private val sessionDir: File = File(baseDir, "session_$sessionTimestamp")

    @Serializable
    data class LogEntry(
        val timestamp: String,
        val callId: String,
        val caller: String,
        val intent: String? = null,
        val iteration: Int? = null,
        val request: RequestInfo,
        val response: ResponseInfo,
        val durationMs: Long
    )

    @Serializable
    data class RequestInfo(
        val model: String,
        val messages: List<MessageInfo>
    )

    @Serializable
    data class MessageInfo(
        val role: String,
        val content: String
    )

    @Serializable
    data class ResponseInfo(
        val raw: String,
        val parsed: String? = null
    )

    @Serializable
    data class LogContext(
        val caller: String,
        val intent: String? = null,
        val iteration: Int? = null,
        val actionHistorySummary: String? = null
    )

    /**
     * Log an LLM interaction.
     *
     * @param model The LLM model name
     * @param messages The messages sent to the LLM
     * @param rawResponse The raw JSON response from the LLM server
     * @param parsedResponse The parsed content from the response
     * @param context Additional context about the call
     * @param durationMs Duration of the call in milliseconds
     */
    fun log(
        model: String,
        messages: List<Map<String, String>>,
        rawResponse: String,
        parsedResponse: String? = null,
        context: LogContext? = null,
        durationMs: Long
    ) {
        callCounter++
        val timestamp = Instant.now().toString()
        val callId = callCounter.toString().padStart(3, '0')

        val entry = LogEntry(
            timestamp = timestamp,
            callId = callId,
            caller = context?.caller ?: "unknown",
            intent = context?.intent,
            iteration = context?.iteration,
            request = RequestInfo(
                model = model,
                messages = messages.map { m ->
                    MessageInfo(role = m["role"] ?: "", content = m["content"] ?: "")
                }
            ),
            response = ResponseInfo(
                raw = rawResponse,
                parsed = parsedResponse
            ),
            durationMs = durationMs
        )

        saveToFile(entry, context?.caller ?: "unknown", callId)
    }

    /**
     * Save the log entry to a JSON file.
     *
     * Files are saved in the session directory.
     * Filename format: {callId}_{caller}.json
     * Example: 001_LLMReasoner_decide.json
     */
    private fun saveToFile(entry: LogEntry, caller: String, callId: String) {
        // Create session directory if it doesn't exist
        if (!sessionDir.exists()) sessionDir.mkdirs()

        // Create filename with call ID and caller info (simpler format within session)
        val callerSanitized = caller.replace(".", "_").replace("/", "_")
        val filename = "${callId}_${callerSanitized}.json"
        val file = File(sessionDir, filename)

        try {
            file.writeText(json.encodeToString(entry))
            println("PromptLogger: Saved prompt/response to ${file.path}")
        } catch (e: Exception) {
            println("PromptLogger: Failed to save log entry: ${e.message}")
        }
    }
    
    /**
     * Get the session directory path.
     */
    fun getSessionDir(): String = sessionDir.path

    /**
     * Reset the call counter.
     */
    fun clear() {
        callCounter = 0
    }

    /**
     * Get the number of logged calls.
     */
    fun getCallCount(): Int = callCounter
}
