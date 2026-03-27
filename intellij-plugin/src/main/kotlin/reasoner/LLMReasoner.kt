package reasoner

import llm.LlmClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import parser.UiComponent
import profile.ApplicationProfile
import formatter.UiTreeFormatter
import recipe.VerifiedRecipe

/**
 * LLM Reasoner - Makes decisions based on intent and observed UI state.
 *
 * This component is the "brain" that:
 * 1. Receives the user's intent
 * 2. Observes the current UI state
 * 3. Decides the next action to take
 * 4. Returns structured decisions for the actor to execute
 *
 * The LLM acts as a developer at the keyboard, reading the screen
 * and deciding what to do next.
 */
class LLMReasoner(private val llm: LlmClient) {

    /**
     * A decision made by the LLM.
     */
    data class Decision(
        val reasoning: String,
        val action: Action,
        val expectedResult: String,
        val confidence: Double,
        val taskComplete: Boolean = false
    )

    /**
     * An action to be executed.
     *
     * NOTE: GenerateJs has been removed. If JavaScript execution is needed,
     * it should be implemented as a specific action type with proper error handling.
     */
    sealed class Action {
        // Navigation actions (for IDE file/symbol navigation)
        data class OpenFile(val path: String) : Action()
        data class MoveCaret(val symbol: String) : Action()
        data class SelectLines(val start: Int, val end: Int) : Action()
        
        // UI interaction actions
        data class Click(val target: String) : Action()
        data class Type(val text: String, val clearFirst: Boolean = true, val target: String? = null) : Action()
        data class PressKey(val key: String) : Action()
        data class SelectDropdown(val target: String, val value: String) : Action()
        data class Wait(val elementType: String, val timeoutMs: Long = 5000) : Action()
        data class UseRecipe(val recipeId: String, val params: Map<String, String>) : Action()
        data object Observe : Action()  // Just observe, don't act
        data object Complete : Action()  // Task is complete
        data object Fail : Action()      // Cannot proceed
    }

    /**
     * Context for decision-making.
     * Uses raw UiComponent tree instead of intermediate UIObservation.
     */
    data class DecisionContext(
        val intent: String,
        val uiTree: List<UiComponent>,  // Raw UI tree - formatted directly for LLM
        val profile: ApplicationProfile,  // Profile for role detection
        val actionHistory: List<HistoryEntry>,
        val matchedRecipe: MatchedRecipe? = null,  // Full recipe with step tracking
        val parsedIntent: ParsedIntent? = null  // Extracted parameters from intent
    )

    /**
     * Parsed intent with extracted parameters.
     */
    data class ParsedIntent(
        val goal: String,
        val targetFile: String? = null,
        val targetSymbol: String? = null,
        val lineStart: Int? = null,
        val lineEnd: Int? = null,
        val newName: String? = null,
        val operation: String? = null
    ) {
        fun format(): String {
            val parts = mutableListOf<String>()
            targetFile?.let { parts.add("Target File: $it") }
            targetSymbol?.let { parts.add("Target Symbol: $it") }
            if (lineStart != null && lineEnd != null) {
                parts.add("Line Range: $lineStart-$lineEnd")
            }
            newName?.let { parts.add("New Name: $it") }
            operation?.let { parts.add("Operation: $it") }
            return if (parts.isNotEmpty()) {
                parts.joinToString("\n") { "- $it" }
            } else ""
        }
    }

    /**
     * Entry in the action history.
     */
    data class HistoryEntry(
        val action: Action,
        val result: String,
        val success: Boolean
    )

    /**
     * A matched recipe with full details and step tracking.
     */
    data class MatchedRecipe(
        val recipe: VerifiedRecipe,
        val currentStep: Int = 0,  // Which step we're on (0-indexed)
        val params: Map<String, String> = emptyMap()  // Bound parameters
    ) {
        /**
         * Get the current step to execute.
         */
        fun getCurrentStep(): VerifiedRecipe.SuccessfulAction? {
            return recipe.successfulActions.getOrNull(currentStep)
        }

        /**
         * Get the next step after current.
         */
        fun getNextStep(): VerifiedRecipe.SuccessfulAction? {
            return recipe.successfulActions.getOrNull(currentStep + 1)
        }

        /**
         * Check if we're at the last step.
         */
        fun isAtLastStep(): Boolean {
            return currentStep >= recipe.successfulActions.size - 1
        }

        /**
         * Progress to the next step.
         */
        fun advance(): MatchedRecipe {
            return copy(currentStep = minOf(currentStep + 1, recipe.successfulActions.size - 1))
        }
    }

