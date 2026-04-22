package agent

import com.intellij.openapi.diagnostic.logger
import dev.langchain4j.model.chat.ChatModel
import execution.ActionGenerator
import execution.UiExecutor
import model.AgentAction
import perception.UiTreeFormatter
import perception.tree.UiComponent
import perception.tree.UiTreeParser
import profile.ApplicationProfile
import reasoner.TreeReasoner
import reasoner.TreeReasoner.*
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
    private val llm: ChatModel,
    private val profile: ApplicationProfile,
    private val executor: UiExecutor,
    private val uiTreeProvider: () -> List<UiComponent>,
) {
    private val log = logger<UiAgent>()
    private val reasoner = TreeReasoner(llm)
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
        // Uses AgentConfig for all configurable values
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
        log.info("\n=== BRAIN AGENT ===")
        log.info("Intent: $intent")

        // Try to find a matching recipe
        val matchedRecipe = findMatchingRecipe(intent)
        if (matchedRecipe != null) {
            log.info("  Found matching recipe: ${matchedRecipe.recipe.id}")
            log.info("  Recipe has ${matchedRecipe.recipe.successfulActions.size} steps")
        } else {
            log.info("  No matching recipe found - will explore from scratch")
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
        while (state.iteration < AgentConfig.maxIterations && !state.failed && !state.complete) {
            state = state.copy(iteration = state.iteration + 1)

            log.info("\n--- Iteration ${state.iteration} ---")

            // 1. OBSERVE: Get current UI state (raw tree, no intermediate transformation)
            val uiTree = uiTreeProvider.invoke()
            val uiDescription = UiTreeFormatter.format(uiTree, profile)

            // Check for context menu items via Remote Robot (HTML tree may not include popups)
            val contextMenuItems = executor.getContextMenuItems()

            state.uiStateHistory.add(uiDescription)

            log.info("  Observed UI tree with ${uiTree.size} root components")
            if (contextMenuItems.isNotEmpty()) {
                log.info(
                    "  Context menu has ${contextMenuItems.size} items: ${contextMenuItems.take(
                        AgentConfig.maxMenuItemsPreview,
                    ).joinToString(", ")}...",
                )
            }

            // Show recipe step info if available
            state.matchedRecipe?.let { recipe ->
                val currentStep = recipe.getCurrentStep()
                if (currentStep != null) {
                    log.info("  Recipe Step ${recipe.currentStep + 1}/${recipe.recipe.successfulActions.size}: ${currentStep.action.type}")
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
                    contextMenuItems = contextMenuItems,
                )

            val decision = reasoner.decide(context)
            println("  [Agent] LLM Decision: ${AgentAction.describe(decision.action)} (conf: ${"%.2f".format(decision.confidence)}, complete: ${decision.taskComplete})")
            println("  [Agent] Reasoning: ${decision.reasoning}")
            log.info("  LLM Reasoning: ${decision.reasoning}")
            log.info("  LLM Decision: ${AgentAction.describe(decision.action)} (confidence: ${"%.2f".format(decision.confidence)})")

            // Loop detection: same action repeated consecutively
            val lastActions = state.actionHistory.takeLast(AgentConfig.loopDetectionThreshold).map { AgentAction.describe(it.action) }
            val currentAction = AgentAction.describe(decision.action)
            if (lastActions.size == AgentConfig.loopDetectionThreshold && lastActions.all { it == currentAction }) {
                println("  [Agent] LOOP DETECTED: '$currentAction' repeated ${AgentConfig.loopDetectionThreshold} times")
                log.warn("  Loop detected: '$currentAction' repeated ${AgentConfig.loopDetectionThreshold} times. Stopping.")
                state = state.copy(failed = true, lastDecision = decision)
                break
            }

            // Check for completion or failure
            // First, if LLM says task_complete but action isn't Complete/Fail, execute it
            if (decision.taskComplete && decision.action !is AgentAction.Complete && decision.action !is AgentAction.Fail) {
                println("  [Agent] LLM says complete, executing pending action: ${AgentAction.describe(decision.action)}")
                log.info("  LLM says task_complete, but action still pending: ${AgentAction.describe(decision.action)}")
                // Execute the final action (e.g., PressKey("Enter")) before checking completion
                val actionResult = actionGenerator.execute(decision.action, uiTree)
                println("  [Agent] Final action result: ${actionResult.message}")
                log.info("  Final Action Result: ${actionResult.message}")
                Thread.sleep(AgentConfig.actionDelayMs)
            }

            if (decision.taskComplete || decision.action is AgentAction.Complete) {
                // Verify completion is actually possible (no inline refactoring, popups, etc.)
                // Check if inline refactoring is active or popups/dialogs are open
                val inlineRefactoringActive = executor.hasInlineRefactoringActive()
                val uiTreeNow = uiTreeProvider.invoke()
                val allComponents = UiTreeParser.flatten(uiTreeNow)
                val hasPopup = allComponents.any { profile.isPopupWindow(it.cls) }
                val hasDialog = allComponents.any { profile.isDialog(it.cls) }
                val hasEditorSelection = try { executor.hasEditorSelection() } catch (e: Exception) { false }

                println("  [Agent] Completion check: inlineRefactoring=$inlineRefactoringActive, popup=$hasPopup, dialog=$hasDialog, selection=$hasEditorSelection")

                if (inlineRefactoringActive || hasPopup || hasDialog || hasEditorSelection) {
                    println("  [Agent] BLOCKED: UI state prevents completion, continuing...")
                    log.warn("  LLM marked complete, but UI state prevents completion (inlineRefactoring=$inlineRefactoringActive, popup=$hasPopup, dialog=$hasDialog, selection=$hasEditorSelection)")
                    // Continue loop instead of breaking - LLM needs to finish the operation
                } else {
                    println("  [Agent] SUCCESS: Task complete!")
                    log.info("  Task marked as complete by LLM")
                    state = state.copy(complete = true, lastDecision = decision)
                    break
                }
            }

            if (decision.action is AgentAction.Fail) {
                println("  [Agent] FAILED: LLM marked task as failed")
                log.warn("  Task marked as failed by LLM")
                state = state.copy(failed = true, lastDecision = decision)
                break
            }

            // 3. ACT: Execute the action
            println("  [Agent] Executing: ${AgentAction.describe(decision.action)}")
            val actionResult = actionGenerator.execute(decision.action, uiTree)
            println("  [Agent] Result: ${actionResult.message}")
            log.info("  Action Result: ${actionResult.message}")

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
                log.info("  Advanced to recipe step ${advancedRecipe.currentStep + 1}")
                state = state.copy(matchedRecipe = advancedRecipe)
            }

            // Wait for UI to update
            Thread.sleep(AgentConfig.actionDelayMs)

            // Check for diff-based completion (source code changed and no popups open)
            if (checkDiffBasedCompletion(state)) {
                log.info("  ✓ Diff-based completion detected: source code changed and no popups open")
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
     * Only returns recipes if useRecipes config is enabled.
     */
    private fun findMatchingRecipe(intent: String): MatchedRecipe? {
        if (!AgentConfig.useRecipes) {
            log.info("  Recipe matching disabled (useRecipes=false)")
            return null
        }

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
     * 3. No popups/dialogs/inline editors are currently open
     * 4. The editor doesn't have an active template selection (refactoring mode)
     */
    private fun checkDiffBasedCompletion(state: ExecutionState): Boolean {
        val initialText = state.initialDocumentText ?: return false

        // Get current document text
        val currentText = executor.getDocumentText() ?: return false

        println("  [DEBUG] Diff check: initial=${initialText.take(50)}..., current=${currentText.take(50)}...")
        println("  [DEBUG] Texts equal: ${currentText == initialText}")

        // Check if document changed
        if (currentText == initialText) {
            println("  [DEBUG] No document change detected")
            return false
        }

        println("  [DEBUG] Document changed! Checking for blockers...")

        // Check if inline refactoring is active (prevents premature completion)
        val inlineRefactoringActive = executor.hasInlineRefactoringActive()
        println("  [DEBUG] inlineRefactoringActive = $inlineRefactoringActive")
        if (inlineRefactoringActive) {
            println("  Inline refactoring is active - preventing premature completion")
            return false
        }

        // Check if any popups/dialogs are open
        val uiTree = uiTreeProvider.invoke()
        val allComponents = UiTreeParser.flatten(uiTree)
        val hasPopup = allComponents.any { profile.isPopupWindow(it.cls) }
        val hasDialog = allComponents.any { profile.isDialog(it.cls) }

        println("  [DEBUG] UI tree components: ${allComponents.map { it.cls }.distinct().take(20)}")
        println("  [DEBUG] hasPopup = $hasPopup, hasDialog = $hasDialog")

        // Check if editor has an active selection (inline refactoring mode)
        // When refactoring is active (Shift+F6, etc), the symbol is selected/highlighted
        val hasEditorSelection = checkEditorHasSelection()
        println("  [DEBUG] hasEditorSelection = $hasEditorSelection")

        // Complete if document changed and no popups/dialogs/selection/template
        val canComplete = !hasPopup && !hasDialog && !hasEditorSelection
        println("  [DEBUG] canComplete = $canComplete")
        return canComplete
    }

    /**
     * Check if the editor has an active selection.
     * During inline refactoring mode (Shift+F6, Cmd+F6, etc), the symbol is highlighted/selected.
     */
    private fun checkEditorHasSelection(): Boolean {
        return try {
            executor.hasEditorSelection()
        } catch (e: Exception) {
            log.warn("  Warning: Could not check editor selection: ${e.message}")
            false
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

        if (success && state.actionHistory.isNotEmpty() && AgentConfig.saveRecipes) {
            // Save verified recipe
            val recipe = createRecipe(intent, state)
            recipeRegistry.register(recipe)
            recipeSaved = true
            recipeId = recipe.id
            log.info("\n  ✓ Saved verified recipe: ${recipe.id}")
        } else if (success && !AgentConfig.saveRecipes) {
            log.info("\n  Skipping recipe save (saveRecipes=false)")
        }

        val message =
            when {
                state.iteration >= AgentConfig.maxIterations -> "Max iterations reached"
                state.failed -> "Task failed: ${state.lastDecision?.reasoning ?: "Unknown reason"}"
                state.complete -> "Task completed successfully"
                else -> "Unknown state"
            }

        log.info("\n=== RESULT ===")
        log.info("Success: $success")
        log.info("Message: $message")
        log.info("Actions taken: ${state.actionHistory.size}")
        log.info("Recipe saved: $recipeSaved")

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
                entry.action is AgentAction.Click &&
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
