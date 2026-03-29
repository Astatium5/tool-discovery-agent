package graph

import agent.UiAgent
import executor.UiExecutor
import kotlinx.serialization.Serializable
import llm.LlmClient
import parser.UiComponent
import parser.UiTreeParser
import profile.ApplicationProfile
import reasoner.LLMReasoner
import reasoner.LLMReasoner.Action
import reasoner.LLMReasoner.Decision
import reasoner.LLMReasoner.HistoryEntry
import java.io.File
import java.nio.file.Paths

/**
 * Graph-enhanced UI Agent that extends Raihan's UiAgent with AppAgentX knowledge graph.
 *
 * Instead of dumping the full UI tree to the LLM every step (Raihan's approach),
 * this agent maintains a knowledge graph of UI states and transitions.
 *
 * Key differences from flat approach:
 * - Compact graph context instead of raw HTML tree
 * - Learned shortcuts for common sequences
 * - Persistent knowledge between runs
 * - Better decisions with fewer tokens as graph fills in
 */
class GraphUiAgent(
    private val llm: LlmClient,
    private val profile: ApplicationProfile,
    private val executor: UiExecutor,
    private val uiTreeProvider: () -> List<UiComponent>,
    private val graphPath: String = "data/knowledge_graph.json"
) {
    private val graph = KnowledgeGraph().apply { load(graphPath) }
    private val reasoner = LLMReasoner(llm)

    // Track execution state
    private var currentPageId: String? = null
    private var prevPageId: String? = null
    private var iteration: Int = 0
    private val actionHistory: MutableList<GraphActionRecord> = mutableListOf()
    private var initialDocText: String? = null

    /**
     * Result of executing an intent with graph enhancement.
     */
    data class GraphExecutionResult(
        val success: Boolean,
        val message: String,
        val actionsTaken: Int = 0,
        val tokenCount: Int = 0,
        val iterations: Int = 0,
        val graphStats: Map<String, Int> = emptyMap()
    )

    /**
     * Record of an action taken during graph-enhanced execution.
     */
    @Serializable
    data class GraphActionRecord(
        val actionType: String,
        val params: Map<String, String> = emptyMap(),
        val pageBefore: String,
        val pageAfter: String,
        val reasoning: String,
        val success: Boolean = true
    )

    companion object {
        private const val MAX_ITERATIONS = 30
        private const val SETTLE_DELAY_MS = 500L
    }

    /**
     * Execute an intent using graph-enhanced Observe-Reason-Act loop.
     *
     * @param intent The user's intent (e.g., "rename method foo to bar")
     * @return The result of the execution
     */
    fun execute(intent: String): GraphExecutionResult {
        println("\n=== GRAPH-ENHANCED UI AGENT ===")
        println("Intent: $intent")
        println("Graph stats: ${graph.stats()}")

        // Capture initial document text for diff-based completion detection
        initialDocText = executor.getDocumentText()
        iteration = 0
        actionHistory.clear()

        // Main execution loop
        while (iteration < MAX_ITERATIONS) {
            iteration++

            println("\n--- Iteration $iteration ---")

            // 1. OBSERVE: Get current UI state and classify page
            val (pageId, elements) = observe()
            currentPageId = pageId

            println("  Page: $pageId (${elements.size} elements)")

            // Add page to graph if new
            if (graph.getPage(pageId) == null) {
                graph.addPage(PageNode(pageId, "Page $pageId"))
                println("  [NEW] Added page '$pageId' to knowledge graph")
            }

            // 2. REASON: LLM decides next action using graph context
            val decision = reason(intent, pageId, elements)
            println("  LLM Decision: ${describeAction(decision.action)}")
            println("  Reasoning: ${decision.reasoning.take(100)}...")

            // Check for completion or failure
            if (decision.taskComplete || decision.action is Action.Complete) {
                println("  ✓ Task marked complete by LLM")
                return finishExecution(success = true, message = "Task completed successfully")
            }

            if (decision.action is Action.Fail) {
                println("  ✗ Task marked failed by LLM")
                return finishExecution(success = false, message = "Task failed: ${decision.reasoning}")
            }

            // 3. ACT: Execute the action
            val actionResult = executeAction(decision.action)
            println("  Action result: ${actionResult.message}")

            // 4. UPDATE GRAPH: Record transition
            updateGraph(decision, pageId, actionResult.success)

            // 5. CHECK COMPLETE: Diff-based detection
            if (checkDiffBasedCompletion()) {
                println("  ✓ Diff-based completion detected")
                return finishExecution(success = true, message = "Task completed (source code changed)")
            }

            // Wait for UI to settle
            Thread.sleep(SETTLE_DELAY_MS)
        }

        return finishExecution(success = false, message = "Max iterations reached")
    }

    /**
     * Observe current UI state and classify page.
     */
    private fun observe(): Pair<String, List<UiComponent>> {
        val uiTree = uiTreeProvider.invoke()
        val allComponents = UiTreeParser.flatten(uiTree)

        // Classify page based on component presence
        val pageId = classifyPage(allComponents)

        // Extract interactive elements (buttons, menus, text fields, etc.)
        val interactiveElements = allComponents.filter { component ->
            profile.isButton(component.cls) ||
            profile.isMenuItem(component.cls) ||
            profile.isTextInput(component.cls) ||
            profile.isEditor(component.cls) ||
            profile.isCheckbox(component.cls) ||
            profile.isDropdown(component.cls)
        }

        return pageId to interactiveElements
    }

    /**
     * Classify the current page based on UI components.
     */
    private fun classifyPage(components: List<UiComponent>): String {
        val hasPopup = components.any { profile.isPopupWindow(it.cls) }
        val hasDialog = components.any { profile.isDialog(it.cls) }
        val hasEditor = components.any { it.cls == "EditorComponentImpl" }

        return when {
            hasDialog -> "dialog"
            hasPopup -> "context_menu"
            hasEditor -> "editor_idle"
            else -> "unknown"
        }
    }

    /**
     * Reason about next action using LLM with graph context.
     */
    private fun reason(
        intent: String,
        pageId: String,
        elements: List<UiComponent>
    ): Decision {
        // Build context with graph knowledge
        val graphContext = graph.toPromptContext(pageId)

        // Build element list
        val elementsList = elements.take(20).joinToString("\n") { el ->
            "  [${el.cls}] \"${el.label}\""
        }

        // Debug: Print available elements
        println("  [DEBUG] Available elements:")
        elements.take(10).forEach { el ->
            println("    [${el.cls}] \"${el.label}\" (accessibleName: \"${el.accessibleName}\")")
        }

        val actionHistoryText = if (actionHistory.isEmpty()) {
            "No actions taken yet."
        } else {
            actionHistory.takeLast(5).joinToString("\n") { "  - ${it.actionType}: ${it.reasoning.take(50)}..." }
        }

        val prompt = """
            |You are a UI automation assistant for IntelliJ IDEA. Your task is: $intent
            |
            |Current page: $pageId
            |
            |$graphContext
            |
            |### Interactive elements on current page:
            |$elementsList
            |
            |### Recent action history:
            |$actionHistoryText
            |
            |IMPORTANT: Break down the task into small steps. Don't immediately mark as complete.
            |For a rename task, you typically need to:
            |1. Find and open the file (use Search Everywhere or file navigation)
            |2. Navigate to the method/variable to rename
            |3. Trigger rename (Alt+Enter for context menu, then select Rename)
            |4. Type the new name
            |5. Press Enter to confirm
            |
            |Available keyboard shortcuts:
            |- shift_f6: Rename refactor
            |- alt+enter: Show context actions
            |- enter: Confirm dialogs
            |- esc: Close dialogs
            |- shift+shift: Search Everywhere
            |- cmd+shift+o: Open file by name
            |- f6: Step over (debugging)
            |- tab: Navigate between fields
            |
            |Decide your next action. Response format (JSON):
            |{
            |  "action": "click|type|press_key|complete|fail",
            |  "target": "exact element label from the list above",
            |  "text": "text to type (if action=type)",
            |  "key": "single key name (enter, esc, tab, f6, shift_f6, alt+enter)",
            |  "reasoning": "why this action helps accomplish the task",
            |  "confidence": 0.0-1.0,
            |  "taskComplete": false
            |}
        """.trimMargin()

        // Call LLM
        val response = llm.chatStructured("You are a UI automation assistant for IntelliJ IDEA.", prompt)

        // Parse decision (simplified - in production would use proper JSON parsing)
        return parseLlmDecision(response)
    }

    /**
     * Execute an action and return result.
     * Ensures IntelliJ is focused before executing actions.
     */
    private fun executeAction(action: Action): ActionResult {
        // Ensure IntelliJ has focus before executing actions (macOS only)
        bringIntelliJToFront()

        return try {
            when (action) {
                is Action.Click -> {
                    executor.clickComponent(action.target)
                    ActionResult(success = true, message = "Clicked ${action.target}")
                }
                is Action.Type -> {
                    executor.typeText(action.text)
                    ActionResult(success = true, message = "Typed '${action.text}'")
                }
                is Action.PressKey -> {
                    // Use Raihan's existing pressKey method which handles shortcuts properly
                    executor.pressKey(action.key)
                    ActionResult(success = true, message = "Pressed ${action.key}")
                }
                is Action.Complete -> {
                    ActionResult(success = true, message = "Task complete")
                }
                else -> ActionResult(success = false, message = "Unknown action type")
            }
        } catch (e: Exception) {
            ActionResult(success = false, message = "Error: ${e.message}")
        }
    }

    /**
     * Bring IntelliJ to front on macOS using AppleScript.
     */
    private fun bringIntelliJToFront() {
        try {
            val osName = System.getProperty("os.name").lowercase()
            if (!osName.contains("mac")) {
                return // Not macOS, skip
            }

            val script = """
                tell application "System Events"
                    set frontmost of the first process whose name contains "IntelliJ" to true
                end tell
            """.trimIndent()

            val processBuilder = ProcessBuilder("osascript", "-e", script)
            processBuilder.start().waitFor()
            Thread.sleep(200) // Wait for window to come to front
        } catch (e: Exception) {
            println("  [WARNING] Could not bring IntelliJ to front: ${e.message}")
        }
    }

    /**
     * Update knowledge graph with transition.
     */
    private fun updateGraph(decision: Decision, pageBefore: String, success: Boolean) {
        if (!success) return

        // Observe new page after action
        val (pageAfter, _) = observe()

        // Record transition if page changed
        if (pageBefore != pageAfter) {
            // Extract target from action if available
            val target = when (val action = decision.action) {
                is Action.Click -> action.target
                is Action.Type -> action.text
                is Action.PressKey -> action.key
                else -> "unknown"
            }

            val elementId = KnowledgeGraph.makeElementId(
                pageBefore,
                "Component",  // Would be filled from actual element
                target
            )

            graph.addTransition(
                fromPage = pageBefore,
                elementId = elementId,
                action = decision.action.javaClass.simpleName,
                toPage = pageAfter
            )

            println("  [GRAPH] Recorded transition: $pageBefore → $pageAfter")
        }

        // Record action in history
        actionHistory.add(
            GraphActionRecord(
                actionType = decision.action.javaClass.simpleName,
                params = emptyMap(),
                pageBefore = pageBefore,
                pageAfter = pageAfter,
                reasoning = decision.reasoning,
                success = success
            )
        )
    }

    /**
     * Check for diff-based completion.
     */
    private fun checkDiffBasedCompletion(): Boolean {
        val initial = initialDocText ?: return false
        val current = executor.getDocumentText() ?: return false

        if (current == initial) return false

        // Check if no popups are open
        val (_, components) = observe()
        val hasPopup = components.any { profile.isPopupWindow(it.cls) }
        val hasDialog = components.any { profile.isDialog(it.cls) }

        return !hasPopup && !hasDialog
    }

    /**
     * Finish execution and save graph.
     */
    private fun finishExecution(success: Boolean, message: String): GraphExecutionResult {
        // Save knowledge graph
        try {
            graph.save(graphPath)
            println("  [GRAPH] Saved knowledge graph to $graphPath")
        } catch (e: Exception) {
            println("  [GRAPH] Failed to save graph: ${e.message}")
        }

        return GraphExecutionResult(
            success = success,
            message = message,
            actionsTaken = actionHistory.size,
            tokenCount = 0,  // Would track actual tokens
            iterations = iteration,
            graphStats = graph.stats()
        )
    }

    /**
     * Parse LLM response into decision.
     */
    private fun parseLlmDecision(response: String): Decision {
        // Extract JSON from response (handle markdown code blocks)
        val jsonMatch = Regex("""\{[^}]*"action"\s*:\s*"(\w+)"[^}]*\}""").find(response)
        val jsonResponse = jsonMatch?.value ?: response

        // Extract action type
        val actionType = Regex(""""action"\s*:\s*"(\w+)""").find(jsonResponse)?.groupValues?.get(1)
        val target = Regex(""""target"\s*:\s*"([^"]+)""").find(jsonResponse)?.groupValues?.get(1) ?: "unknown"
        val text = Regex(""""text"\s*:\s*"([^"]*)""").find(jsonResponse)?.groupValues?.get(1) ?: ""
        val key = Regex(""""key"\s*:\s*"([^"]+)""").find(jsonResponse)?.groupValues?.get(1) ?: ""
        val reasoning = Regex(""""reasoning"\s*:\s*"([^"]+)""").find(jsonResponse)?.groupValues?.get(1)
            ?: "No reasoning provided"
        val taskComplete = jsonResponse.contains("\"taskComplete\"\\s*:\\s*true") ||
                          jsonResponse.contains("\"taskComplete\"\\s*:\\s*true")

        val action = when (actionType) {
            "click" -> Action.Click(target)
            "type" -> Action.Type(text, target = if (target != "unknown") target else null)
            "press_key" -> Action.PressKey(key)
            "complete" -> Action.Complete
            "fail" -> Action.Fail
            else -> Action.Complete
        }

        return Decision(
            action = action,
            reasoning = reasoning,
            expectedResult = "UI should update accordingly",
            confidence = 0.8,
            taskComplete = taskComplete
        )
    }

    /**
     * Describe an action for logging.
     */
    private fun describeAction(action: Action): String {
        return when (action) {
            is Action.Click -> "Click('${action.target}')"
            is Action.Type -> "Type('${action.text}')"
            is Action.PressKey -> "PressKey('${action.key}')"
            is Action.Complete -> "Complete"
            is Action.Fail -> "Fail"
            else -> "Other"
        }
    }

    /**
     * Simple action result.
     */
    private data class ActionResult(
        val success: Boolean,
        val message: String
    )

    /**
     * Get the knowledge graph for external access.
     */
    fun getGraph(): KnowledgeGraph = graph

    /**
     * Clear the knowledge graph.
     */
    fun clearGraph() {
        val graphFile = File(graphPath)
        if (graphFile.exists()) {
            graphFile.delete()
        }
        // Reinitialize graph
    }
}
