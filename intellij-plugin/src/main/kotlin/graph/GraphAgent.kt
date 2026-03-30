package graph

import executor.UiExecutor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import llm.LlmClient
import parser.HtmlUiTreeProvider
import parser.UiTreeParser

/**
 * Graph-based UI automation agent implementing AppAgentX approach.
 *
 * This agent maintains a knowledge graph of UI states and transitions,
 * providing compact context to the LLM instead of dumping raw HTML every step.
 *
 * Main execution loop: observe → reason → act → update_graph → check_complete
 */
class GraphAgent(
    private val executor: UiExecutor,
    private val llmClient: LlmClient,
    private val treeProvider: HtmlUiTreeProvider,
    private val parser: UiTreeParser,
    private val graphPath: String = "data/knowledge_graph.json",
    private val maxIterations: Int = 30,
) {
    private val graph = KnowledgeGraph().apply { load(graphPath) }
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    companion object {
        private const val SETTLE_DELAY_MS = 500L
    }

    // Track execution state
    private var currentPageId: String? = null
    private var prevPageId: String? = null
    private var iteration: Int = 0
    private val actionHistory: MutableList<ActionRecord> = mutableListOf()
    private var totalTokenCount: Int = 0

    /**
     * Record of an action taken during execution.
     */
    @Serializable
    data class ActionRecord(
        val actionType: String,
        val params: Map<String, String> = emptyMap(),
        val pageBefore: String,
        val pageAfter: String,
        val reasoning: String,
        val success: Boolean = true,
    )

    /**
     * Result of executing a task.
     */
    data class AgentResult(
        val success: Boolean,
        val message: String,
        val actionHistory: List<ActionRecord> = emptyList(),
        val tokenCount: Int = 0,
        val iterations: Int = 0,
    )

    /**
     * Execute a task using the graph-enhanced observe→reason→act→update_graph loop.
     *
     * @param task The task description (e.g., "rename method executeRecipe to runRecipe")
     * @return AgentResult with success status, action history, and metrics
     */
    fun execute(task: String): AgentResult {
        println("\n=== GraphAgent Starting ===")
        println("Task: $task")
        println("Graph stats: ${graph.stats()}")

        iteration = 0
        actionHistory.clear()
        totalTokenCount = 0
        currentPageId = null
        prevPageId = null

        // Main execution loop
        while (iteration < maxIterations) {
            iteration++

            println("\n--- Iteration $iteration ---")

            try {
                // 1. OBSERVE: Get current UI state
                val pageState = observe()
                prevPageId = currentPageId
                currentPageId = pageState.pageId

                println("Current page: ${pageState.pageId}")
                println("Elements: ${pageState.elements.size}")

                // 2. REASON: Get LLM decision with graph context
                val (reasoning, decision) = reason(task, pageState, actionHistory)

                println("Reasoning: $reasoning")
                println("Decision: ${decision.action} - ${decision.params}")

                // 3. ACT: Execute the action
                val record = act(decision, pageState.pageId, reasoning)

                // Add to history BEFORE updateGraph so it can access the record
                actionHistory.add(record)

                // 4. UPDATE GRAPH: Record transition and save
                val newPageState = updateGraph(record, prevPageId)

                // 5. CHECK COMPLETE: See if we're done
                if (decision.action == "complete") {
                    println("✓ Task completed successfully")
                    return AgentResult(
                        success = true,
                        message = "Task completed: $reasoning",
                        actionHistory = actionHistory.toList(),
                        tokenCount = totalTokenCount,
                        iterations = iteration,
                    )
                }

                if (decision.action == "fail") {
                    println("✗ Task failed: $reasoning")
                    return AgentResult(
                        success = false,
                        message = "Task failed: $reasoning",
                        actionHistory = actionHistory.toList(),
                        tokenCount = totalTokenCount,
                        iterations = iteration,
                    )
                }

                // Wait for UI to settle after action
                Thread.sleep(SETTLE_DELAY_MS)
            } catch (e: Exception) {
                println("ERROR at iteration $iteration: ${e.message}")
                e.printStackTrace()

                // Record failed action
                val failedRecord =
                    ActionRecord(
                        actionType = "error",
                        params = mapOf("error" to (e.message ?: "unknown")),
                        pageBefore = currentPageId ?: "unknown",
                        pageAfter = currentPageId ?: "unknown",
                        reasoning = "Execution error",
                        success = false,
                    )
                actionHistory.add(failedRecord)

                return AgentResult(
                    success = false,
                    message = "Execution error at iteration $iteration: ${e.message}",
                    actionHistory = actionHistory.toList(),
                    tokenCount = totalTokenCount,
                    iterations = iteration,
                )
            }
        }

        // Max iterations reached
        println("✗ Max iterations ($maxIterations) reached")
        return AgentResult(
            success = false,
            message = "Max iterations reached without completion",
            actionHistory = actionHistory.toList(),
            tokenCount = totalTokenCount,
            iterations = iteration,
        )
    }

    /**
     * OBSERVE: Fetch current UI state and classify page.
     *
     * @return PageState with pageId, description, and interactive elements
     */
    private fun observe(): PageState {
        // Fetch UI tree from Remote Robot via tree provider
        val components = treeProvider.fetchTree()

        // Get raw HTML for debugging (fetch directly from endpoint)
        val endpoint = "http://localhost:8082"
        val http = java.net.http.HttpClient.newHttpClient()
        val response =
            http.send(
                java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(endpoint))
                    .GET()
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString(),
            )
        val html = response.body()

        // Infer page state from components
        val pageState = parser.inferPageState(components, html)

        // Record page visit in graph
        graph.addPage(PageNode(pageState.pageId, pageState.description))
        graph.recordVisit(pageState.pageId)

        return pageState
    }

    /**
     * REASON: Get LLM decision based on task, current page, graph context, and history.
     *
     * @param task The user's task
     * @param page Current UI state
     * @param history Recent action history
     * @return Pair of (reasoning text, decision with action and params)
     */
    private fun reason(
        task: String,
        page: PageState,
        history: List<ActionRecord>,
    ): Pair<String, Decision> {
        // Build prompt with task, page context, graph context, and recent history
        val prompt =
            buildString {
                appendLine("## Task")
                appendLine(task)
                appendLine()

                appendLine("## Current UI State")
                appendLine(page.toPromptString())
                appendLine()

                // Add graph context
                if (currentPageId != null) {
                    appendLine(graph.toPromptContext(currentPageId!!))
                    appendLine()
                }

                // Add recent history (last 5 actions)
                if (history.isNotEmpty()) {
                    appendLine("## Recent Actions")
                    history.takeLast(5).forEachIndexed { index, record ->
                        appendLine("${index + 1}. ${record.actionType} on ${record.pageBefore}")
                        appendLine("   Reasoning: ${record.reasoning}")
                        appendLine("   Success: ${record.success}")
                    }
                    appendLine()
                }

                appendLine("## Your Decision")
                appendLine("Provide your response in JSON format:")
                appendLine("{")
                appendLine("  \"reasoning\": \"your thought process\",")
                appendLine("  \"decision\": {")
                appendLine(
                    "    \"action\": \"click|type|press_key|press_shortcut|open_context_menu|click_menu_item|select_dropdown|click_dialog_button|observe|complete|fail\",",
                )
                appendLine("    \"params\": {")
                appendLine("      \"target\": \"element label (for click)\",")
                appendLine("      \"text\": \"text to type (for type)\",")
                appendLine("      \"key\": \"key name (for press_key)\",")
                appendLine("      \"keys\": \"shortcut keys (for press_shortcut)\",")
                appendLine("      \"label\": \"menu item label (for click_menu_item/click_dialog_button)\",")
                appendLine("      \"value\": \"dropdown value (for select_dropdown)\"")
                appendLine("    }")
                appendLine("  }")
                appendLine("}")
            }

        // System prompt
        val systemPrompt = """You are an intelligent UI automation agent for IntelliJ IDEA.

Your goal is to complete the given task by interacting with the IDE UI. You have access to:
- Current UI state (page type and interactive elements)
- Knowledge graph of previously learned transitions
- History of recent actions

Available actions:
- click: Click on a UI element by its label
- type: Type text at the current cursor position
- press_key: Press a single key (enter, escape, tab, backspace, f6, etc.)
- press_shortcut: Press a keyboard shortcut (e.g., "meta shift f6" for Shift+F6 on Mac)
- open_context_menu: Open right-click context menu
- click_menu_item: Click an item in a context menu
- select_dropdown: Select a value from a dropdown
- click_dialog_button: Click a button in a dialog
- observe: Just observe the current state (no action)
- complete: Task is complete
- fail: Task cannot be completed

Important notes:
- Use "meta" for Command key on Mac (e.g., "meta shift f6" for Cmd+Shift+F6)
- Use "shift_f6" as a shortcut for rename (more reliable than menu navigation)
- Always check if the task is complete before taking unnecessary actions
- Learn from the graph context to avoid repeating failed actions
- Be concise in your reasoning

Provide your response as JSON with "reasoning" and "decision" fields."""

        // Call LLM
        val response =
            llmClient.chatStructured(
                systemPrompt = systemPrompt,
                userPrompt = prompt,
            )

        // Track token usage (approximate based on response length)
        totalTokenCount += response.length

        // Parse LLM response
        return parseParams(response)
    }

    /**
     * ACT: Execute the decided action via UiExecutor.
     *
     * @param decision The LLM's decision with action and params
     * @param pageBefore Page ID before action
     * @param reasoning The LLM's reasoning
     * @return ActionRecord with execution results
     */
    private fun act(
        decision: Decision,
        pageBefore: String,
        reasoning: String,
    ): ActionRecord {
        val actionType = decision.action
        val params = decision.params

        println("  Executing: $actionType with params: $params")

        try {
            when (actionType) {
                "click" -> {
                    val target =
                        params["target"]
                            ?: throw IllegalArgumentException("Missing 'target' parameter for click action")
                    executor.clickComponent(target)
                }

                "type" -> {
                    val text =
                        params["text"]
                            ?: throw IllegalArgumentException("Missing 'text' parameter for type action")
                    executor.typeText(text)
                }

                "press_key" -> {
                    val key =
                        params["key"]
                            ?: throw IllegalArgumentException("Missing 'key' parameter for press_key action")
                    executor.pressKey(key)
                }

                "press_shortcut" -> {
                    val keys =
                        params["keys"]
                            ?: throw IllegalArgumentException("Missing 'keys' parameter for press_shortcut action")
                    executor.pressShortcut(keys)
                }

                "open_context_menu" -> {
                    executor.openContextMenu()
                }

                "click_menu_item" -> {
                    val label =
                        params["label"]
                            ?: throw IllegalArgumentException("Missing 'label' parameter for click_menu_item action")
                    executor.clickMenuItem(label)
                }

                "select_dropdown" -> {
                    val value =
                        params["value"]
                            ?: throw IllegalArgumentException("Missing 'value' parameter for select_dropdown action")
                    executor.selectDropdown(value)
                }

                "click_dialog_button" -> {
                    val label =
                        params["label"]
                            ?: throw IllegalArgumentException("Missing 'label' parameter for click_dialog_button action")
                    executor.clickDialogButton(label)
                }

                "observe" -> {
                    // Just observe, no action
                }

                "complete", "fail" -> {
                    // Terminal actions, nothing to execute
                }

                else -> {
                    throw IllegalArgumentException("Unknown action type: $actionType")
                }
            }

            // Success - will update pageAfter after observe in next iteration
            return ActionRecord(
                actionType = actionType,
                params = params,
                pageBefore = pageBefore,
                pageAfter = pageBefore, // Will be updated in updateGraph
                reasoning = reasoning,
                success = true,
            )
        } catch (e: Exception) {
            println("  Action failed: ${e.message}")

            return ActionRecord(
                actionType = actionType,
                params = params,
                pageBefore = pageBefore,
                pageAfter = pageBefore,
                reasoning = reasoning,
                success = false,
            )
        }
    }

    /**
     * UPDATE GRAPH: Record page/element/transitions and save graph.
     *
     * @param record The action record from act()
     * @param prevPageId The previous page ID (before this action)
     * @return New PageState after the action
     */
    private fun updateGraph(
        record: ActionRecord,
        prevPageId: String?,
    ): PageState {
        // Observe new state after action
        val newState = observe()

        // Update record with actual page after
        val updatedRecord = record.copy(pageAfter = newState.pageId)
        actionHistory[actionHistory.size - 1] = updatedRecord

        // Record elements in the "before" page
        if (prevPageId != null) {
            val beforePage = graph.getPage(prevPageId)
            if (beforePage != null) {
                // Get elements from the page state (we'd need to store this)
                // For now, we'll skip element recording since we don't have the elements list here
            }
        }

        // Record transition if action was successful and pages changed
        if (record.success && prevPageId != null && prevPageId != newState.pageId) {
            // Find the element that was clicked/acted upon
            val elementId =
                record.params["target"]?.let { target ->
                    // Create element ID from target
                    KnowledgeGraph.makeElementId(prevPageId, "unknown", target)
                } ?: "$prevPageId::unknown"

            graph.addTransition(
                fromPage = prevPageId,
                elementId = elementId,
                action = record.actionType,
                toPage = newState.pageId,
                params = record.params,
            )
        }

        // Save graph to disk
        graph.save(graphPath)

        return newState
    }

    /**
     * Parse LLM response into reasoning and decision.
     *
     * @param response LLM response text
     * @return Pair of (reasoning, Decision)
     */
    private fun parseParams(response: String): Pair<String, Decision> {
        try {
            // Try to parse as JSON first
            val jsonStart = response.indexOf("{")
            val jsonEnd = response.lastIndexOf("}")

            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val jsonStr = response.substring(jsonStart, jsonEnd + 1)
                val jsonElement = json.parseToJsonElement(jsonStr)

                if (jsonElement is JsonObject) {
                    val reasoning = jsonElement["reasoning"]?.toString()?.trim('"') ?: ""

                    val decisionElement = jsonElement["decision"]
                    if (decisionElement is JsonObject) {
                        val action = decisionElement["action"]?.toString()?.trim('"') ?: "observe"

                        val params = mutableMapOf<String, String>()
                        val paramsElement = decisionElement["params"]

                        if (paramsElement is JsonObject) {
                            paramsElement.forEach { (key, value) ->
                                params[key] = value.toString().trim('"')
                            }
                        }

                        return reasoning to Decision(action, params)
                    }
                }
            }
        } catch (e: Exception) {
            println("  JSON parsing failed, using text fallback: ${e.message}")
        }

        // Fallback: text-based parsing
        val lines = response.lines()
        val reasoning = lines.take(3).joinToString(" ").trim()

        // Look for action keywords
        val action =
            when {
                response.contains("complete", ignoreCase = true) -> "complete"
                response.contains("fail", ignoreCase = true) -> "fail"
                response.contains("click", ignoreCase = true) -> "click"
                response.contains("type", ignoreCase = true) -> "type"
                response.contains("press", ignoreCase = true) -> "press_key"
                else -> "observe"
            }

        // Simple param extraction
        val params = mutableMapOf<String, String>()
        when (action) {
            "click" -> {
                // Find text in quotes after "click"
                Regex("""click\s+"([^"]+)""").find(response)?.groupValues?.get(1)?.let {
                    params["target"] = it
                }
            }
            "type" -> {
                // Find text in quotes after "type"
                Regex("""type\s+"([^"]+)""").find(response)?.groupValues?.get(1)?.let {
                    params["text"] = it
                }
            }
        }

        return reasoning to Decision(action, params)
    }

    /**
     * Decision data class.
     */
    data class Decision(
        val action: String,
        val params: Map<String, String> = emptyMap(),
    )
}