    /**
     * Summary of a saved recipe for context.
     */
    data class RecipeSummary(
        val id: String,
        val intentPattern: String,
        val successCount: Int
    )

    companion object {
        private const val DECISION_PROMPT = """You are a developer using IntelliJ IDEA. Your goal is to accomplish a task by interacting with the UI.

## Task
{{INTENT}}

{{PARSED_PARAMS}}

## Current UI State
{{UI_STATE}}

## Previous Actions
{{ACTION_HISTORY}}

{{RECIPE_SECTION}}

## Available Actions

### Navigation Actions
1. **OpenFile** - Open a file in the editor
   - {"type": "open_file", "path": "src/main/kotlin/UiExecutor.kt"}
    
2. **MoveCaret** - Move cursor to a named symbol (method, class, variable)
   - {"type": "move_caret", "symbol": "executeRecipe"}
    
3. **SelectLines** - Select a range of lines
   - {"type": "select_lines", "start": 10, "end": 20}

### UI Interaction Actions
4. **Click** - Click on a menu item or button by its label
   - {"type": "click", "target": "Rename..."}
     
5. **PressKey** - Press a keyboard key
   - {"type": "press_key", "key": "Enter"}
   - Special key: "context_menu" opens the right-click context menu at the caret position
   - Use: Enter (confirm), Escape (cancel), context_menu (open context menu)

6. **Type** - Type text into a field
   - {"type": "type", "text": "newName", "clearFirst": true, "target": "Return type"}
   - target: optional field label to focus before typing
     
7. **SelectDropdown** - Select a value from a dropdown
   - {"type": "select_dropdown", "target": "Visibility:", "value": "private"}
     
8. **Wait** - Wait for an element to appear
   - {"type": "wait", "elementType": "dialog"}

### Control Actions
9. **Observe** - Just observe the UI state without acting
10. **Complete** - Mark task as successfully completed
11. **Fail** - Mark task as failed (cannot proceed)

## Recommended Workflow

1. **OpenFile** - Navigate to the target file
2. **MoveCaret** or **SelectLines** - Position cursor on target
3. **PressKey("context_menu")** - Open right-click menu
4. **Click("Refactor")** - Navigate to submenu (if needed)
5. **Click("Rename...")** - Click the tool
6. **Wait("text_field")** - Wait for input field
7. **Type** - Enter new value
8. **PressKey("Enter")** - Confirm

## Rules

1. NAVIGATE FIRST: If the task mentions a file, use OpenFile
2. POSITION CURSOR: Use MoveCaret or SelectLines for specific targets
3. USE CONTEXT MENU: PressKey("context_menu") then Click menu items - this is more reliable than keyboard shortcuts
4. VERIFY EACH STEP: Check that the expected result happened
5. VERIFY COMPLETION: Before marking Complete, verify the task succeeded

Return JSON (only JSON, no other text):
{
  "reasoning": "Analyze what you see on screen and explain your decision.",
  "action": {
    "type": "open_file|move_caret|select_lines|click|type|press_key|select_dropdown|wait|observe|complete|fail",
    ...action-specific fields...
  },
  "expected_result": "What you expect to happen",
  "confidence": 0.0-1.0,
  "task_complete": true/false
}

## Confidence Scoring
- 0.9-1.0: Absolutely certain, the element is visible and action is clear
- 0.7-0.9: Very confident, clear evidence supports this action
- 0.5-0.7: Reasonably confident, logical next step
- 0.3-0.5: Low confidence, educated guess
- 0.0-0.3: Very uncertain, fallback action
"""
    }

    /**
     * Make a decision based on the current context.
     *
     * @param context The decision context with intent, observation, and history
     * @return The LLM's decision
     */
    fun decide(context: DecisionContext): Decision {
        val prompt = buildPrompt(context)

        return try {
            val response = llm.chatStructured("You are an expert developer using IntelliJ IDEA.", prompt)
            parseDecision(response)
        } catch (e: Exception) {
            println("  LLMReasoner: LLM call failed, returning Observe action: ${e.message}")
            Decision(
                reasoning = "LLM call failed, observing current state",
                action = Action.Observe,
                expectedResult = "Get fresh UI state",
                confidence = 0.5,
                taskComplete = false
            )
        }
    }

