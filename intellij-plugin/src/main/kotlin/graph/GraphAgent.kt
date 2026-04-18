package graph

import executor.UiExecutor
import graph.telemetry.GraphTelemetry
import graph.telemetry.GraphTelemetryAttributes
import graph.telemetry.GraphTelemetryFactory
import io.opentelemetry.api.common.Attributes
import kotlinx.serialization.Serializable
import llm.LlmClient
import parser.UiComponent
import parser.UiTreeParser
import parser.UiTreeProvider

/**
 * Graph-based UI automation agent implementing the AppAgentX approach.
 *
 * This class now orchestrates explicit observation, decision, and action seams
 * so the loop can be tested without hidden live work.
 */
class GraphAgent(
    private val provider: UiTreeProvider,
    private val parser: UiTreeParser,
    private val decisionEngine: GraphDecisionEngine,
    private val actionHandler: GraphActionHandler,
    private val graphPath: String = "data/knowledge_graph.json",
    private val maxIterations: Int = 30,
    private val telemetry: GraphTelemetry = GraphTelemetryFactory.create(serviceName = "graph-agent"),
) {
    constructor(
        executor: UiExecutor,
        llmClient: LlmClient,
        treeProvider: UiTreeProvider,
        parser: UiTreeParser,
        graphPath: String = "data/knowledge_graph.json",
        maxIterations: Int = 30,
    ) : this(
        provider = treeProvider,
        parser = parser,
        decisionEngine = LlmDecisionEngine(llmClient),
        actionHandler = GraphActionExecutorAdapter(executor),
        graphPath = graphPath,
        maxIterations = maxIterations,
    )

    private val graph = KnowledgeGraph().apply { load(graphPath) }

    private var currentPageId: String? = null
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
        val elementClass: String? = null,
        val elementLabel: String? = null,
    )

    /**
     * Result returned after task execution completes or fails.
     */
    data class AgentResult(
        val success: Boolean,
        val message: String,
        val actionHistory: List<ActionRecord> = emptyList(),
        val tokenCount: Int = 0,
        val iterations: Int = 0,
    )

    fun execute(task: String): AgentResult =
        telemetry.rootSpan(
            name = "graph_agent.execute",
            attributes =
                Attributes.builder()
                    .put(GraphTelemetryAttributes.TASK_ID, task)
                    .build(),
        ) {
            println("\n=== GraphAgent Starting ===")
            println("Task: $task")
            println("Graph stats: ${graph.stats()}")

            iteration = 0
            actionHistory.clear()
            totalTokenCount = 0
            currentPageId = null

            while (iteration < maxIterations) {
                iteration++
                println("\n--- Iteration $iteration ---")

                try {
                    val pageState =
                        telemetry.childSpan(
                            name = "graph_agent.iteration",
                            attributes =
                                Attributes.builder()
                                    .put(GraphTelemetryAttributes.ITERATION, iteration.toLong())
                                    .build(),
                        ) {
                            runIteration(task)
                        }

                    if (pageState != null) {
                        currentPageId = pageState.pageId
                    }
                } catch (completion: GraphAgentCompletion) {
                    return@rootSpan completion.result
                } catch (e: Exception) {
                    println("ERROR at iteration $iteration: ${e.message}")
                    e.printStackTrace()

                    val pageId = currentPageId ?: "unknown"
                    actionHistory.add(
                        ActionRecord(
                            actionType = "error",
                            params = mapOf("error" to (e.message ?: "unknown")),
                            pageBefore = pageId,
                            pageAfter = pageId,
                            reasoning = "Execution error",
                            success = false,
                        ),
                    )

                    return@rootSpan AgentResult(
                        success = false,
                        message = "Execution error at iteration $iteration: ${e.message}",
                        actionHistory = actionHistory.toList(),
                        tokenCount = totalTokenCount,
                        iterations = iteration,
                    )
                }
            }

            println("✗ Max iterations ($maxIterations) reached")
            AgentResult(
                success = false,
                message = "Max iterations reached without completion",
                actionHistory = actionHistory.toList(),
                tokenCount = totalTokenCount,
                iterations = iteration,
            )
        }

    private fun runIteration(task: String): PageState? {
        val pageState = observe()
        currentPageId = pageState.pageId

        println("Current page: ${pageState.pageId}")
        println("Elements: ${pageState.elements.size}")

        val decisionResult =
            telemetry.childSpan(
                name = "graph_agent.decision",
                attributes = pageAttributes(pageState.pageId),
            ) {
                decisionEngine.decide(
                    task = task,
                    page = pageState,
                    history = actionHistory.toList(),
                    graph = graph,
                    currentPageId = currentPageId,
                )
            }
        totalTokenCount += decisionResult.tokenCount

        val reasoning = decisionResult.reasoning
        val decision = decisionResult.decision

        println("Reasoning: $reasoning")
        println("Decision: ${decision.action} - ${decision.params}")

        val record =
            when (decision.action) {
                "complete" -> terminalRecord(pageState, reasoning, success = true)
                "fail" -> terminalRecord(pageState, reasoning, success = false)
                else -> act(decision, pageState, reasoning)
            }

        actionHistory.add(record)

        when (decision.action) {
            "complete" -> {
                persistGraphState()
                println("✓ Task completed successfully")
                throw GraphAgentCompletion(
                    AgentResult(
                        success = true,
                        message = "Task completed: $reasoning",
                        actionHistory = actionHistory.toList(),
                        tokenCount = totalTokenCount,
                        iterations = iteration,
                    ),
                )
            }
            "fail" -> {
                persistGraphState()
                println("✗ Task failed: $reasoning")
                throw GraphAgentCompletion(
                    AgentResult(
                        success = false,
                        message = "Task failed: $reasoning",
                        actionHistory = actionHistory.toList(),
                        tokenCount = totalTokenCount,
                        iterations = iteration,
                    ),
                )
            }
        }

        val newState = updateGraph(actionHistory.lastIndex, pageState.pageId)
        Thread.sleep(SETTLE_DELAY_MS)
        return newState
    }

    /**
     * OBSERVE: Fetch current raw HTML from the provider seam, parse it, and classify the page.
     */
    private fun observe(): PageState =
        telemetry.childSpan(name = "graph_agent.observe") {
            val html = provider.fetchRawHtml()
            val components = parser.parse(html)
            val pageState = parser.inferPageState(components, html)

            val firstVisit = graph.getPage(pageState.pageId) == null
            graph.addPage(PageNode(pageState.pageId, pageState.description))
            graph.recordVisit(pageState.pageId)

            if (firstVisit) {
                graph.storeElements(pageState.pageId, pageState.elements)
            }

            pageState
        }

    private fun persistGraphState() {
        graph.save(graphPath)
    }

    /**
     * ACT: Execute the decided action using the explicit action seam.
     */
    private fun act(
        decision: GraphDecision,
        pageState: PageState,
        reasoning: String,
    ): ActionRecord {
        val targetElement = resolveTargetElement(pageState, decision)

        val actionResult =
            telemetry.childSpan(
                name = "graph_agent.action",
                attributes = actionAttributes(pageState.pageId, decision, targetElement),
            ) {
                actionHandler.execute(
                    GraphActionRequest(
                        decision = decision,
                        page = pageState,
                        targetElement = targetElement,
                    ),
                )
            }

        if (!actionResult.success) {
            println("  Action failed: ${actionResult.message}")
        }

        return ActionRecord(
            actionType = decision.action,
            params = decision.params,
            pageBefore = pageState.pageId,
            pageAfter = pageState.pageId,
            reasoning = reasoning,
            success = actionResult.success,
            elementClass = targetElement?.cls,
            elementLabel = targetElement?.label,
        )
    }

    /**
     * UPDATE GRAPH: Record the transition taken and save graph to disk.
     */
    private fun updateGraph(
        actionIndex: Int,
        pageBefore: String,
    ): PageState =
        telemetry.childSpan(
            name = "graph_agent.update_graph",
            attributes = pageAttributes(pageBefore),
        ) {
            val record = actionHistory[actionIndex]
            val newState = observe()
            currentPageId = newState.pageId

            val updatedRecord = record.copy(pageAfter = newState.pageId)
            actionHistory[actionIndex] = updatedRecord

            val elementClass = record.elementClass ?: "unknown"
            val elementLabel = record.elementLabel ?: actionTargetLabel(record.params) ?: "unknown"
            val elementId =
                KnowledgeGraph.makeElementId(
                    pageId = pageBefore,
                    cls = elementClass,
                    label = elementLabel,
                )

            if (record.success && pageBefore != newState.pageId) {
                graph.addTransition(
                    fromPage = pageBefore,
                    elementId = elementId,
                    action = record.actionType,
                    toPage = newState.pageId,
                    params = record.params,
                    success = record.success,
                )
            } else if (!record.success) {
                val failureReason = record.reasoning.takeIf { it.isNotBlank() } ?: "Action execution failed"
                graph.addFailedTransition(
                    fromPage = pageBefore,
                    elementId = elementId,
                    action = record.actionType,
                    reason = failureReason,
                )
                println("  Recorded failed transition: ${record.actionType} on \"$elementLabel\" - $failureReason")
            }

            graph.addElement(
                ElementNode(
                    id = elementId,
                    pageId = pageBefore,
                    cls = elementClass,
                    label = elementLabel,
                    xpath = record.elementLabel?.let { target ->
                        record.elementClass?.let { cls ->
                            "//div[@class='$cls' and @accessiblename='$target']"
                        }
                    } ?: "",
                    role = inferRole(elementClass),
                ),
            )

            persistGraphState()

            if (actionHistory.size % 5 == 0 && actionHistory.size >= 10) {
                val discovered = graph.discoverShortcuts(actionHistory)
                if (discovered.isNotEmpty()) {
                    println("Discovered ${discovered.size} new shortcuts:")
                    discovered.forEach { println("  - ${it.name}") }
                }
            }

            newState
        }

    private fun terminalRecord(
        pageState: PageState,
        reasoning: String,
        success: Boolean,
    ): ActionRecord =
        ActionRecord(
            actionType = if (success) "complete" else "fail",
            params = emptyMap(),
            pageBefore = pageState.pageId,
            pageAfter = pageState.pageId,
            reasoning = reasoning,
            success = success,
        )

    private fun resolveTargetElement(
        pageState: PageState,
        decision: GraphDecision,
    ): UiComponent? {
        val label = actionTargetLabel(decision.params) ?: return null
        return pageState.elements.find { it.label == label }
            ?: pageState.elements.find { it.label.contains(label, ignoreCase = true) }
    }

    private fun pageAttributes(pageId: String): Attributes =
        Attributes.builder()
            .put(GraphTelemetryAttributes.PAGE_BEFORE, pageId)
            .build()

    private fun actionAttributes(
        pageId: String,
        decision: GraphDecision,
        targetElement: UiComponent?,
    ): Attributes {
        val builder =
            Attributes.builder()
                .put(GraphTelemetryAttributes.PAGE_BEFORE, pageId)
                .put(GraphTelemetryAttributes.ACTION_TYPE, decision.action)

        actionTargetLabel(decision.params)?.let { builder.put(GraphTelemetryAttributes.TARGET_LABEL, it) }
        targetElement?.cls?.let { builder.put(GraphTelemetryAttributes.ELEMENT_CLASS, it) }

        return builder.build()
    }

    private fun actionTargetLabel(params: Map<String, String>): String? = params["target"] ?: params["label"]

    /**
     * Helper to infer role from class name.
     */
    private fun inferRole(cls: String): String =
        when {
            cls.contains("Button", ignoreCase = true) -> "button"
            cls.contains("MenuItem", ignoreCase = true) -> "menu_item"
            cls.contains("TextField", ignoreCase = true) -> "text_field"
            cls.contains("CheckBox", ignoreCase = true) -> "checkbox"
            cls.contains("ComboBox", ignoreCase = true) -> "dropdown"
            else -> "unknown"
        }

    private class GraphAgentCompletion(
        val result: AgentResult,
    ) : RuntimeException(null, null, false, false)

    private companion object {
        const val SETTLE_DELAY_MS = 500L
    }
}

