package agent

import dev.langchain4j.model.chat.ChatModel
import execution.ActionGenerator
import execution.UiExecutor
import llm.LLMReasoner
import llm.LLMReasoner.Decision
import llm.LLMReasoner.DecisionContext
import llm.LLMReasoner.HistoryEntry
import llm.LLMReasoner.MatchedRecipe
import llm.PromptLogger
import model.AgentAction
import perception.UiDelta
import perception.UiTreeFormatter
import perception.parser.ScopedSnapshotBuilder
import perception.parser.UiComponent
import perception.parser.UiTreeParser
import profile.ApplicationProfile
import recipe.RecipeRegistry
import recipe.VerifiedRecipe

/**
 * UI Agent - The main orchestrator for LLM-centric UI automation.
 *
 * This agent implements the Observe-Reason-Act loop:
 * 1. Observe: Capture current UI state
 * 2. Reason: LLM decides next action based on intent and observation
 * 3. Act: Execute the decided action
 * 4. Repeat until task complete or failure
 *
 * Key principles:
 * - LLM is the brain, making decisions at each step
 * - UI state is observed, not assumed
 * - Recipes are references, not blind scripts
 * - Successful executions are saved as verified recipes
 *
 * @param promptLogDir Root directory for [PromptLogger] output. One sub-directory per
 *                     [execute] call keeps sessions separate and easy to diff.
 *                     Set to `null` to disable prompt logging entirely.
 */
