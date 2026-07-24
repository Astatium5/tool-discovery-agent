package llm

import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.openai.OpenAiChatModel
import java.time.Duration

/**
 * Generic LLM model factory using OpenAI-compatible API.
 *
 * Works with any provider that exposes an OpenAI-compatible endpoint:
 * Alibaba Cloud Coding Plan Lite, OpenAI, Azure OpenAI, LM Studio, etc.
 *
 * Timeouts are tuned for **small local models** (e.g. 8B LLMs running through
 * LM Studio): first-token latency on Apple Silicon can be 5-10s, and a full
 * generation with a large UI-tree context can run 15-30s. The default
 * langchain4j 60s timeout is therefore raised to 180s. Max retries is lowered
 * to 1 so a single stalled request fails fast — the agent loop recovers
 * via Observe rather than blocking 60+ seconds on three retries.
 *
 * Sampling is pinned to **temperature 0** (greedy) and a fixed **seed**.
 * Small local models (LFM2.5-8B-A1B in particular) under default LM Studio
 * sampling (temp 0.7, top_p 0.95, top_k 40) produce non-deterministic action
 * selections on the same prompt — the model samples among plausibly-correct
 * candidates (e.g. `MoveCaret('maybeCompleteCheckpoint')` vs. the
 * `completePendingCheckpoint` that the prompt actually named). For an
 * action-selection agent we need the argmax, not a sample. Greedy decoding
 * also collapses the JSON-shape distribution (less "action": [...] wrapping,
 * fewer assumptions-as-array hallucinations). Seed 42 makes runs
 * reproducible for debugging.
 */
object LlmModel {
    fun create(
        apiKey: String,
        baseUrl: String = "http://127.0.0.1:1234/v1",
        model: String = "lfm2.5-8b-a1b-mlx",
        timeout: Duration = Duration.ofSeconds(180),
        maxRetries: Int = 1,
        temperature: Double = 0.0,
        seed: Int = 42,
    ): ChatModel {
        return OpenAiChatModel.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .modelName(model)
            .timeout(timeout)
            .maxRetries(maxRetries)
            .temperature(temperature)
            .seed(seed)
//            .responseFormat("json_object")
            .build()
    }
}