data class GraphActionRequest(
    val decision: GraphDecision,
    val page: PageState,
    val targetElement: UiComponent? = null,
)

data class GraphActionResult(
    val success: Boolean,
    val message: String? = null,
)

interface GraphActionHandler {
    fun execute(request: GraphActionRequest): GraphActionResult
}

class GraphActionExecutorAdapter(
    private val executor: UiExecutor,
) : GraphActionHandler {
    override fun execute(request: GraphActionRequest): GraphActionResult =
        runCatching {
            when (request.decision.action) {
                "click" -> {
                    val target =
                        request.decision.params["target"]
                            ?: throw IllegalArgumentException("Missing 'target' parameter for click action")
                    executor.clickComponent(target)
                }
                "type" -> {
                    val text =
                        request.decision.params["text"]
                            ?: throw IllegalArgumentException("Missing 'text' parameter for type action")
                    executor.typeText(text)
                }
                "press_key" -> {
                    val key =
                        request.decision.params["key"]
                            ?: throw IllegalArgumentException("Missing 'key' parameter for press_key action")
                    executor.pressKey(key)
                }
                "press_shortcut" -> {
                    val keys =
                        request.decision.params["keys"]
                            ?: throw IllegalArgumentException("Missing 'keys' parameter for press_shortcut action")
                    executor.pressShortcut(keys)
                }
                "open_context_menu" -> executor.openContextMenu()
                "click_menu_item" -> {
                    val label =
                        request.decision.params["label"]
                            ?: throw IllegalArgumentException("Missing 'label' parameter for click_menu_item action")
                    executor.clickMenuItem(label)
                }
                "select_dropdown" -> {
                    val value =
                        request.decision.params["value"]
                            ?: throw IllegalArgumentException("Missing 'value' parameter for select_dropdown action")
                    executor.selectDropdown(value)
                }
                "click_dialog_button" -> {
                    val label =
                        request.decision.params["label"]
                            ?: throw IllegalArgumentException("Missing 'label' parameter for click_dialog_button action")
                    executor.clickDialogButton(label)
                }
                "observe", "complete", "fail" -> Unit
                else -> throw IllegalArgumentException("Unknown action type: ${request.decision.action}")
            }

            GraphActionResult(success = true)
        }.getOrElse { error ->
            GraphActionResult(
                success = false,
                message = error.message ?: error::class.simpleName,
            )
        }
}