class UiAgent(
    private val llm: ChatModel,
    private val profile: ApplicationProfile,
    private val executor: UiExecutor,
    private val uiTreeProvider: () -> List<UiComponent>,
    private val promptLogDir: String? = "sent_prompt",
) {
    // Rebuilt for each [execute] call so every run gets its own session folder.
    private var promptLogger: PromptLogger? = null
    private var reasoner: LLMReasoner = LLMReasoner(llm)
    private var actionGenerator: ActionGenerator =
        ActionGenerator(executor, profile, uiTreeProvider, llm)
    private val recipeRegistry = RecipeRegistry()

    /**
     * Result of executing an intent.
     */
    data class ExecutionResult(
        val success: Boolean,
        val message: String,
        val actionsTaken: Int = 0,
        val recipeSaved: Boolean = false,
        val recipeId: String? = null,
    )

    /**
     * State carried through the execution loop.
     */
    private data class ExecutionState(
        val intent: String,
        val iteration: Int = 0,
        val actionHistory: MutableList<HistoryEntry> = mutableListOf(),
        val uiStateHistory: MutableList<String> = mutableListOf(),
        val lastDecision: Decision? = null,
        val failed: Boolean = false,
        val complete: Boolean = false,
        val matchedRecipe: MatchedRecipe? = null,
        val initialDocumentText: String? = null,
        /** Snapshot captured at the start of the previous iteration, for delta computation. */
        val previousSnapshot: ScopedSnapshotBuilder.CompactSnapshot? = null,
        /** Stable hash from [previousSnapshot]; duplicated for fast comparisons. */
        val previousFingerprint: String = "",
        /**
         * Number of consecutive iterations whose pre-action fingerprint matches
         * the one before — the primary stagnation signal.
         */
        val sameFingerprintStreak: Int = 0,
        /** Consecutive duplicate actions with no intervening UI change. */
        val sameActionStreak: Int = 0,
        /** One-shot hint for the next LLM prompt. Consumed and cleared after use. */
        val nextHint: String = "",
    )

    companion object {
        private const val MAX_ITERATIONS = 30
        private const val OBSERVE_DELAY_MS = 500L

        /** Force an Observe after this many consecutive same-fingerprint iterations. */
        private const val STAGNATION_WARN_STREAK = 2

        /**
         * Fail the loop after this many consecutive "same action + same fingerprint"
         * iterations. Stricter than the previous 3-in-a-row rule because we now
         * know for sure the UI didn't react.
         */
        private const val STAGNATION_FAIL_STREAK = 3
    }

    /**
     * Execute an intent using the Observe-Reason-Act loop.
     *
     * IMPORTANT: We do NOT use regex-based intent parsing. The LLM handles
     * natural language intent parsing as part of its decision-making process.
     * Regex fails on unexpected phrasing; LLM handles variation naturally.
     *
     * @param intent The user's intent (e.g., "rename method foo to bar")
     * @return The result of the execution
     */
    fun execute(intent: String): ExecutionResult {
        println("\n=== BRAIN AGENT ===")
        println("Intent: $intent")

        // Start a new prompt-logging session per run so every LLM call for this
        // intent lands under `sent_prompt/session_<ts>/NNN_<caller>.json`.
        promptLogger = promptLogDir?.let { PromptLogger(baseDir = it) }
        reasoner = LLMReasoner(llm, promptLogger)
        actionGenerator = ActionGenerator(executor, profile, uiTreeProvider, llm, promptLogger)
        promptLogger?.let { println("  Prompt log session: ${it.sessionDir.path}") }

        // Try to find a matching recipe
        val matchedRecipe = findMatchingRecipe(intent)
        if (matchedRecipe != null) {
            println("  Found matching recipe: ${matchedRecipe.recipe.id}")
            println("  Recipe has ${matchedRecipe.recipe.successfulActions.size} steps")
        } else {
            println("  No matching recipe found - will explore from scratch")
        }

        // Capture initial document text for diff-based completion detection
        val initialDocumentText = executor.getDocumentText()
        var state =
            ExecutionState(
                intent = intent,
                matchedRecipe = matchedRecipe,
                initialDocumentText = initialDocumentText,
            )

        // Make sure the tree parser and snapshot builder both use the same
        // semantic profile — otherwise structural decisions (isDialog, role, …)
        // disagree between parsing and reasoning.
        UiTreeParser.profile = profile

        // Main execution loop
        while (state.iteration < MAX_ITERATIONS && !state.failed && !state.complete) {
            state = state.copy(iteration = state.iteration + 1)

            println("\n--- Iteration ${state.iteration} ---")

            // 1. OBSERVE: raw tree + compact snapshot + delta vs previous iter.
            val uiTree = uiTreeProvider.invoke()
            // Live editor state is a thin JS round-trip — normally cheap, but it
            // dispatches onto IntelliJ's EDT via `invokeAndWait`. When a modal
            // dialog is on top (Change Signature, Find Usages preview, …) that
            // EDT is inside a nested event loop which on macOS doesn't reliably
            // pump non-EDT `invokeAndWait` requests. The call times out
            // client-side but the queued task stays stuck on Remote Robot's
            // serialized component-API dispatcher, poisoning every subsequent
            // `/api/tree` and `/api/component/...` request **permanently** —
            // the server then appears dead until the IDE is restarted. We
            // cheaply check the raw tree for a visible dialog first and skip
            // the JS call when one is open; editor code is in any case
            // irrelevant while the LLM is supposed to be reading the dialog.
            val dialogVisible = ScopedSnapshotBuilder.containsDialog(uiTree, profile)
            val editorCode =
                if (dialogVisible) {
                    null
                } else {
                    try {
                        executor.getEditorContext()?.let {
                            ScopedSnapshotBuilder.EditorCode(
                                caretLine = it.caretLine,
                                caretColumn = it.caretColumn,
                                totalLines = it.totalLines,
                                symbolUnderCaret = it.symbolUnderCaret,
                                selectedText = it.selectedText,
                                windowStartLine = it.windowStartLine,
                                windowEndLine = it.windowEndLine,
                                visibleText = it.visibleText,
                            )
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
            val snapshot = ScopedSnapshotBuilder.buildCompactSnapshot(uiTree, profile, editorCode)
            val delta = UiDelta.between(state.previousSnapshot, snapshot)
            val fingerprintBefore = snapshot.fingerprint

            val uiDescription = UiTreeFormatter.format(uiTree, profile)
            state.uiStateHistory.add(uiDescription)

            println(
                "  Observed UI tree with ${uiTree.size} root components " +
                    "(fingerprint=${fingerprintBefore.take(8)}, ctx=${snapshot.activeContext})",
            )

            // Show recipe step info if available
            state.matchedRecipe?.let { recipe ->
                val currentStep = recipe.getCurrentStep()
                if (currentStep != null) {
                    println(
                        "  Recipe Step ${recipe.currentStep + 1}/${recipe.recipe.successfulActions.size}: " +
                            currentStep.action.type,
                    )
                }
            }

            // 2. REASON: pass the delta (and any pending hint) so the LLM sees
            // what changed and any guidance the loop has accumulated.
            val context =
                DecisionContext(
                    intent = intent,
                    uiTree = uiTree,
                    profile = profile,
                    actionHistory = state.actionHistory.toList(),
                    matchedRecipe = state.matchedRecipe,
                    uiDelta = delta,
                    extraHint = state.nextHint,
                    editorCode = editorCode,
                )
            // Hint has been consumed by this prompt; clear it for subsequent iters.
            state = state.copy(nextHint = "")

            val decision = reasoner.decide(context)
            println("  LLM Reasoning: ${decision.reasoning.take(200)}...")
            println(
                "  LLM Decision: ${describeAction(decision.action)} " +
                    "(confidence: ${"%.2f".format(decision.confidence)})",
            )
            if (decision.assumptions.isNotBlank()) {
                println("  LLM Assumptions: ${decision.assumptions.take(160)}")
            }

            // Fingerprint-based stagnation: if the UI didn't change since last
            // iteration and the LLM picks the same action again, don't re-run it.
            val currentActionDesc = describeAction(decision.action)
            val prevActionDesc = state.actionHistory.lastOrNull()?.let { describeAction(it.action) }
            val sameFingerprint =
                state.previousFingerprint.isNotEmpty() &&
                    state.previousFingerprint == fingerprintBefore
            val sameAction = prevActionDesc != null && prevActionDesc == currentActionDesc

            // Special case 1: in an empty editor snapshot, Observe genuinely
            // can't help and the model has no other signal. Don't count it
            // toward stagnation — instead, feed the LLM a one-shot hint on
            // the next turn telling it to act on the editor directly.
            val activeWindowEmpty =
                snapshot.activeWindow.fields.isEmpty() &&
                    snapshot.activeWindow.buttons.isEmpty() &&
                    snapshot.activeWindow.menuItems.isEmpty()
            val observeInEmptyEditor =
                decision.action is AgentAction.Observe &&
                    snapshot.activeContext == ScopedSnapshotBuilder.ActiveContext.EDITOR &&
                    activeWindowEmpty

            // Special case 2: inline template (rename/extract). Observing
            // here just wastes iterations — the only way to make progress
            // is to type the new name and press Enter. We nudge the LLM
            // explicitly instead of silently stalling.
            val observeInInlineWidget =
                decision.action is AgentAction.Observe &&
                    snapshot.activeContext == ScopedSnapshotBuilder.ActiveContext.INLINE_WIDGET

            val shouldSkipStagnation = observeInEmptyEditor || observeInInlineWidget

            val newSameFingerprintStreak =
                if (sameFingerprint && !shouldSkipStagnation) state.sameFingerprintStreak + 1 else 0
            val newSameActionStreak =
                if (sameFingerprint && sameAction && !shouldSkipStagnation) {
                    state.sameActionStreak + 1
                } else {
                    0
                }

            if (observeInEmptyEditor) {
                val hint =
                    "Observe won't help — the UI snapshot is sparse because the editor area " +
                        "is empty. Take a concrete action instead: OpenFile(<filename>) if no " +
                        "file is open, MoveCaret(<symbol>) to position in an open file, or " +
                        "PressKey(\"context_menu\") once the caret is placed."
                println("  Empty-editor Observe: injecting hint for next iter.")
                state = state.copy(nextHint = hint)
            }

            if (observeInInlineWidget) {
                val oldId = snapshot.inlineWidget?.oldIdentifier.orEmpty()
                val hint =
                    "An in-place rename template is LIVE. The old identifier " +
                        (if (oldId.isNotBlank()) "\"$oldId\" " else "") +
                        "is already selected in the editor — type the new name " +
                        "(Type with clearFirst=false) then PressKey(\"Enter\") to commit. " +
                        "Observe will not advance this state."
                println("  Inline-widget Observe: injecting type+Enter hint.")
                state = state.copy(nextHint = hint)
            }

            if (newSameActionStreak >= STAGNATION_FAIL_STREAK) {
                println(
                    "  Stagnation: '$currentActionDesc' + no UI change for " +
                        "$newSameActionStreak iterations. Stopping.",
                )
                state = state.copy(failed = true, lastDecision = decision)
                break
            }

            // Effective action: after a short stagnation, override a repeated
            // no-op action with Observe so the LLM gets a fresh view next turn.
            val effectiveAction =
                if (sameFingerprint && sameAction && newSameFingerprintStreak >= STAGNATION_WARN_STREAK) {
                    println("  Stagnation guard: forcing Observe (UI unchanged for $newSameFingerprintStreak iters)")
                    AgentAction.Observe
                } else {
                    decision.action
                }

            // Check for completion or failure
            if (decision.taskComplete || effectiveAction is AgentAction.Complete) {
                println("  Task marked as complete by LLM")
                state = state.copy(complete = true, lastDecision = decision)
                break
            }

            if (effectiveAction is AgentAction.Fail) {
                println("  Task marked as failed by LLM")
                state = state.copy(failed = true, lastDecision = decision)
                break
            }

            // 3. ACT: execute with intent as goal for post-click analysis.
            val actionResult = actionGenerator.execute(effectiveAction, uiTree, goal = intent)
            println("  Action Result: ${actionResult.message}")

            // Observe again so history carries a meaningful post-fingerprint.
            Thread.sleep(OBSERVE_DELAY_MS)
            val postTree = uiTreeProvider.invoke()
            val postSnapshot = ScopedSnapshotBuilder.buildCompactSnapshot(postTree, profile)

            state.actionHistory.add(
                HistoryEntry(
                    action = effectiveAction,
                    result = actionResult.message,
                    success = actionResult.success,
                    expected = decision.expectedResult,
                    fingerprintBefore = fingerprintBefore,
                    fingerprintAfter = postSnapshot.fingerprint,
                ),
            )

            // Advance recipe only on genuine success, and only when the action
            // we actually executed matches the LLM's decision (stagnation guard
            // may have swapped it for Observe).
            if (actionResult.success && effectiveAction === decision.action && state.matchedRecipe != null) {
                val advancedRecipe = state.matchedRecipe.advance()
                println("  Advanced to recipe step ${advancedRecipe.currentStep + 1}")
                state = state.copy(matchedRecipe = advancedRecipe)
            }

            if (checkDiffBasedCompletion(state)) {
                println("  ✓ Diff-based completion detected: source code changed and no popups open")
                state = state.copy(complete = true, lastDecision = decision)
                break
            }

            // Carry the PRE-action snapshot into the next iteration's "previous"
            // slot — NOT the post-action snapshot. Reason: the UI drifts slightly
            // between iterations (caret blink, tooltip fade, async classloader
            // hints) so post-of-N rarely equals pre-of-N+1 even when nothing
            // meaningful changed. Comparing iteration-start fingerprints across
            // iterations gives an honest stagnation signal: "did the world look
            // the same the last time I was about to decide?". The per-action
            // before/after pair is still captured in [HistoryEntry] above.
            state =
                state.copy(
                    lastDecision = decision,
                    previousSnapshot = snapshot,
                    previousFingerprint = fingerprintBefore,
                    sameFingerprintStreak = newSameFingerprintStreak,
                    sameActionStreak = newSameActionStreak,
                )
        }

        // Build final result
        return buildResult(state, intent)
    }

    /**
     * Find a matching recipe for the given intent.
     */
    private fun findMatchingRecipe(intent: String): MatchedRecipe? {
        val matchingRecipes = recipeRegistry.findMatching(intent)
        if (matchingRecipes.isEmpty()) {
            return null
        }

        // Use the best matching recipe (highest success count)
        val bestRecipe = matchingRecipes.first()
        return MatchedRecipe(
            recipe = bestRecipe,
            currentStep = 0,
            params = emptyMap(),
        )
    }

    /**
     * Check for diff-based completion.
     *
     * A task is considered complete if:
     * 1. We have initial document text captured
     * 2. The current document text differs from initial
     * 3. No popups/dialogs are currently open
     */
    private fun checkDiffBasedCompletion(state: ExecutionState): Boolean {
        val initialText = state.initialDocumentText ?: return false

        // Get current document text
        val currentText = executor.getDocumentText() ?: return false

        // Check if document changed
        if (currentText == initialText) {
            return false
        }

        // Check if any popups/dialogs are open
        val uiTree = uiTreeProvider.invoke()
        val allComponents = UiTreeParser.flatten(uiTree)
        val hasPopup = allComponents.any { profile.isPopupWindow(it.cls) }
        val hasDialog = allComponents.any { profile.isDialog(it.cls) }

        // Complete if document changed and no popups/dialogs
        return !hasPopup && !hasDialog
    }

    /**
     * Describe an action for logging. Delegates to [AgentAction.describe] so
     * the canonical vocabulary lives in a single place.
     */
    private fun describeAction(action: AgentAction): String = AgentAction.describe(action)

    /**
     * Build the final result.
     */
    private fun buildResult(
        state: ExecutionState,
        intent: String,
    ): ExecutionResult {
        val success = state.complete && !state.failed

        var recipeSaved = false
        var recipeId: String? = null

        if (success && state.actionHistory.isNotEmpty()) {
            // Save verified recipe
            val recipe = createRecipe(intent, state)
            recipeRegistry.register(recipe)
            recipeSaved = true
            recipeId = recipe.id
            println("\n  ✓ Saved verified recipe: ${recipe.id}")
        }

        val message =
            when {
                state.iteration >= MAX_ITERATIONS -> "Max iterations reached"
                state.failed -> "Task failed: ${state.lastDecision?.reasoning ?: "Unknown reason"}"
                state.complete -> "Task completed successfully"
                else -> "Unknown state"
            }

        println("\n=== RESULT ===")
        println("Success: $success")
        println("Message: $message")
        println("Actions taken: ${state.actionHistory.size}")
        println("Recipe saved: $recipeSaved")

        return ExecutionResult(
            success = success,
            message = message,
            actionsTaken = state.actionHistory.size,
            recipeSaved = recipeSaved,
            recipeId = recipeId,
        )
    }

    /**
     * Create a verified recipe from a successful execution.
     */
    private fun createRecipe(
        intent: String,
        state: ExecutionState,
    ): VerifiedRecipe {
        // Determine context from execution
        val context =
            VerifiedRecipe.RecipeContext(
                precondition = null, // Could be inferred from first action
                responseType = inferResponseType(state),
                application = "IntelliJ IDEA",
            )

        // Build successful actions with UI state context
        val actions =
            state.actionHistory.mapIndexed { index, entry ->
                val uiState = state.uiStateHistory.getOrNull(index) ?: "Unknown UI state"
                Pair(uiState, entry)
            }

        return recipeRegistry.createRecipe(intent, context, actions)
    }

    /**
     * Infer the response type from the execution history.
     */
    private fun inferResponseType(state: ExecutionState): String? {
        // Look for indicators in the action history
        val hasDialog =
            state.actionHistory.any { entry ->
                val a = entry.action
                val isClickLike =
                    a is AgentAction.Click ||
                        a is AgentAction.ClickMenuItem ||
                        a is AgentAction.ClickButton
                isClickLike &&
                    (
                        entry.result.contains("dialog", ignoreCase = true) ||
                            entry.result.contains("popup", ignoreCase = true)
                    )
            }

        val hasInlineWidget =
            state.actionHistory.any { entry ->
                entry.result.contains("inline", ignoreCase = true) ||
                    entry.result.contains("widget", ignoreCase = true)
            }

        return when {
            hasInlineWidget -> "INLINE_WIDGET"
            hasDialog -> "DIALOG"
            else -> null
        }
    }

    /**
     * Get the recipe registry for external access.
     */
    fun getRecipeRegistry(): RecipeRegistry = recipeRegistry

    /**
     * Clear all saved recipes.
     */
    fun clearRecipes() {
        recipeRegistry.clear()
    }
}
