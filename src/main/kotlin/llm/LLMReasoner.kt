package llm

import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatModel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import model.AgentAction
import perception.UiDelta
import perception.UiDeltaFormatter
import perception.parser.ScopedSnapshotBuilder
import perception.parser.UiComponent
import perception.parser.UiTreeParser
import profile.ApplicationProfile
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
class LLMReasoner(
    private val llm: ChatModel,
) {
    /**
     * A decision made by the LLM.
     */
    data class Decision(
        val reasoning: String,
        val action: AgentAction,
        val expectedResult: String,
        val confidence: Double,
        val taskComplete: Boolean = false,
        /**
         * Optional explanation the LLM fills when the prompt flagged
         * "NO PROGRESS: last action did not change the UI". Helps surface
         * mis-modelled state without reading the full reasoning text.
         */
        val assumptions: String = "",
    )

    /**
     * Context for decision-making.
     * Uses raw UiComponent tree instead of intermediate UIObservation.
     */
    data class DecisionContext(
        val intent: String,
        val uiTree: List<UiComponent>, // Raw UI tree - formatted directly for LLM
        val profile: ApplicationProfile, // Profile for role detection
        val actionHistory: List<HistoryEntry>,
        val matchedRecipe: MatchedRecipe? = null, // Full recipe with step tracking
        val parsedIntent: ParsedIntent? = null, // Extracted parameters from intent
        val uiDelta: UiDelta = UiDelta.INITIAL, // What changed since last action
        /**
         * One-shot nudge injected by the agent loop when it detects a
         * situation the snapshot alone can't convey (e.g. "Observe won't
         * help here, move the caret instead"). Empty when there's nothing
         * to add.
         */
        val extraHint: String = "",
        /**
         * Live editor state (caret, symbol-under-caret, visible window).
         * When non-null it is folded into the compact snapshot and gives
         * the LLM actual source context — without this, EDITOR states show
         * only filename + tabs which is why the agent kept re-Observing.
         */
        val editorCode: ScopedSnapshotBuilder.EditorCode? = null,
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
        val operation: String? = null,
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
            } else {
                ""
            }
        }
    }

    /**
     * Entry in the action history.
     *
     * Carries both what the LLM expected and what the UI actually settled into,
     * plus stable fingerprints of the snapshot before / after the action so
     * downstream prompts can show "expected vs actual" without replaying the
     * full UI tree.
     */
    data class HistoryEntry(
        val action: AgentAction,
        val result: String,
        val success: Boolean,
        val expected: String = "",
        val fingerprintBefore: String = "",
        val fingerprintAfter: String = "",
    )

    /**
     * A matched recipe with full details and step tracking.
     */
    data class MatchedRecipe(
        val recipe: VerifiedRecipe,
        val currentStep: Int = 0, // Which step we're on (0-indexed)
        val params: Map<String, String> = emptyMap(), // Bound parameters
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
        val successCount: Int,
    )

    companion object {
        private const val DECISION_PROMPT = """You are a developer using IntelliJ IDEA. Accomplish the task by interacting with the UI.

## Task
{{INTENT}}

{{PARSED_PARAMS}}

## UI Snapshot
{{UI_SNAPSHOT}}

## What changed
{{UI_DELTA}}
{{EXTRA_HINT}}
## Recent Actions
{{ACTION_HISTORY}}

{{RECIPE_SECTION}}

## Actions

Return a single JSON object. Use lowercase snake_case for "type".

Navigation:
  open_file:     {"type":"open_file","path":"<filename>"}
  move_caret:    {"type":"move_caret","symbol":"<identifier>"}
  select_lines:  {"type":"select_lines","start":<int>,"end":<int>}
  focus_editor:  {"type":"focus_editor"}

Menu & dialog:
  open_context_menu:  {"type":"open_context_menu"}
  close_all_popups:   {"type":"close_all_popups"}
  cancel_dialog:      {"type":"cancel_dialog"}

Click & type:
  click_menu_item:  {"type":"click_menu_item","target":"<menu label>"}
  click_button:     {"type":"click_button","target":"<button label>"}
  type:             {"type":"type","text":"<string>","clearFirst":<bool>,"target":"<field|null>"}
  press_key:        {"type":"press_key","key":"<Enter|Escape|Tab|ArrowDown>"}

Control:
  observe:   {"type":"observe"}
  complete:  {"type":"complete"}
  fail:      {"type":"fail"}

## Refactoring workflow

For rename, change signature, extract, find usages, etc.:

  Step 1: File open?    → No → OpenFile.  Yes → skip.
  Step 2: Caret on symbol? → No → MoveCaret.  Yes → skip.
          (Check Visible Source — if symbol is visible with cursor near it, it's positioned.)
  Step 3: OpenContextMenu
  Step 4: ClickMenuItem("Refactor")
  Step 5: ClickMenuItem("Rename..." / "Change Signature..." / etc.)

For inline rename/extract (Active Context = INLINE_WIDGET):
  → Type(<new_name>, clearFirst=false) → PressKey("Enter"). Do not Observe.

## Rules

1. Skip steps that are already done. If file is open, don't OpenFile. If caret is on the symbol, don't MoveCaret.
2. IDE commands go through context menu: OpenContextMenu → ClickMenuItem. Never use keyboard shortcuts for refactors.
3. If NO PROGRESS in "What changed", do NOT repeat. Try CloseAllPopups or a different action.
4. After refactor, check Visible Source. If the end-state is present, set task_complete=true. Do not re-observe.

Return JSON:
{
  "reasoning": "…",
  "assumptions": "… only if NO PROGRESS",
  "action": { "type": "<action_type>" },
  "expected_result": "…",
  "confidence": 0.0-1.0,
  "task_complete": false
}
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
        val systemPrompt = "You are an expert developer using IntelliJ IDEA."

        return try {
            val response =
                llm.chat(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(prompt),
                )
            val rawText = response.aiMessage().text()
            parseDecision(rawText)
        } catch (e: Exception) {
            println("  LLMReasoner: LLM call failed, returning Observe action: ${e.message}")
            Decision(
                reasoning = "LLM call failed, observing current state",
                action = AgentAction.Observe,
                expectedResult = "Get fresh UI state",
                confidence = 0.5,
                taskComplete = false,
            )
        }
    }

    /**
     * Build the prompt for the LLM.
     */
    private fun buildPrompt(context: DecisionContext): String {
        val paramsSection =
            context.parsedIntent?.let { parsed ->
                val formatted = parsed.format()
                if (formatted.isNotEmpty()) {
                    "## Parameters\nUse these exact values in your actions.\n$formatted"
                } else {
                    ""
                }
            } ?: ""

        val lastActionDescribed =
            context.actionHistory.lastOrNull()?.let { describeAction(it.action) }

        val hintBlock =
            if (context.extraHint.isNotBlank()) {
                "\nHint: ${context.extraHint}\n"
            } else {
                ""
            }

        return DECISION_PROMPT
            .replace("{{INTENT}}", context.intent)
            .replace("{{PARSED_PARAMS}}", paramsSection)
            .replace("{{UI_SNAPSHOT}}", formatUIState(context.uiTree, context.profile, context.editorCode))
            .replace("{{UI_DELTA}}", UiDeltaFormatter.format(context.uiDelta, lastActionDescribed))
            .replace("{{EXTRA_HINT}}", hintBlock)
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
            val status =
                when {
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
        val params =
            if (action.params.isNotEmpty()) {
                action.params.entries.joinToString(", ") { "${it.key}='${it.value}'" }
            } else {
                ""
            }
        return "${action.type}($params)"
    }

    /**
     * Format the UI state for the prompt using [ScopedSnapshotBuilder.buildCompactSnapshot].
     *
     * Token budget: only the topmost window's interactive content is enumerated;
     * background windows are listed by title + type. A deterministic fingerprint
     * is already embedded in the snapshot and drives [UiDelta] downstream.
     *
     * If the compact snapshot produces no visible content but the live tree
     * clearly contains something useful (editor, dialog, popup), we fall back
     * to the verbose [UiTreeFormatter]. Detection is profile-driven — no
     * string matching on class names — so it stays correct across apps.
     */
    private fun formatUIState(
        uiTree: List<UiComponent>,
        profile: ApplicationProfile,
        editorCode: ScopedSnapshotBuilder.EditorCode? = null,
    ): String {
        UiTreeParser.profile = profile

        val snapshot = ScopedSnapshotBuilder.buildCompactSnapshot(uiTree, profile, editorCode)
        return ScopedSnapshotBuilder.formatCompactSnapshot(snapshot)
    }

    /**
     * Format the action history for the prompt.
     *
     * Trimmed to the last 5 entries; each entry shows the expected result
     * alongside the observed result so the LLM can spot drift without us
     * having to replay the full UI tree.
     */
    private fun formatActionHistory(history: List<HistoryEntry>): String {
        if (history.isEmpty()) {
            return "No actions taken yet."
        }

        val sb = StringBuilder()
        val recent = history.takeLast(5)
        for ((index, entry) in recent.withIndex()) {
            val status = if (entry.success) "OK" else "FAIL"
            sb.append("${index + 1}. [$status] ").append(describeAction(entry.action)).append("\n")
            if (entry.expected.isNotBlank()) {
                sb.append("   expected: ").append(entry.expected).append("\n")
            }
            sb.append("   actual:   ").append(entry.result).append("\n")
            if (entry.fingerprintBefore.isNotBlank() && entry.fingerprintAfter.isNotBlank()) {
                val same = entry.fingerprintBefore == entry.fingerprintAfter
                sb.append("   fingerprint: ")
                    .append(entry.fingerprintBefore.take(8))
                    .append(" -> ")
                    .append(entry.fingerprintAfter.take(8))
                    .append(if (same) " (no change)" else "")
                    .append("\n")
            }
        }
        return sb.toString().trimEnd()
    }

    /**
     * Describe an action for the history.
     */
    private fun describeAction(action: AgentAction): String {
        return when (action) {
            is AgentAction.OpenFile -> "Open file '${action.path}'"
            is AgentAction.MoveCaret -> "Move caret to '${action.symbol}'"
            is AgentAction.SelectLines -> "Select lines ${action.start}-${action.end}"
            is AgentAction.Click -> "Click on '${action.target}'"
            is AgentAction.ClickMenuItem -> "Click menu item '${action.target}'"
            is AgentAction.ClickButton -> "Click button '${action.target}'"
            is AgentAction.OpenContextMenu -> "Open context menu"
            is AgentAction.CloseAllPopups -> "Close all popups"
            is AgentAction.Type -> "Type '${action.text}' (clearFirst=${action.clearFirst})"
            is AgentAction.PressKey -> "Press ${action.key}"
            is AgentAction.FocusEditor -> "Focus editor"
            is AgentAction.CancelDialog -> "Cancel dialog (Escape)"
            is AgentAction.Observe -> "Observe UI state"
            is AgentAction.Complete -> "Task complete"
            is AgentAction.Fail -> "Task failed"
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
        val taskComplete: Boolean = false,
        val assumptions: String = "",
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
        val params: Map<String, String>? = null,
        // Extended primitives
        val checked: Boolean? = null,
        val direction: String? = null,
        val amount: Int? = null,
        val predicate: String? = null,
    ) {
        /**
         * Convert DTO to domain AgentAction.
         *
         * Note: unknown types still default to [AgentAction.Observe] here — the
         * LLM can hallucinate type strings and we want to stay alive, not crash
         * the loop. Unknown types from **recipes** fail loudly instead (see
         * [recipe.VerifiedRecipe.ActionJson.toAction]).
         */
        fun toAction(): AgentAction {
            // Normalize the type string before matching. LLMs regularly
            // emit PascalCase / camelCase names that mirror the action
            // headers in the prompt ("MoveCaret", "OpenContextMenu"). The
            // dispatch table uses snake_case, so without this step the
            // request would silently become Observe.
            val normalized = normalizeTypeKey(type)
            return when (normalized) {
                // Navigation actions
                "open_file" -> AgentAction.OpenFile(path ?: "")
                "move_caret" -> AgentAction.MoveCaret(symbol ?: "")
                "select_lines" ->
                    AgentAction.SelectLines(
                        start = start ?: 1,
                        end = end ?: start ?: 1,
                    )
                // UI interaction actions
                "click" -> AgentAction.Click(target ?: "")
                "click_menu_item" -> AgentAction.ClickMenuItem(target ?: "")
                "click_button" -> AgentAction.ClickButton(target ?: "")
                "open_context_menu" -> AgentAction.OpenContextMenu
                "close_all_popups" -> AgentAction.CloseAllPopups
                "type" ->
                    AgentAction.Type(
                        text = text ?: "",
                        clearFirst = clearFirst ?: true,
                        target = target,
                    )
                "press_key" -> AgentAction.PressKey(key ?: "Enter")
                "focus_editor" -> AgentAction.FocusEditor
                "cancel_dialog" -> AgentAction.CancelDialog
                // Control actions
                "observe" -> AgentAction.Observe
                "complete" -> AgentAction.Complete
                "fail" -> AgentAction.Fail
                // Unknown type — log loudly so we never silently swallow a
                // typo again. Past incident: "MoveCaret" (PascalCase) kept
                // looking like Observe for 6+ iterations. Normalization
                // above should catch that now; anything reaching this branch
                // is a genuinely new/misspelled type.
                else -> {
                    System.err.println(
                        "  [ActionDto] WARNING: unknown action type='$type' " +
                            "(normalized='$normalized') — falling back to Observe.",
                    )
                    AgentAction.Observe
                }
            }
        }

        /**
         * Collapse `MoveCaret` / `moveCaret` / `move-caret` / `Move Caret`
         * into `move_caret` so the dispatch table has to only know one
         * canonical form per action.
         *
         * Algorithm: lowercase every upper-case letter and insert `_`
         * before the run of upper-case letters (except at the start), then
         * replace any `-` / space with `_` and collapse repeats.
         */
        private fun normalizeTypeKey(raw: String): String {
            if (raw.isBlank()) return ""
            val sb = StringBuilder(raw.length + 4)
            raw.forEachIndexed { i, c ->
                when {
                    c.isUpperCase() -> {
                        if (i > 0 &&
                            (sb.isNotEmpty() && sb.last() != '_') &&
                            // insert a separator at transitions like `aB` -> `a_b`
                            raw[i - 1].isLetterOrDigit()
                        ) {
                            sb.append('_')
                        }
                        sb.append(c.lowercaseChar())
                    }
                    c == '-' || c == ' ' -> if (sb.isNotEmpty() && sb.last() != '_') sb.append('_')
                    else -> sb.append(c)
                }
            }
            // Collapse any doubled underscores introduced by the transitions.
            return sb.toString().replace(Regex("_+"), "_").trim('_')
        }
    }

    /**
     * JSON parser configuration for lenient parsing.
     */
    private val jsonParser =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

    /**
     * Parse the LLM response into a Decision using structured JSON parsing.
     *
     * Production LLM output is messier than our DTO. Real failures we've hit:
     *   - narrative prose prefix before the JSON
     *   - ```` ```json … ``` ```` code fences
     *   - `<minimax:tool_call>…</minimax:tool_call>` chain-of-thought blocks
     *     that happen to contain stray `{`
     *   - `"action": "observe"` as a bare string instead of a nested object
     *
     * We handle all of these before handing the text to kotlinx.serialization.
     */
    private fun parseDecision(response: String): Decision {
        return try {
            val jsonText = extractJsonFromResponse(response)
                .replace(Regex("""\\(?=")"""), "")

            // Parse as a generic JsonElement first so we can patch shorthand
            // forms (e.g. string action) before binding to ActionDto.
            val raw = jsonParser.parseToJsonElement(jsonText)
            val normalized = normalizeDecisionJson(raw)
            val dto = jsonParser.decodeFromJsonElement<LLMDecisionDto>(normalized)

            Decision(
                reasoning = dto.reasoning.ifBlank { "No reasoning provided" },
                action = dto.action?.toAction() ?: AgentAction.Observe,
                expectedResult = dto.expectedResult,
                confidence = dto.confidence.coerceIn(0.0, 1.0),
                taskComplete = dto.taskComplete,
                assumptions = dto.assumptions,
            )
        } catch (e: Exception) {
            println("  LLMReasoner: Failed to parse LLM response: ${e.message}")
            println("  Response preview: ${response.take(500)}")

            Decision(
                reasoning = "Failed to parse LLM response: ${e.message}",
                action = AgentAction.Observe,
                expectedResult = "Get fresh UI state",
                confidence = 0.5,
                taskComplete = false,
            )
        }
    }

    /**
     * Patch common LLM shorthand so the payload matches [LLMDecisionDto].
     *
     * Handles four cases that small local models (e.g. 8B LLMs) regularly emit:
     *   - `"action": "<type>"` → `"action": {"type":"<type>"}`     (string shorthand)
     *   - `"action": [ {...} ]` → `"action": { ... }`              (singleton array)
     *   - `"action": [ {...}, {...} ]` → `"action": {first}`        (multi-element: model
     *     treats `action` as a plan, executor executes one step at a time)
     *   - `"assumptions": [...]` → `"assumptions": "..."`          (string field rendered as array)
     *
     * The action-wrapping is especially common with small models because the
     * JSON examples in their training data often use lists. The multi-element
     * case is rarer but seen — the model emits a sequence of actions
     * anticipating a planner executor, when ours only handles one at a time.
     * We pick the first object and ignore the rest; the agent loop will
     * emit the next action in the next iteration. Without the lenient
     * normalisation the parse fails and the agent falls through to Observe,
     * which burns iterations and budget.
     */
    private fun normalizeDecisionJson(element: JsonElement): JsonElement {
        if (element !is JsonObject) return element
        val action = element["action"]
        val assumptions = element["assumptions"]

        val actionStringShorthand =
            action is JsonPrimitive && action.isString &&
                !tryParseJsonString(action.content).isStringWithEmbeddedJson()
        val actionStringifiedJson =
            action is JsonPrimitive && action.isString &&
                tryParseJsonString(action.content).isStringWithEmbeddedJson()
        val actionArray =
            action is JsonArray && action.isNotEmpty() && action.all { it is JsonObject }
        val assumptionsNeedsFix = assumptions is JsonArray || assumptions is JsonObject

        if (!actionStringShorthand && !actionStringifiedJson && !actionArray && !assumptionsNeedsFix) {
            return element
        }

        return buildJsonObject {
            element.entries.forEach { (k, v) ->
                when {
                    // action shorthand: "action": "<type>" → {"type":"<type>"}
                    k == "action" && actionStringShorthand -> {
                        val typeString = (v as JsonPrimitive).content
                        put(k, buildJsonObject { put("type", JsonPrimitive(typeString)) })
                    }
                    // action as stringified JSON: "action": "{\"type\": ...}" → parsed
                    // Small local models sometimes wrap the action object as an
                    // escaped JSON string instead of emitting it directly. Parse
                    // the inner JSON and use it as the action value.
                    k == "action" && actionStringifiedJson -> {
                        val parsed =
                            (
                                tryParseJsonString((v as JsonPrimitive).content)
                                    as ParseResult.Success
                            ).element
                        put(k, parsed)
                    }
                    // action as array (one or more) — keep the first, drop the rest
                    k == "action" && actionArray -> {
                        put(k, (v as JsonArray)[0])
                    }
                    // assumptions as array/object → flatten to string
                    k == "assumptions" && assumptionsNeedsFix -> {
                        put(k, JsonPrimitive(flattenToString(v)))
                    }
                    else -> put(k, v)
                }
            }
        }
    }

    private sealed class ParseResult {
        data class Success(val element: JsonElement) : ParseResult()

        data object NotJson : ParseResult()
    }

    private fun tryParseJsonString(s: String): ParseResult {
        val trimmed = s.trim()
        if (trimmed.isEmpty()) return ParseResult.NotJson
        if (trimmed[0] != '{' && trimmed[0] != '[') return ParseResult.NotJson
        return try {
            ParseResult.Success(jsonParser.parseToJsonElement(trimmed))
        } catch (_: Exception) {
            ParseResult.NotJson
        }
    }

    private fun ParseResult?.isStringWithEmbeddedJson(): Boolean =
        this is ParseResult.Success && (
            this.element is JsonObject || (
                this.element is JsonArray &&
                    (this.element as JsonArray).isNotEmpty() &&
                    (this.element as JsonArray).all { it is JsonObject }
            )
        )

    private fun flattenToString(element: JsonElement): String =
        when (element) {
            is JsonPrimitive -> element.content
            is JsonArray ->
                element
                    .map { flattenToString(it) }
                    .filter { it.isNotBlank() }
                    .joinToString("; ")
            is JsonObject -> element.toString()
        }

    /**
     * Extract the JSON decision object from an LLM response.
     *
     * Strategy:
     *   1. Strip ```` ```json … ``` ```` fences. Prefer the last fenced block
     *      because reasoning models often emit scratch JSON early and the
     *      final answer last.
     *   2. Strip pseudo-XML tool-call blocks (e.g. `<minimax:tool_call>…`)
     *      which sometimes contain stray braces that confuse brace matching.
     *   3. Walk the string for balanced top-level `{…}` objects and return
     *      the **last** one that looks like a decision (contains `"action"`
     *      or `"reasoning"`). Falling back to the last balanced object, or
     *      the trimmed input if no braces are found.
     */
    private fun extractJsonFromResponse(response: String): String {
        // 1. Prefer the last fenced block — if multiple fences are present
        //    the useful one is almost always the last.
        val fenceRegex = Regex("""```(?:json)?\s*([\s\S]*?)```""")
        val fenceMatches = fenceRegex.findAll(response).toList()
        if (fenceMatches.isNotEmpty()) {
            // Try each fence starting from the last; pick the first one that
            // actually contains a JSON object with "action" or "reasoning".
            for (m in fenceMatches.asReversed()) {
                val body = m.groupValues[1].trim()
                if (body.contains("\"action\"") || body.contains("\"reasoning\"")) {
                    return body
                }
            }
            return fenceMatches.last().groupValues[1].trim()
        }

        // 2a. Some reasoning models (MiniMax, certain tool-use fine-tunes)
        //     emit NO JSON at all and instead write a pseudo-XML tool call:
        //
        //       <minimax:tool_call>
        //         <invoke name="Observe">
        //           <parameter name="foo">bar</parameter>
        //         </invoke>
        //       </minimax:tool_call>
        //
        //     Rather than failing parse, synthesize an equivalent decision
        //     object from the invoke tag. The normalizer in toAction()
        //     handles the PascalCase type name (`Observe` -> `observe`).
        val invokeRegex = Regex("""<invoke\s+name=["']([^"']+)["']\s*>([\s\S]*?)</invoke>""")
        val invokeMatch = invokeRegex.find(response)
        if (invokeMatch != null) {
            val typeName = invokeMatch.groupValues[1]
            val body = invokeMatch.groupValues[2]
            val paramRegex =
                Regex("""<parameter\s+name=["']([^"']+)["']\s*>([\s\S]*?)</parameter>""")
            val paramsSb = StringBuilder()
            for (pm in paramRegex.findAll(body)) {
                val k = pm.groupValues[1].replace("\"", "\\\"")
                val v = pm.groupValues[2].trim().replace("\"", "\\\"")
                paramsSb.append(", \"").append(k).append("\": \"").append(v).append("\"")
            }
            val synthesized =
                """{"reasoning":"Recovered from tool-call shorthand",""" +
                    """"action":{"type":"$typeName"$paramsSb},""" +
                    """"expected_result":"","confidence":0.5,"task_complete":false}"""
            return synthesized
        }

        // 2b. Scrub tool-call scaffolding that reasoning models sometimes emit.
        val cleaned =
            response
                .replace(Regex("""<[^/\s>][^>]*:tool_call[^>]*>[\s\S]*?</[^>]+>"""), " ")
                .replace(Regex("""<invoke[^>]*>[\s\S]*?</invoke>"""), " ")

        // 3. Collect every balanced top-level {…} object.
        val objects = mutableListOf<String>()
        var braceCount = 0
        var startIndex = -1
        for ((index, char) in cleaned.withIndex()) {
            when (char) {
                '{' -> {
                    if (braceCount == 0) startIndex = index
                    braceCount++
                }
                '}' -> {
                    braceCount--
                    if (braceCount == 0 && startIndex >= 0) {
                        objects += cleaned.substring(startIndex, index + 1)
                        startIndex = -1
                    }
                    if (braceCount < 0) braceCount = 0 // defensive reset
                }
            }
        }

        if (objects.isNotEmpty()) {
            val decisionLike =
                objects.lastOrNull { it.contains("\"action\"") || it.contains("\"reasoning\"") }
            if (decisionLike != null) return decisionLike
            return objects.last()
        }

        return cleaned.trim()
    }

    /** Best-effort model name for logging — langchain4j [ChatModel] hides this. */
    private fun describeModel(): String = llm::class.simpleName ?: "unknown"
}
