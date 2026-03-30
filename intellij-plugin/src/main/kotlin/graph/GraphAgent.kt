package graph

import executor.UiExecutor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import llm.LlmClient
import parser.HtmlUiTreeProvider
import parser.UiTreeParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Graph-based UI automation agent implementing the AppAgentX approach.
 *
 * Maintains a knowledge graph of UI states and transitions, providing compact
 * context to the LLM instead of dumping raw HTML every step.
 *
 * Execution loop: observe → reason → act → update_graph → check_complete
 */
class GraphAgent(
    private val executor: UiExecutor,
    private val llmClient: LlmClient,
    private val treeProvider: HtmlUiTreeProvider,
    private val parser: UiTreeParser,
    private val graphPath: String = "data/knowledge_graph.json",
    private val maxIterations: Int = 30,
    private val remoteRobotUrl: String = "http://localhost:8082",
) {
    private val graph = KnowledgeGraph().apply { load(graphPath) }
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private var currentPageId: String? = null
    private var prevPageId: String? = null
    private var iteration: Int = 0
    private val actionHistory: MutableList<ActionRecord> = mutableListOf()
    private var totalTokenCount: Int = 0

    /**
     * Record of a single action taken during task execution.
     * Tracks the action, parameters, page transitions, reasoning, and success status.
     */
    @Serializable
    data class ActionRecord(
        val actionType: String,
        val params: Map<String, String> = emptyMap(),
        val pageBefore: String,
        val pageAfter: String,
        val reasoning: String,
        val success: Boolean = true,
        // Track the actual element that was acted upon
        val elementClass: String? = null,  // e.g., "ActionButton", "ActionMenuItem"
        val elementLabel: String? = null,  // e.g., "Refactor", "Rename"
    )

    /**
     * Result returned after task execution completes or fails.
     * Contains success status, message, full action history, token usage, and iteration count.
     */
    data class AgentResult(
        val success: Boolean,
        val message: String,
        val actionHistory: List<ActionRecord> = emptyList(),
        val tokenCount: Int = 0,
        val iterations: Int = 0,
    )

    data class Decision(
        val action: String,
        val params: Map<String, String> = emptyMap(),
    )

    fun execute(task: String): AgentResult {
        println("\n=== GraphAgent Starting ===")
        println("Task: $task")
        println("Graph stats: ${graph.stats()}")

        iteration = 0
        actionHistory.clear()
        totalTokenCount = 0
        currentPageId = null
        prevPageId = null

        while (iteration < maxIterations) {
            iteration++
            println("\n--- Iteration $iteration ---")

            try {
                val pageState = observe()
                prevPageId = currentPageId
                currentPageId = pageState.pageId

                println("Current page: ${pageState.pageId}")
                println("Elements: ${pageState.elements.size}")

                val (reasoning, decision) = reason(task, pageState, actionHistory)

                println("Reasoning: $reasoning")
                println("Decision: ${decision.action} - ${decision.params}")

                val record = act(decision, pageState.pageId, reasoning)
                actionHistory.add(record)

                val newPageState = updateGraph(record, prevPageId)

                when (decision.action) {
                    "complete" -> {
                        println("✓ Task completed successfully")
                        return AgentResult(
                            success = true,
                            message = "Task completed: $reasoning",
                            actionHistory = actionHistory.toList(),
                            tokenCount = totalTokenCount,
                            iterations = iteration,
                        )
                    }
                    "fail" -> {
                        println("✗ Task failed: $reasoning")
                        return AgentResult(
                            success = false,
                            message = "Task failed: $reasoning",
                            actionHistory = actionHistory.toList(),
                            tokenCount = totalTokenCount,
                            iterations = iteration,
                        )
                    }
                }

                Thread.sleep(SETTLE_DELAY_MS)
            } catch (e: Exception) {
                println("ERROR at iteration $iteration: ${e.message}")
                e.printStackTrace()

                val failedRecord = ActionRecord(
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
     * OBSERVE: Fetch current UI state from Remote Robot and classify the page.
     *
     * Returns a PageState containing the page type (editor_idle, dialog, etc.),
     * description, and list of interactive elements available on this page.
     */
    private fun observe(): PageState {
        val components = treeProvider.fetchTree()

        val html = fetchRawHtml()

        val pageState = parser.inferPageState(components, html)

        graph.addPage(PageNode(pageState.pageId, pageState.description))
        graph.recordVisit(pageState.pageId)

        // Store elements on first visit to enable delta computation later
        if (!graph.hasVisitedPage(pageState.pageId)) {
            graph.storeElements(pageState.pageId, pageState.elements)
        }

        return pageState
    }

    /**
     * Fetch raw HTML directly from Remote Robot endpoint.
     * Used for page state inference and debugging.
     */
    private fun fetchRawHtml(): String {
        val http = HttpClient.newHttpClient()
        val response = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(remoteRobotUrl))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return response.body()
    }

    /**
     * REASON: Query the LLM for the next action based on current state.
     *
     * Builds a prompt containing the task, current UI state, learned graph context
     * (known transitions from this page, available shortcuts), and recent action history.
     *
     * Returns a pair of (reasoning text, Decision with action and parameters).
     */
    private fun reason(
        task: String,
        page: PageState,
        history: List<ActionRecord>,
    ): Pair<String, Decision> {
        val prompt = buildPrompt(task, page, history)

        val systemPrompt = buildString {
            appendLine("You are an intelligent UI automation agent for IntelliJ IDEA.")
            appendLine()
            appendLine("Your goal is to complete the given task by interacting with the IDE UI.")
            appendLine()
            appendLine("Available actions:")
            appendLine("  - click: Click a UI element by its label")
            appendLine("  - type: Type text at the current cursor position")
            appendLine("  - press_key: Press a single key (enter, escape, tab, backspace, f6, etc.)")
            appendLine("  - press_shortcut: Press a keyboard shortcut (e.g., \"meta shift f6\" for Shift+F6 on Mac)")
            appendLine("  - open_context_menu: Open right-click context menu")
            appendLine("  - click_menu_item: Click an item in a context menu")
            appendLine("  - select_dropdown: Select a value from a dropdown")
            appendLine("  - click_dialog_button: Click a button in a dialog")
            appendLine("  - observe: Just observe the current state (no action)")
            appendLine("  - complete: Task is complete")
            appendLine("  - fail: Task cannot be completed")
            appendLine()
            appendLine("Important notes:")
            appendLine("  - Use \"meta\" for Command key on Mac (e.g., \"meta shift f6\" for Cmd+Shift+F6)")
            appendLine("  - Use \"shift_f6\" as a shortcut for rename (more reliable than menu navigation)")
            appendLine("  - Always check if the task is complete before taking unnecessary actions")
            appendLine("  - Learn from the graph context to avoid repeating failed actions")
            appendLine()
            appendLine("Provide your response as JSON with \"reasoning\" and \"decision\" fields.")
        }

        val response = llmClient.chatStructured(
            systemPrompt = systemPrompt,
            userPrompt = prompt,
        )

        totalTokenCount += response.length

        return parseLlmResponse(response)
    }

    /**
     * Build the LLM prompt from task, page state, and action history.
     *
     * Formats the current UI state, graph context (learned transitions and shortcuts),
     * and recent actions into a structured prompt for the LLM.
     */
    private fun buildPrompt(
        task: String,
        page: PageState,
        history: List<ActionRecord>,
    ): String = buildString {
        appendLine("## Task")
        appendLine(task)
        appendLine()

        // NEW: Use delta-aware prompt for revisited pages
        val hasVisitedBefore = graph.hasVisitedPage(page.pageId)
        val knownElements = graph.getElementsForPage(page.pageId)
        appendLine(page.toDeltaPromptString(hasVisitedBefore, knownElements))
        appendLine()

        if (currentPageId != null) {
            appendLine(graph.toPromptContext(currentPageId!!))
            appendLine()
        }

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
        appendLine("    \"action\": \"click|type|press_key|press_shortcut|open_context_menu|click_menu_item|select_dropdown|click_dialog_button|observe|complete|fail\",")
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

    /**
     * ACT: Execute the decided action via UiExecutor.
     *
     * Maps action types (click, type, press_key, etc.) to their corresponding
     * UiExecutor method calls. Returns an ActionRecord with execution results.
     */
    private fun act(
        decision: Decision,
        pageBefore: String,
        reasoning: String,
    ): ActionRecord {
        val actionType = decision.action
        val params = decision.params

        println("  Executing: $actionType with params: $params")

        // Find the element being acted upon (for click actions)
        var elementClass: String? = null
        var elementLabel: String? = null

        if (actionType == "click" || actionType == "click_menu_item" || actionType == "click_dialog_button") {
            val targetLabel = params["target"] ?: params["label"]
            if (targetLabel != null) {
                // Look up the element in the current page state
                val currentState = observe()  // Get current state to find element
                val clickedElement = currentState.elements.find { it.label == targetLabel }
                if (clickedElement != null) {
                    elementClass = clickedElement.cls
                    elementLabel = clickedElement.label
                    println("  Found element: class=$elementClass, label=$elementLabel")
                }
            }
        }

        val success = try {
            executeAction(actionType, params)
            true
        } catch (e: Exception) {
            println("  Action failed: ${e.message}")
            false
        }

        return ActionRecord(
            actionType = actionType,
            params = params,
            pageBefore = pageBefore,
            pageAfter = pageBefore,
            reasoning = reasoning,
            success = success,
            elementClass = elementClass,
            elementLabel = elementLabel,
        )
    }

    /**
     * Execute a single action type with its parameters.
     * Maps string action types to UiExecutor method calls.
     */
    private fun executeAction(actionType: String, params: Map<String, String>) {
        when (actionType) {
            "click" -> {
                val target = params["target"]
                    ?: throw IllegalArgumentException("Missing 'target' parameter for click action")
                executor.clickComponent(target)
            }
            "type" -> {
                val text = params["text"]
                    ?: throw IllegalArgumentException("Missing 'text' parameter for type action")
                executor.typeText(text)
            }
            "press_key" -> {
                val key = params["key"]
                    ?: throw IllegalArgumentException("Missing 'key' parameter for press_key action")
                executor.pressKey(key)
            }
            "press_shortcut" -> {
                val keys = params["keys"]
                    ?: throw IllegalArgumentException("Missing 'keys' parameter for press_shortcut action")
                executor.pressShortcut(keys)
            }
            "open_context_menu" -> executor.openContextMenu()
            "click_menu_item" -> {
                val label = params["label"]
                    ?: throw IllegalArgumentException("Missing 'label' parameter for click_menu_item action")
                executor.clickMenuItem(label)
            }
            "select_dropdown" -> {
                val value = params["value"]
                    ?: throw IllegalArgumentException("Missing 'value' parameter for select_dropdown action")
                executor.selectDropdown(value)
            }
            "click_dialog_button" -> {
                val label = params["label"]
                    ?: throw IllegalArgumentException("Missing 'label' parameter for click_dialog_button action")
                executor.clickDialogButton(label)
            }
            "observe", "complete", "fail" -> {
                // No action needed
            }
            else -> throw IllegalArgumentException("Unknown action type: $actionType")
        }
    }

    /**
     * UPDATE GRAPH: Record the transition taken and save graph to disk.
     *
     * After an action is executed, observes the new page state and records
     * the transition (fromPage → toPage) in the knowledge graph for future use.
     */
    private fun updateGraph(
        record: ActionRecord,
        prevPageId: String?,
    ): PageState {
        val newState = observe()

        val updatedRecord = record.copy(pageAfter = newState.pageId)
        actionHistory[actionHistory.size - 1] = updatedRecord

        if (record.success && prevPageId != null && prevPageId != newState.pageId) {
            // Use actual element class if available
            val elementClass = record.elementClass ?: "unknown"
            val elementLabel = record.elementLabel ?: record.params["target"] ?: "unknown"

            val elementId = KnowledgeGraph.makeElementId(
                pageId = prevPageId,
                cls = elementClass,  // Was: "unknown"
                label = elementLabel,  // Was: params["target"]
            )

            graph.addTransition(
                fromPage = prevPageId,
                elementId = elementId,
                action = record.actionType,
                toPage = newState.pageId,
                params = record.params,
            )

            // Also store the element in the graph for future reference
            val elementNode = ElementNode(
                id = elementId,
                pageId = prevPageId,
                cls = elementClass,
                label = elementLabel,
                xpath = "",  // Could be filled in if we had it
                role = inferRole(elementClass),
            )
            graph.addElement(elementNode)
        }

        graph.save(graphPath)

        return newState
    }

    /**
     * Parse LLM response into reasoning and decision.
     *
     * Attempts to parse JSON response first. Falls back to text-based
     * parsing if JSON extraction fails.
     */
    private fun parseLlmResponse(response: String): Pair<String, Decision> {
        try {
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

        return parseTextFallback(response)
    }

    private fun parseTextFallback(response: String): Pair<String, Decision> {
        val lines = response.lines()
        val reasoning = lines.take(3).joinToString(" ").trim()

        val action = when {
            response.contains("complete", ignoreCase = true) -> "complete"
            response.contains("fail", ignoreCase = true) -> "fail"
            response.contains("click", ignoreCase = true) -> "click"
            response.contains("type", ignoreCase = true) -> "type"
            response.contains("press", ignoreCase = true) -> "press_key"
            else -> "observe"
        }

        val params = mutableMapOf<String, String>()
        when (action) {
            "click" -> {
                Regex("""click\s+"([^"]+)""").find(response)?.groupValues?.get(1)?.let {
                    params["target"] = it
                }
            }
            "type" -> {
                Regex("""type\s+"([^"]+)""").find(response)?.groupValues?.get(1)?.let {
                    params["text"] = it
                }
            }
        }

        return reasoning to Decision(action, params)
    }

    /**
     * Helper to infer role from class name.
     * Used to categorize elements in the knowledge graph.
     */
    private fun inferRole(cls: String): String {
        return when {
            cls.contains("Button", ignoreCase = true) -> "button"
            cls.contains("MenuItem", ignoreCase = true) -> "menu_item"
            cls.contains("TextField", ignoreCase = true) -> "text_field"
            cls.contains("CheckBox", ignoreCase = true) -> "checkbox"
            cls.contains("ComboBox", ignoreCase = true) -> "dropdown"
            else -> "unknown"
        }
    }

    private companion object {
        const val SETTLE_DELAY_MS = 500L
    }
}
