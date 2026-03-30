package llm

import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.service.AiServices
import dev.langchain4j.service.UserMessage
import dev.langchain4j.service.V

/**
 * LangChain4j AiServices interface for structured LLM decisions.
 *
 * Uses @UserMessage template with @V annotations for parameter binding.
 * The LLM returns LLMDecisionDto directly — no regex JSON parsing needed.
 *
 * The model must be configured with responseFormat("json_object") for this to work.
 * See LlmModel.create().
 */
interface DecisionService {
    @UserMessage(
        """Given the current UI state, decide the next action to accomplish the task.

UI State:
{{uiState}}

Task Intent: {{intent}}

{{actionHistory}}

Return a JSON decision with the reasoning, action to take, expected result, confidence, and task completion status.
The action object must have a "type" field (one of: open_file, move_caret, select_lines, click, type, press_key, select_dropdown, wait, observe, complete, fail) and type-specific fields.""",
    )
    fun decide(
        @V("uiState") uiState: String,
        @V("intent") intent: String,
        @V("actionHistory") actionHistory: String,
    ): LLMReasoner.LLMDecisionDto

    companion object {
        fun create(chatModel: ChatModel): DecisionService {
            return AiServices.builder(DecisionService::class.java)
                .chatModel(chatModel)
                .build()
        }
    }
}
