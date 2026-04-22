package llm

import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatModel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import model.AgentAction
import perception.UiDelta
import perception.UiDeltaFormatter
import perception.UiTreeFormatter
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
    private val promptLogger: PromptLogger? = null,
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
        /**
         * Prompt contract — sections appear in a fixed order so the LLM can
         * rely on positional cues, and deltas between runs are easy to diff.
         */
        private const val DECISION_PROMPT = """You are a developer using IntelliJ IDEA. Your goal is to accomplish a task by interacting with the UI.

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

## Allowed Actions (schema)

All examples below use `<placeholder: type — hint>`. Replace each placeholder
with a concrete value derived from the UI Snapshot. Do NOT copy placeholder
text literally.

**Type-string convention**: the `"type"` field MUST be the **lowercase
snake_case** string shown in each schema (e.g. `"open_file"`, `"move_caret"`,
`"open_context_menu"`, `"click_menu_item"`). The bold header next to each
entry (e.g. "**MoveCaret**") is a human-readable label for your reasoning —
do NOT put it in the JSON.

**Output shape (single JSON object, no prose, no XML tool-call tags)**:

```
{
  "reasoning": "…",
  "action": { "type": "move_caret", "symbol": "compute" },
  "expected_result": "…",
  "confidence": 0.85,
  "task_complete": false
}
```

### Navigation
- **OpenFile** — `{"type": "open_file", "path": <string: filename only, e.g. the exact tab title>}`
- **MoveCaret** — `{"type": "move_caret", "symbol": <string: identifier text visible in the editor>}`
- **SelectLines** — `{"type": "select_lines", "start": <int: 1-based line>, "end": <int: 1-based line>}`
- **FocusEditor** — `{"type": "focus_editor"}`

### Menu & dialog control
- **OpenContextMenu** — `{"type": "open_context_menu"}` (right-click at caret; the ONLY way to reach Refactor / Go To / Generate / etc.)
- **CloseAllPopups** — `{"type": "close_all_popups"}` (recovery: Escape-drain all popups/dialogs)
- **CancelDialog** — `{"type": "cancel_dialog"}` (close topmost dialog only, verified)

### UI interaction
- **ClickMenuItem** — `{"type": "click_menu_item", "target": <string: exact label of a menu item from Active Window>}`
- **ClickButton** — `{"type": "click_button", "target": <string: exact label of a dialog button>}`
- **Click** — `{"type": "click", "target": <string: label>}` (generic; prefer ClickMenuItem or ClickButton when you know which it is)
- **Type** — `{"type": "type", "text": <string: what to type>, "clearFirst": <bool>, "target": <string|null: field label>}`
- **SelectDropdown** — `{"type": "select_dropdown", "target": <string: dropdown label>, "value": <string: option label>}`
- **SetCheckbox** — `{"type": "set_checkbox", "target": <string: checkbox label>, "checked": <bool>}`
- **Scroll** — `{"type": "scroll", "direction": <"up"|"down"|"page_up"|"page_down"|"home"|"end">, "target": <string: list/tree label or "">, "amount": <int>}`
- **PressKey** — `{"type": "press_key", "key": <string: single key name, e.g. "Enter"/"Escape"/"Tab"/"ArrowDown">}`
  Use for **single keys only** — Enter to commit a dialog/template, Escape to cancel, Tab/Arrow to navigate list items. **Do NOT use for keyboard shortcuts** like `"Shift+F6"` or `"Ctrl+R"`; use the context menu instead.
- **Wait** — `{"type": "wait", "elementType": <"dialog"|"popup"|"textfield">, "timeout": <int: ms, optional>}`

### Verification & control
- **Verify** — `{"type": "verify", "predicate": <string>}`
  Predicate forms:
  - UI-surface: `dialog_open:<title>`, `popup_open`, `no_popup`,
    `context=<CTX>`, `button_enabled:<label>`, `field_present:<label>`,
    `focused:<label>`.
  - Source content (checked against the current Visible Source window):
    `source_contains:<text>` — true iff the visible source contains `<text>`.
    `source_absent:<text>` — true iff the visible source does NOT contain `<text>`.
    `line_contains:<line>:<text>` — true iff the given 1-based line
    (must be inside the Visible Source window) contains `<text>`.
  - Full-document content (scans the WHOLE open file, not just the
    visible window — use these when the change you want to confirm is
    far from the caret, e.g. after Change Signature the caret stays on
    a call site but the modified declaration is hundreds of lines
    away):
    `file_contains:<text>` — true iff the entire file contains `<text>`.
    `file_absent:<text>`   — true iff the entire file does NOT contain `<text>`.
  These are the tools for confirming that a code change actually landed
  (e.g. after a rename or change-signature refactor). Prefer `file_*`
  over `source_*` when the edit may be off-screen.
- **Observe** — `{"type": "observe"}` (re-observe without acting)
- **Complete** — `{"type": "complete"}`
- **Fail** — `{"type": "fail"}`

## Preferred Flows

IDE commands are reached via the **right-click context menu**, not shortcuts or
action IDs. The Active Window after `OpenContextMenu` lists the visible menu
items — click the one you want by its exact label.

- **Rename a symbol** (method / variable / class):
  `MoveCaret(<old_name>)` →
  `OpenContextMenu` →
  `ClickMenuItem("Refactor")` (opens submenu) →
  `ClickMenuItem("Rename...")` →
  INLINE_WIDGET appears (or a Rename dialog) →
  `Type(<new_name>, clearFirst=false)` →
  `PressKey("Enter")` (commits — Active Context returns to EDITOR) →
  **Confirm outcome** with Verify on the new Visible Source:
  `Verify("source_contains:<new_name>")` AND
  `Verify("source_absent:<old_name>")` →
  set `task_complete: true`.

- **Change Signature of a method** (e.g. change visibility, add/remove params):
  `MoveCaret(<method_name>)` →
  `OpenContextMenu` →
  `ClickMenuItem("Refactor")` →
  `ClickMenuItem("Change Signature...")` →
  A "Change Signature" DIALOG opens. Operate on it:
    - change visibility → `SelectDropdown("Visibility", "<Private|Package-private|Protected|Public>")`
      (exact label shown in the dialog's visibility combobox).
    - add param → use the dialog's buttons / table as shown in Active Window.
  →  `ClickButton("Refactor")` to commit (if the dialog is still open —
     a `SelectDropdown` may auto-commit on some IntelliJ versions and
     the dialog closes on its own). Once Active Context returns to
     EDITOR the caret stays on the call site, NOT the declaration, so
     the modified signature is typically OFF-SCREEN. Confirm with the
     full-document predicates:
     `Verify("file_contains:<expected_new_signature_fragment>")` AND
     `Verify("file_absent:<old_signature_fragment>")`.
  → `task_complete: true`.
  Example (change `private <ret> foo(...)` to package-private):
    `Verify("file_absent:private <ret> foo")` then
    `Verify("file_contains:<ret> foo")` → complete.

- **Go to method declaration** (only needed if a task REQUIRES the caret be on
  the declaration — usually it doesn't; see rule 12):
  `MoveCaret(<symbol>)` →
  `OpenContextMenu` →
  `ClickMenuItem("Go To")` →
  `ClickMenuItem("Declaration or Usages")`.

- **Find usages of a symbol**:
  `MoveCaret(<symbol>)` →
  `OpenContextMenu` →
  `ClickMenuItem("Find Usages")`.

- **Reformat a file**:
  `OpenContextMenu` →
  `ClickMenuItem("Reformat Code")`.

- **Recover from a stacked popup dead-end**: `CloseAllPopups`, then resume.

## Rules

1. NAVIGATE FIRST: if the task names a file, start with OpenFile.
2. POSITION CURSOR: use MoveCaret or SelectLines to place the caret on the target symbol/range before triggering refactors. The context menu acts on whatever the caret is currently on.
3. USE THE CONTEXT MENU FOR IDE COMMANDS: refactors, "Go To", "Find Usages", "Reformat Code", "Generate", etc. are all reached via `OpenContextMenu` → `ClickMenuItem(<label>)`. NEVER synthesise keyboard shortcuts with PressKey.
4. READ "What changed": if it says NO PROGRESS, your last action did nothing — do NOT repeat it. Pick a different action, use Verify to check assumptions, or CloseAllPopups to recover.
5. ACT ON THE ACTIVE WINDOW ONLY: only the topmost window's items are listed; background windows are titles only. After `OpenContextMenu`, the menu items are listed in the Active Window — use `ClickMenuItem` on an EXACT label from that list.
6. RECOGNISE COMPLETION FROM THE SNAPSHOT: the `Visible Source` block shows
   the live source around the caret. After a refactor / edit, READ IT. If
   the observable end-state the task described is already present there
   (e.g. the new identifier appears where the old one used to be), the task
   is done. Do NOT spin another `Observe` or `MoveCaret` "to check" — the
   snapshot already contains the answer. Use `Verify` with a
   `source_contains` / `source_absent` predicate to confirm and then set
   `task_complete: true` in the same turn.
7. NEVER pick Observe when Active Context is EDITOR and no popup/dialog is open — Observe only refreshes perception. Use OpenFile, MoveCaret, SelectLines, OpenContextMenu, or Verify instead.
8. INLINE WIDGET flow: when Active Context is INLINE_WIDGET (an in-place rename/extract template is live), the old identifier is ALREADY selected in the editor. Do NOT click anything and do NOT set clearFirst=true — just `Type(<new_name>, clearFirst=false)` then `PressKey("Enter")` to commit, or `PressKey("Escape")` to cancel. Never Observe in this state: typing is the only way to make progress.
9. PICK THE SPECIFIC CLICK VARIANT: in POPUP_MENU/POPUP_CHOOSER use ClickMenuItem; in DIALOG use ClickButton. Use generic Click only when unsure.
10. SUBMENUS: some context-menu entries open nested submenus (e.g. "Refactor" → "Rename...", "Go To" → "Declaration or Usages"). After `ClickMenuItem` on a parent, the submenu's items appear in the next Active Window — click the child item there.
11. DON'T CHASE THE OLD IDENTIFIER AFTER A RENAME: once a rename commits, the
    old name is GONE from the file. A subsequent `MoveCaret("<old_name>")` will
    (correctly) fail with "symbol not found". That failure is EVIDENCE the
    rename worked — treat it as a completion signal, not a problem to retry.
12. REFACTORINGS RESOLVE SYMBOLS AUTOMATICALLY. When the caret is on ANY
    occurrence of a method/variable — call site OR declaration — IntelliJ's
    `Refactor → Rename / Change Signature / Find Usages / ...` act on the
    symbol itself, not on that specific textual occurrence. You do NOT need
    to navigate to the method declaration first. `MoveCaret("<method>")` lands
    on the first textual occurrence (often a call site); that is fine. Go
    straight to `OpenContextMenu` → `ClickMenuItem("Refactor")` next.
13. "CARET ALREADY ON …" IS A GO-AHEAD SIGNAL. If the previous action's
    result says "Caret was already on '<symbol>'", the precondition for the
    next step is satisfied. Do NOT re-issue `MoveCaret` — advance to the
    next step of the flow (`OpenContextMenu`, the refactor, etc.). Repeating
    `MoveCaret` on the same symbol is a LOOP.

## Task completion rubric

A task is complete when the Visible Source / UI state shows the observable
end-state described by the Task. Concrete patterns:

- **Rename refactor** (`rename X to Y`):
  Completion signals (any is sufficient):
  (a) `Visible Source` contains `Y` at a location that used to contain `X`
      (inline widget has already closed — Active Context back to EDITOR).
  (b) `MoveCaret("X")` returns "Symbol not found" AND `Visible Source`
      contains `Y`.
  Decision: emit one `Verify("source_contains:Y")` (and optionally
  `Verify("source_absent:X")`); if it succeeds, the very next decision sets
  `task_complete: true` with action `{"type": "complete"}`. Do NOT keep
  re-observing or re-navigating.

- **File open / navigate**:
  Completion signal: the breadcrumb / Editor line of the snapshot names the
  target file and the caret is positioned as requested. Emit `Complete`.

- **Change Signature refactor** (visibility / parameter / return-type change):
  The Change Signature dialog CLOSING is the primary success signal
  (`What changed` shows `closed: "Change Signature" (DIALOG)` and Active
  Context is back to EDITOR). The caret typically stays on the call
  site you triggered from — the modified method DECLARATION is usually
  OFF-SCREEN, so `Visible Source` may not show it. Use the full-document
  predicates: `Verify("file_contains:<new-signature-fragment>")` AND/OR
  `Verify("file_absent:<old-signature-fragment>")`. If either confirms,
  set `task_complete: true` on the next turn. Do NOT re-navigate or
  re-trigger the refactor.

- **Edit that produces a dialog** (e.g. Extract Method dialog):
  Completion signal: the dialog closes with success and `Visible Source`
  (or `file_contains` for off-screen edits) contains the expected new
  fragment.

General principle — **"dialog closed" + "file_contains check passed" ≡
task done** for any refactor. Once `What changed` shows the refactor
dialog closing back to EDITOR, the edit has already been applied; your
only remaining job is to confirm with a single `Verify` and emit
`Complete`. Re-issuing `MoveCaret` / re-opening the context menu at
this point is stagnation.

If you are uncertain whether the end-state holds, issue ONE targeted
`Verify` — never more than two in a row. Repeated `Verify` with the same
predicate is stagnation.

Return JSON (only JSON, no other text):
{
  "reasoning": "Analyze what you see and explain your decision.",
  "assumptions": "Fill this ONLY if 'What changed' said NO PROGRESS — explain why the last action failed.",
  "action": {
    "type": "<one of the action types above>",
    "...": "...action-specific fields..."
  },
  "expected_result": "What you expect to happen",
  "confidence": 0.0-1.0,
  "task_complete": true/false
}

## Confidence
- 0.9-1.0: Element is visible and action is clearly correct.
- 0.7-0.9: Clear evidence supports this action.
- 0.5-0.7: Logical next step.
- 0.3-0.5: Educated guess.
- 0.0-0.3: Fallback.
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
            val started = System.currentTimeMillis()
            val response =
                llm.chat(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(prompt),
                )
            val durationMs = System.currentTimeMillis() - started
            val rawText = response.aiMessage().text()
            promptLogger?.log(
                context =
                    PromptLogger.LogContext(
                        caller = "LLMReasoner.decide",
                        intent = context.intent,
                        iteration = context.actionHistory.size + 1,
                    ),
                model = describeModel(),
                messages = PromptLogger.messages(systemPrompt, prompt),
                rawResponse = rawText,
                parsedResponse = rawText,
                durationMs = durationMs,
            )
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
        // Ensure the parser applies the same semantic profile as the snapshot.
        UiTreeParser.profile = profile

        val snapshot = ScopedSnapshotBuilder.buildCompactSnapshot(uiTree, profile, editorCode)
        val rendered = ScopedSnapshotBuilder.formatCompactSnapshot(snapshot)

        if (shouldUseFullTreeFallback(snapshot, uiTree, profile)) {
            return UiTreeFormatter.format(uiTree, profile)
        }

        return rendered
    }

    /**
     * Fall back to the full-tree formatter only when the compact view is
     * clearly under-representing the live UI.
     *
     * Uses [ApplicationProfile] predicates (not class-name string matches) so
     * misclassification is surfaced via the profile rather than hidden here.
     */
    private fun shouldUseFullTreeFallback(
        snapshot: ScopedSnapshotBuilder.CompactSnapshot,
        uiTree: List<UiComponent>,
        profile: ApplicationProfile,
    ): Boolean {
        val all = UiTreeParser.flatten(uiTree)
        val activeWindow = snapshot.activeWindow
        val activeIsEmpty =
            activeWindow.fields.isEmpty() &&
                activeWindow.buttons.isEmpty() &&
                activeWindow.menuItems.isEmpty()

        val hasEditor = all.any { profile.isEditor(it.cls) }
        val hasDialog = all.any { profile.isDialog(it.cls) }
        val hasPopup = all.any { profile.isPopupWindow(it.cls) }

        val missedEditor = hasEditor && snapshot.editor == null
        val missedDialog =
            hasDialog &&
                snapshot.windowStack.none { it.type == ScopedSnapshotBuilder.ActiveContext.DIALOG }
        val missedPopup =
            hasPopup &&
                snapshot.windowStack.none {
                    it.type == ScopedSnapshotBuilder.ActiveContext.POPUP_MENU ||
                        it.type == ScopedSnapshotBuilder.ActiveContext.POPUP_CHOOSER ||
                        it.type == ScopedSnapshotBuilder.ActiveContext.INLINE_WIDGET
                }

        return (activeIsEmpty && (hasDialog || hasPopup)) || missedEditor || missedDialog || missedPopup
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
            // Navigation actions
            is AgentAction.OpenFile -> "Open file '${action.path}'"
            is AgentAction.MoveCaret -> "Move caret to '${action.symbol}'"
            is AgentAction.SelectLines -> "Select lines ${action.start}-${action.end}"
            // UI interaction actions
            is AgentAction.Click -> "Click on '${action.target}'"
            is AgentAction.ClickMenuItem -> "Click menu item '${action.target}'"
            is AgentAction.ClickButton -> "Click button '${action.target}'"
            is AgentAction.OpenContextMenu -> "Open context menu"
            is AgentAction.CloseAllPopups -> "Close all popups"
            is AgentAction.Type -> "Type '${action.text}' (clearFirst=${action.clearFirst})"
            is AgentAction.PressKey -> "Press ${action.key}"
            is AgentAction.SelectDropdown -> "Select '${action.value}' from '${action.target}'"
            is AgentAction.Wait -> "Wait for ${action.elementType}"
            is AgentAction.UseRecipe -> "Use recipe '${action.recipeId}'"
            is AgentAction.FocusEditor -> "Focus editor"
            is AgentAction.CancelDialog -> "Cancel dialog (Escape)"
            is AgentAction.SetCheckbox -> "Set checkbox '${action.target}' = ${action.checked}"
            is AgentAction.Scroll ->
                "Scroll ${action.direction}" +
                    (if (action.target.isNotBlank()) " on '${action.target}'" else "") +
                    " x${action.amount}"
            is AgentAction.Verify -> "Verify '${action.predicate}'"
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
                "select_dropdown" ->
                    AgentAction.SelectDropdown(
                        target = target ?: "",
                        value = value ?: "",
                    )
                "wait" ->
                    AgentAction.Wait(
                        elementType = elementType ?: "dialog",
                        timeoutMs = timeout ?: 5000,
                    )
                "use_recipe" ->
                    AgentAction.UseRecipe(
                        recipeId = recipeId ?: "",
                        params = params ?: emptyMap(),
                    )
                "focus_editor" -> AgentAction.FocusEditor
                "cancel_dialog" -> AgentAction.CancelDialog
                "set_checkbox" ->
                    AgentAction.SetCheckbox(
                        target = target ?: "",
                        checked = checked ?: true,
                    )
                "scroll" ->
                    AgentAction.Scroll(
                        direction = direction ?: "down",
                        target = target ?: "",
                        amount = amount ?: 1,
                    )
                "verify" -> AgentAction.Verify(predicate = predicate ?: (target ?: ""))
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
     * Currently only normalises `"action": "<type>"` → `"action": {"type":"<type>"}`.
     * Everything else passes through unchanged.
     */
    private fun normalizeDecisionJson(element: JsonElement): JsonElement {
        if (element !is JsonObject) return element
        val action = element["action"]
        if (action !is JsonPrimitive || !action.isString) return element

        val typeString = action.content
        val patched =
            buildJsonObject {
                element.entries.forEach { (k, v) ->
                    if (k == "action") {
                        put(
                            "action",
                            buildJsonObject { put("type", JsonPrimitive(typeString)) },
                        )
                    } else {
                        put(k, v)
                    }
                }
            }
        return patched
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
