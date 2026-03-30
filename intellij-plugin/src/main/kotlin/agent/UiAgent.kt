package agent

import action.ActionGenerator
import executor.UiExecutor
import formatter.UiTreeFormatter
import llm.LlmClient
import parser.UiComponent
import parser.UiTreeParser
import profile.ApplicationProfile
import reasoner.LLMReasoner
import reasoner.LLMReasoner.*
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
 */
class UiAgent(
    private val llm: LlmClient,
    private val profile: ApplicationProfile,
    private val executor: UiExecutor,
    private val uiTreeProvider: () -> List<UiComponent>,
) {
    private val reasoner = LLMReasoner(llm)
    private val actionGenerator = ActionGenerator(executor, profile, uiTreeProvider, llm)
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
        val matchedRecipe: MatchedRecipe? = null, // Recipe with step tracking
        val initialDocumentText: String? = null, // For diff-based completion detection
    )

    companion object {
        private const val MAX_ITERATIONS = 30
        private const val OBSERVE_DELAY_MS = 500L
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

        // Main execution loop
        while (state.iteration < MAX_ITERATIONS && !state.failed && !state.complete) {
            state = state.copy(iteration = state.iteration + 1)

            println("\n--- Iteration ${state.iteration} ---")

            // 1. OBSERVE: Get current UI state (raw tree, no intermediate transformation)
            val uiTree = uiTreeProvider.invoke()
            val uiDescription = UiTreeFormatter.format(uiTree, profile)
            state.uiStateHistory.add(uiDescription)

            println("  Observed UI tree with ${uiTree.size} root components")

            // Show recipe step info if available
            state.matchedRecipe?.let { recipe ->
                val currentStep = recipe.getCurrentStep()
                if (currentStep != null) {
                    println("  Recipe Step ${recipe.currentStep + 1}/${recipe.recipe.successfulActions.size}: ${currentStep.action.type}")
                }
            }

            // 2. REASON: LLM decides next action (using raw tree directly)
            val context =
                DecisionContext(
                    intent = intent,
                    uiTree = uiTree,
                    profile = profile,
                    actionHistory = state.actionHistory.toList(),
                    matchedRecipe = state.matchedRecipe,
                )

            val decision = reasoner.decide(context)
            println("  LLM Reasoning: ${decision.reasoning.take(200)}...")
            println("  LLM Decision: ${describeAction(decision.action)} (confidence: ${"%.2f".format(decision.confidence)})")

            // Check for completion or failure
            if (decision.taskComplete || decision.action is Action.Complete) {
                println("  Task marked as complete by LLM")
                state = state.copy(complete = true, lastDecision = decision)
                break
            }

            if (decision.action is Action.Fail) {
                println("  Task marked as failed by LLM")
                state = state.copy(failed = true, lastDecision = decision)
                break
            }

            // 3. ACT: Execute the action
            val actionResult = actionGenerator.execute(decision.action, uiTree)
            println("  Action Result: ${actionResult.message}")

            // Record in history
            state.actionHistory.add(
                HistoryEntry(
                    action = decision.action,
                    result = actionResult.message,
                    success = actionResult.success,
                ),
            )

            // Advance recipe step if action was successful and we have a matched recipe
            if (actionResult.success && state.matchedRecipe != null) {
                val advancedRecipe = state.matchedRecipe.advance()
                println("  Advanced to recipe step ${advancedRecipe.currentStep + 1}")
                state = state.copy(matchedRecipe = advancedRecipe)
            }

            // Wait for UI to update
            Thread.sleep(OBSERVE_DELAY_MS)

            // Check for diff-based completion (source code changed and no popups open)
            if (checkDiffBasedCompletion(state)) {
                println("  ✓ Diff-based completion detected: source code changed and no popups open")
                state = state.copy(complete = true, lastDecision = decision)
                break
            }

            // Update state
            state = state.copy(lastDecision = decision)
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
     * Describe an action for logging.
     */
    private fun describeAction(action: Action): String {
        return when (action) {
            // Navigation actions
            is Action.OpenFile -> "OpenFile('${action.path}')"
            is Action.MoveCaret -> "MoveCaret('${action.symbol}')"
            is Action.SelectLines -> "SelectLines(${action.start}-${action.end})"
            // UI interaction actions
            is Action.Click -> "Click('${action.target}')"
            is Action.Type -> "Type('${action.text}', clearFirst=${action.clearFirst})"
            is Action.PressKey -> "PressKey('${action.key}')"
            is Action.SelectDropdown -> "SelectDropdown('${action.target}', '${action.value}')"
            is Action.Wait -> "Wait('${action.elementType}')"
            is Action.UseRecipe -> "UseRecipe('${action.recipeId}')"
            is Action.Observe -> "Observe"
            is Action.Complete -> "Complete"
            is Action.Fail -> "Fail"
        }
    }

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
                entry.action is Action.Click &&
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