    /**
     * Build the prompt for the LLM.
     */
    private fun buildPrompt(context: DecisionContext): String {
        val paramsSection = context.parsedIntent?.let { parsed ->
            val formatted = parsed.format()
            if (formatted.isNotEmpty()) {
                "## Extracted Parameters\nUse these exact values in your actions!\n$formatted"
            } else ""
        } ?: ""

        return DECISION_PROMPT
            .replace("{{INTENT}}", context.intent)
            .replace("{{PARSED_PARAMS}}", paramsSection)
            .replace("{{UI_STATE}}", formatUIState(context.uiTree, context.profile))
            .replace("{{ACTION_HISTORY}}", formatActionHistory(context.actionHistory))
            .replace("{{RECIPE_SECTION}}", formatRecipeSection(context.matchedRecipe))
    }

    /**
     * Format the recipe section for the prompt.
     * Shows full recipe details with step tracking if a matched recipe exists.
     */
    private fun formatRecipeSection(matchedRecipe: MatchedRecipe?): String {
        if (matchedRecipe == null) {
            return """## No Matching Recipe Found
This task has no saved recipe to reference. You need to explore and discover the steps yourself.
Use the available primitive actions and observe the UI after each action."""
        }

        val recipe = matchedRecipe.recipe
        val currentStep = matchedRecipe.currentStep
        val totalSteps = recipe.successfulActions.size

        val sb = StringBuilder()
        sb.append("## Matched Recipe: ${recipe.id}\n")
        sb.append("Intent Pattern: ${recipe.intentPattern}\n")
        sb.append("Success Count: ${recipe.successCount}\n")
        sb.append("Current Step: ${currentStep + 1} of $totalSteps\n\n")

        sb.append("### Recipe Steps\n")
        sb.append("Use these steps as a REFERENCE. Compare the expected UI state with what you actually see.\n\n")

        for ((index, action) in recipe.successfulActions.withIndex()) {
            val stepNum = index + 1
            val status = when {
                index < currentStep -> "✓ DONE"
                index == currentStep -> "→ CURRENT"
                else -> "  PENDING"
            }

            sb.append("**Step $stepNum** [$status]\n")
            sb.append("Expected UI: ${action.uiStateDescription}\n")
            sb.append("Action: ${formatActionJson(action.action)}\n")
            sb.append("Result: ${action.result}\n\n")
        }

        // Add guidance for the LLM
        sb.append("### Recipe Guidance\n")
        sb.append("- Compare the CURRENT step's 'Expected UI' with what you actually see\n")
        sb.append("- If they match, proceed with the suggested action\n")
        sb.append("- If they differ, ADAPT your approach based on the actual UI\n")
        sb.append("- You may skip steps if already past them, or repeat steps if needed\n")

        return sb.toString()
    }

    /**
     * Format an action JSON for display.
     */
    private fun formatActionJson(action: VerifiedRecipe.ActionJson): String {
        val params = if (action.params.isNotEmpty()) {
            action.params.entries.joinToString(", ") { "${it.key}='${it.value}'" }
        } else {
            ""
        }
        return "${action.type}($params)"
    }

    /**
     * Format the UI state for the prompt using direct tree formatting.
     * No intermediate UIObservation - format UiComponent tree directly.
     */
    private fun formatUIState(uiTree: List<UiComponent>, profile: ApplicationProfile): String {
        return UiTreeFormatter.format(uiTree, profile)
    }

    /**
     * Format the action history for the prompt.
     */
    private fun formatActionHistory(history: List<HistoryEntry>): String {
        if (history.isEmpty()) {
            return "No actions taken yet."
        }

        val sb = StringBuilder()
        sb.append("### Recent Actions\n\n")

        for ((index, entry) in history.takeLast(10).withIndex()) {
            val status = if (entry.success) "✓" else "✗"
            sb.append("$status ${index + 1}. ${describeAction(entry.action)}\n")
            sb.append("   Result: ${entry.result}\n")
        }

        return sb.toString()
    }

    /**
     * Describe an action for the history.
     */
    private fun describeAction(action: Action): String {
        return when (action) {
            // Navigation actions
            is Action.OpenFile -> "Open file '${action.path}'"
            is Action.MoveCaret -> "Move caret to '${action.symbol}'"
            is Action.SelectLines -> "Select lines ${action.start}-${action.end}"
            // UI interaction actions
            is Action.Click -> "Click on '${action.target}'"
            is Action.Type -> "Type '${action.text}' (clearFirst=${action.clearFirst})"
            is Action.PressKey -> "Press ${action.key}"
            is Action.SelectDropdown -> "Select '${action.value}' from '${action.target}'"
            is Action.Wait -> "Wait for ${action.elementType}"
            is Action.UseRecipe -> "Use recipe '${action.recipeId}'"
            is Action.Observe -> "Observe UI state"
            is Action.Complete -> "Task complete"
            is Action.Fail -> "Task failed"
        }
    }

