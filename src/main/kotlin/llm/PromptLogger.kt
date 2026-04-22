package llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger

/**
 * Writes every LLM prompt/response pair to `sent_prompt/session_<ts>/NNN_<caller>.json`.
 *
 * The format mirrors the older `intellij-plugin/sent_prompt/` dumps so existing
 * tooling that consumes those fixtures keeps working:
 *  - one folder per agent run (session)
 *  - one JSON file per LLM call, numbered with a zero-padded sequence
 *  - filename: `001_LLMReasoner_decide.json`, `002_ActionGenerator_analyzeToolResponse.json`, …
 *
 * The logger is intentionally best-effort: any IO error is swallowed with a
 * console warning so a broken disk never takes down the agent loop.
 */
class PromptLogger(
    baseDir: String = "sent_prompt",
    sessionId: String? = null,
) {
    /**
     * Caller-supplied context attached to each log record. Purely informational;
     * callers decide how much to fill in.
     */
    @Serializable
    data class LogContext(
        val caller: String,
        val intent: String = "",
        val iteration: Int = 0,
        val extra: Map<String, String> = emptyMap(),
    )

    /** Simple representation of one chat message in the request. */
    @Serializable
    data class LoggedMessage(val role: String, val content: String)

    @Serializable
    private data class Request(
        val model: String,
        val messages: List<LoggedMessage>,
    )

    @Serializable
    private data class Response(
        val raw: String,
        val parsed: String,
    )

    @Serializable
    private data class LogRecord(
        val timestamp: String,
        val callId: String,
        val caller: String,
        val intent: String,
        val iteration: Int,
        val extra: Map<String, String>,
        val request: Request,
        val response: Response,
        val durationMs: Long,
    )

    private val timestampFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault())

    private val resolvedSessionId: String =
        sessionId ?: ("session_" + timestampFormatter.format(Instant.now()))

    val sessionDir: File = File(baseDir, resolvedSessionId)
    private val counter = AtomicInteger(0)
    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
        }

    init {
        runCatching { sessionDir.mkdirs() }
    }

    /**
     * Record one LLM call.
     *
     * @param context caller-side metadata (intent, iteration, caller name).
     * @param model   OpenAI-style model id (free-form; stored as-is).
     * @param messages the request messages in their final order.
     * @param rawResponse the serialized HTTP body / the response text as returned by the SDK.
     * @param parsedResponse the text content extracted from [rawResponse].
     * @param durationMs wall-clock duration of the chat call.
     */
    fun log(
        context: LogContext,
        model: String,
        messages: List<LoggedMessage>,
        rawResponse: String,
        parsedResponse: String,
        durationMs: Long,
    ) {
        val id = counter.incrementAndGet()
        val callId = "%03d".format(id)
        val safeCaller = context.caller.replace(Regex("[^A-Za-z0-9_]+"), "_")
        val fileName = "${callId}_$safeCaller.json"

        val record =
            LogRecord(
                timestamp = Instant.now().toString(),
                callId = callId,
                caller = context.caller,
                intent = context.intent,
                iteration = context.iteration,
                extra = context.extra,
                request = Request(model = model, messages = messages),
                response = Response(raw = rawResponse, parsed = parsedResponse),
                durationMs = durationMs,
            )

        try {
            File(sessionDir, fileName).writeText(json.encodeToString(record))
        } catch (e: Exception) {
            // Logging must never break the agent.
            println("  PromptLogger: failed to write $fileName: ${e.message}")
        }
    }

    companion object {
        /** Convenience for callers that only have a system + user prompt. */
        fun messages(
            system: String,
            user: String,
        ): List<LoggedMessage> =
            listOf(
                LoggedMessage("system", system),
                LoggedMessage("user", user),
            )

        /** Human-readable session id for [sessionId] parameters. */
        fun newSessionId(): String {
            val fmt =
                DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault())
            return "session_" + fmt.format(LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant())
        }
    }
}
