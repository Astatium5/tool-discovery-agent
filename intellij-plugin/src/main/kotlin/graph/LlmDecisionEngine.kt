package graph

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import llm.LlmClient

class LlmDecisionEngine(
    private val llmClient: LlmClient,
) : GraphDecisionEngine {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    override fun decide(
        task: String,
        page: PageState,
        history: List<GraphAgent.ActionRecord>,
        graph: KnowledgeGraph,
        currentPageId: String?,
    ): GraphDecisionResult {
        val prompt = buildPrompt(task, page, history, graph, currentPageId)

        val systemPrompt =
            buildString {
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

        val response =
            llmClient.chatStructured(
                systemPrompt = systemPrompt,
                userPrompt = prompt,
            )

        val parsed = parseLlmResponse(response)
        return parsed.copy(tokenCount = response.length)
    }

    private fun buildPrompt(
        task: String,
        page: PageState,
        history: List<GraphAgent.ActionRecord>,
        graph: KnowledgeGraph,
        currentPageId: String?,
    ): String =
        buildString {
            appendLine("## Task")
            appendLine(task)
            appendLine()

            val hasVisitedBefore = graph.hasVisitedPage(page.pageId)
            val knownElements = graph.getElementsForPage(page.pageId)
            appendLine(page.toDeltaPromptString(hasVisitedBefore, knownElements))
            appendLine()

            if (currentPageId != null) {
                appendLine(graph.toPromptContext(currentPageId))
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

    private fun parseLlmResponse(response: String): GraphDecisionResult {
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

                        return GraphDecisionResult(
                            reasoning = reasoning,
                            decision = GraphDecision(action, params),
                        )
                    }
                }
            }
        } catch (e: Exception) {
            println("  JSON parsing failed, using text fallback: ${e.message}")
        }

        return parseTextFallback(response)
    }

    private fun parseTextFallback(response: String): GraphDecisionResult {
        val lines = response.lines()
        val reasoning = lines.take(3).joinToString(" ").trim()

        val action =
            when {
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

        return GraphDecisionResult(
            reasoning = reasoning,
            decision = GraphDecision(action, params),
        )
    }
}