    // ── Structured JSON Parsing with kotlinx.serialization ─────────────────────

    /**
     * DTO for LLM decision response.
     * Uses kotlinx.serialization for type-safe JSON parsing.
     */
    @Serializable
    data class LLMDecisionDto(
        val reasoning: String = "",
        val action: ActionDto? = null,
        @SerialName("expected_result")
        val expectedResult: String = "",
        val confidence: Double = 0.5,
        @SerialName("task_complete")
        val taskComplete: Boolean = false
    )

    /**
     * DTO for action within LLM response.
     * All fields are nullable with defaults for lenient parsing.
     */
    @Serializable
    data class ActionDto(
        val type: String = "",
        // Navigation actions
        val path: String? = null,
        val symbol: String? = null,
        val start: Int? = null,
        val end: Int? = null,
        // UI interaction actions
        val target: String? = null,
        val text: String? = null,
        @SerialName("clearFirst")
        val clearFirst: Boolean? = null,
        val key: String? = null,
        val value: String? = null,
        @SerialName("elementType")
        val elementType: String? = null,
        val timeout: Long? = null,
        @SerialName("recipeId")
        val recipeId: String? = null,
        val params: Map<String, String>? = null
    ) {
        /**
         * Convert DTO to domain Action.
         */
        fun toAction(): Action {
            return when (type.lowercase()) {
                // Navigation actions
                "open_file" -> Action.OpenFile(path ?: "")
                "move_caret" -> Action.MoveCaret(symbol ?: "")
                "select_lines" -> Action.SelectLines(
                    start = start ?: 1,
                    end = end ?: start ?: 1
                )
                // UI interaction actions
                "click" -> Action.Click(target ?: "")
                "type" -> Action.Type(
                    text = text ?: "",
                    clearFirst = clearFirst ?: true,
                    target = target
                )
                "press_key" -> Action.PressKey(key ?: "Enter")
                "select_dropdown" -> Action.SelectDropdown(
                    target = target ?: "",
                    value = value ?: ""
                )
                "wait" -> Action.Wait(
                    elementType = elementType ?: "dialog",
                    timeoutMs = timeout ?: 5000
                )
                "use_recipe" -> Action.UseRecipe(
                    recipeId = recipeId ?: "",
                    params = params ?: emptyMap()
                )
                // Control actions
                "observe" -> Action.Observe
                "complete" -> Action.Complete
                "fail" -> Action.Fail
                // Unknown type defaults to Observe
                else -> Action.Observe
            }
        }
    }

    /**
     * JSON parser configuration for lenient parsing.
     */
    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Parse the LLM response into a Decision using structured JSON parsing.
     */
    private fun parseDecision(response: String): Decision {
        return try {
            // Extract JSON from response (handles markdown code blocks)
            val jsonText = extractJsonFromResponse(response)
            
            // Parse using kotlinx.serialization
            val dto = jsonParser.decodeFromString<LLMDecisionDto>(jsonText)
            
            // Convert DTO to domain model
            Decision(
                reasoning = dto.reasoning.ifBlank { "No reasoning provided" },
                action = dto.action?.toAction() ?: Action.Observe,
                expectedResult = dto.expectedResult,
                confidence = dto.confidence.coerceIn(0.0, 1.0),
                taskComplete = dto.taskComplete
            )
        } catch (e: Exception) {
            println("  LLMReasoner: Failed to parse LLM response: ${e.message}")
            println("  Response preview: ${response.take(500)}")
            
            // Return Observe action on parse failure
            Decision(
                reasoning = "Failed to parse LLM response: ${e.message}",
                action = Action.Observe,
                expectedResult = "Get fresh UI state",
                confidence = 0.5,
                taskComplete = false
            )
        }
    }

    /**
     * Extract JSON from LLM response.
     * Handles both raw JSON and markdown code blocks.
     */
    private fun extractJsonFromResponse(response: String): String {
        // Try to extract from markdown code block first
        val codeBlockRegex = Regex("""```(?:json)?\s*([\s\S]*?)```""")
        codeBlockRegex.find(response)?.let {
            return it.groupValues[1].trim()
        }
        
        // Try to find raw JSON object (outermost braces)
        var braceCount = 0
        var startIndex = -1
        for ((index, char) in response.withIndex()) {
            when (char) {
                '{' -> {
                    if (braceCount == 0) startIndex = index
                    braceCount++
                }
                '}' -> {
                    braceCount--
                    if (braceCount == 0 && startIndex >= 0) {
                        return response.substring(startIndex, index + 1)
                    }
                }
            }
        }
        
        // Return as-is if no JSON structure found
        return response.trim()
    }
}