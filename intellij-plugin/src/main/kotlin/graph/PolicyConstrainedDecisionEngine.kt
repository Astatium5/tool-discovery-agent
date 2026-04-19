package graph

data class GraphDecisionPolicy(
    val name: String,
    val instructions: String,
    val allowedActions: Set<String>,
    val allowedMenuLabels: Set<String> = emptySet(),
    val allowedKeys: Set<String> = emptySet(),
    val expectedTypeText: String? = null,
    val contextualHints: (PageState, List<GraphAgent.ActionRecord>) -> List<String> = { _, _ -> emptyList() },
) {
    fun applyToTask(
        task: String,
        page: PageState,
        history: List<GraphAgent.ActionRecord>,
    ): String =
        buildString {
            appendLine("## Execution Policy: $name")
            appendLine(instructions.trim())
            appendLine()
            appendLine("Allowed actions: ${allowedActions.joinToString(", ")}")
            if (allowedMenuLabels.isNotEmpty()) {
                appendLine("Allowed menu labels: ${allowedMenuLabels.joinToString(", ")}")
            }
            if (allowedKeys.isNotEmpty()) {
                appendLine("Allowed keys: ${allowedKeys.joinToString(", ")}")
            }
            expectedTypeText?.let { appendLine("Expected replacement text: $it") }
            val hints = contextualHints(page, history)
            if (hints.isNotEmpty()) {
                appendLine()
                appendLine("Current step hints:")
                hints.forEach { hint -> appendLine("- $hint") }
            }
            appendLine()
            appendLine("## User Task")
            append(task)
        }

    fun validate(decision: GraphDecision): String? {
        if (decision.action !in allowedActions) {
            return "Action '${decision.action}' is not allowed by policy '$name'"
        }

        if (decision.action == "click_menu_item" && allowedMenuLabels.isNotEmpty()) {
            val label = decision.params["label"]
            if (label !in allowedMenuLabels) {
                return "Action 'click_menu_item' must use one of ${allowedMenuLabels.joinToString(", ")}, got '${label ?: "<missing>"}'"
            }
        }

        if (decision.action == "press_key" && allowedKeys.isNotEmpty()) {
            val key = decision.params["key"]?.uppercase()
            if (key !in allowedKeys) {
                return "Action 'press_key' must use one of ${allowedKeys.joinToString(", ")}, got '${decision.params["key"] ?: "<missing>"}'"
            }
        }

        if (decision.action == "type" && expectedTypeText != null) {
            val text = decision.params["text"]
            if (text != expectedTypeText) {
                return "Action 'type' must use '$expectedTypeText', got '${text ?: "<missing>"}'"
            }
        }

        return null
    }
}

object GraphDecisionPolicies {
    fun renameViaContextMenu(expectedReplacementText: String): GraphDecisionPolicy =
        GraphDecisionPolicy(
            name = "rename_via_context_menu",
            instructions =
                """
                Use IntelliJ's right-click Rename action for this task.
                Do not use keyboard shortcuts, global search, or unrelated refactor actions.
                Open the context menu on the selected symbol, choose Rename, type the replacement text, then confirm with Enter.
                If the task is already complete, respond with complete.
                """.trimIndent(),
            allowedActions = setOf("open_context_menu", "click_menu_item", "type", "press_key", "complete", "fail"),
            allowedMenuLabels = setOf("Rename"),
            allowedKeys = setOf("ENTER", "ESCAPE"),
            expectedTypeText = expectedReplacementText,
            contextualHints = { page, history ->
                when {
                    history.isEmpty() && page.pageId == "editor_idle" ->
                        listOf(
                            "The editor is already focused and the caret is already on the target symbol.",
                            "Do not click the editor or move the caret again.",
                            "Your next action must be open_context_menu.",
                        )

                    history.lastOrNull()?.actionType == "open_context_menu" &&
                        page.pageId in setOf("context_menu", "refactor_submenu") ->
                        listOf("The Rename option is the only valid menu target from this popup.", "Use click_menu_item with label Rename.")

                    page.pageId == "inline_widget" && history.none { it.actionType == "type" } ->
                        listOf("The inline rename widget is already open.", "Type exactly $expectedReplacementText.")

                    page.pageId == "inline_widget" && history.lastOrNull()?.actionType == "type" ->
                        listOf("The replacement text has already been entered.", "Confirm the rename with press_key ENTER.")

                    else -> emptyList()
                }
            },
        )
}

class PolicyConstrainedDecisionEngine(
    private val delegate: GraphDecisionEngine,
    private val policy: GraphDecisionPolicy,
) : GraphDecisionEngine {
    override fun decide(
        task: String,
        page: PageState,
        history: List<GraphAgent.ActionRecord>,
        graph: KnowledgeGraph,
        currentPageId: String?,
    ): GraphDecisionResult {
        val delegatedTask = policy.applyToTask(task, page, history)
        val result = delegate.decide(delegatedTask, page, history, graph, currentPageId)
        val violation = policy.validate(result.decision) ?: return result

        return GraphDecisionResult(
            reasoning =
                buildString {
                    if (result.reasoning.isNotBlank()) {
                        append(result.reasoning.trim())
                        appendLine()
                    }
                    append("Policy violation: ")
                    append(violation)
                },
            decision = GraphDecision(action = "fail", params = mapOf("policy" to policy.name)),
            tokenCount = result.tokenCount,
        )
    }
}
