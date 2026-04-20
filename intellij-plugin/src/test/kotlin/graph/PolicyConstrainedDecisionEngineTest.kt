package graph

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PolicyConstrainedDecisionEngineTest {
    @Test
    fun `injects rename policy instructions into delegated task`() {
        val delegate = RecordingDecisionEngine(
            GraphDecisionResult(
                reasoning = "Open the right-click menu first.",
                decision = GraphDecision(action = "open_context_menu"),
            ),
        )

        val engine =
            PolicyConstrainedDecisionEngine(
                delegate = delegate,
                policy = GraphDecisionPolicies.renameViaContextMenu(expectedReplacementText = "renamedName"),
            )

        val result =
            engine.decide(
                task = "rename the local variable originalName to renamedName in the current file",
                page = PageState(pageId = "editor_idle", description = "Editor", elements = emptyList(), rawHtml = ""),
                history = emptyList(),
                graph = KnowledgeGraph(),
                currentPageId = null,
            )

        assertEquals("open_context_menu", result.decision.action)
        assertTrue(delegate.recordedTask.contains("Use IntelliJ's right-click Rename action"))
        assertTrue(delegate.recordedTask.contains("Allowed actions: open_context_menu, click_menu_item, observe, type, press_key, complete, fail"))
        assertTrue(delegate.recordedTask.contains("Do not use keyboard shortcuts"))
        assertTrue(delegate.recordedTask.contains("The editor is already focused and the caret is already on the target symbol"))
        assertTrue(delegate.recordedTask.contains("Your next action must be open_context_menu"))
    }

    @Test
    fun `fails decisions that violate the rename policy`() {
        val delegate = RecordingDecisionEngine(
            GraphDecisionResult(
                reasoning = "Using the rename shortcut is faster.",
                decision = GraphDecision(action = "press_shortcut", params = mapOf("keys" to "meta shift f6")),
            ),
        )

        val engine =
            PolicyConstrainedDecisionEngine(
                delegate = delegate,
                policy = GraphDecisionPolicies.renameViaContextMenu(expectedReplacementText = "renamedName"),
            )

        val result =
            engine.decide(
                task = "rename the local variable originalName to renamedName in the current file",
                page = PageState(pageId = "editor_idle", description = "Editor", elements = emptyList(), rawHtml = ""),
                history = emptyList(),
                graph = KnowledgeGraph(),
                currentPageId = null,
            )

        assertEquals("fail", result.decision.action)
        assertTrue(result.reasoning.contains("Policy violation"))
        assertTrue(result.reasoning.contains("press_shortcut"))
    }

    @Test
    fun `fails rename policy when menu label is not Rename`() {
        val delegate = RecordingDecisionEngine(
            GraphDecisionResult(
                reasoning = "Choose a nearby refactor action.",
                decision = GraphDecision(action = "click_menu_item", params = mapOf("label" to "Change Signature")),
            ),
        )

        val engine =
            PolicyConstrainedDecisionEngine(
                delegate = delegate,
                policy = GraphDecisionPolicies.renameViaContextMenu(expectedReplacementText = "renamedName"),
            )

        val result =
            engine.decide(
                task = "rename the local variable originalName to renamedName in the current file",
                page = PageState(pageId = "refactor_submenu", description = "Popup", elements = emptyList(), rawHtml = ""),
                history = emptyList(),
                graph = KnowledgeGraph(),
                currentPageId = null,
            )

        assertEquals("fail", result.decision.action)
        assertTrue(result.reasoning.contains("click_menu_item"))
        assertTrue(result.reasoning.contains("Rename"))
    }

    @Test
    fun `allows single observe fallback immediately after choosing Rename`() {
        val delegate = RecordingDecisionEngine(
            GraphDecisionResult(
                reasoning = "Wait one cycle for the inline rename widget to appear.",
                decision = GraphDecision(action = "observe"),
            ),
        )

        val engine =
            PolicyConstrainedDecisionEngine(
                delegate = delegate,
                policy = GraphDecisionPolicies.renameViaContextMenu(expectedReplacementText = "renamedName"),
            )

        val result =
            engine.decide(
                task = "rename the local variable originalName to renamedName in the current file",
                page = PageState(pageId = "editor_idle", description = "Editor", elements = emptyList(), rawHtml = ""),
                history =
                    listOf(
                        GraphAgent.ActionRecord(
                            actionType = "click_menu_item",
                            pageBefore = "refactor_submenu",
                            pageAfter = "editor_idle",
                            reasoning = "Choose Rename from the refactor submenu.",
                            success = true,
                        ),
                    ),
                graph = KnowledgeGraph(),
                currentPageId = null,
            )

        assertEquals("observe", result.decision.action)
        assertTrue(delegate.recordedTask.contains("Allowed actions: open_context_menu, click_menu_item, observe, type, press_key, complete, fail"))
        assertTrue(delegate.recordedTask.contains("Use observe exactly once"))
    }

    @Test
    fun `fails observe fallback outside the post menu editor idle window`() {
        val delegate = RecordingDecisionEngine(
            GraphDecisionResult(
                reasoning = "Observe again just to be safe.",
                decision = GraphDecision(action = "observe"),
            ),
        )

        val engine =
            PolicyConstrainedDecisionEngine(
                delegate = delegate,
                policy = GraphDecisionPolicies.renameViaContextMenu(expectedReplacementText = "renamedName"),
            )

        val result =
            engine.decide(
                task = "rename the local variable originalName to renamedName in the current file",
                page = PageState(pageId = "inline_widget", description = "Rename widget", elements = emptyList(), rawHtml = ""),
                history =
                    listOf(
                        GraphAgent.ActionRecord(
                            actionType = "observe",
                            pageBefore = "editor_idle",
                            pageAfter = "inline_widget",
                            reasoning = "Already waited once.",
                            success = true,
                        ),
                    ),
                graph = KnowledgeGraph(),
                currentPageId = null,
            )

        assertEquals("fail", result.decision.action)
        assertTrue(result.reasoning.contains("observe"))
        assertTrue(result.reasoning.contains("exactly once"))
    }

    @Test
    fun `injects completion hint after rename confirmation returns editor to idle`() {
        val delegate = RecordingDecisionEngine(
            GraphDecisionResult(
                reasoning = "The rename is complete.",
                decision = GraphDecision(action = "complete"),
            ),
        )

        val engine =
            PolicyConstrainedDecisionEngine(
                delegate = delegate,
                policy = GraphDecisionPolicies.renameViaContextMenu(expectedReplacementText = "renamedName"),
            )

        val result =
            engine.decide(
                task = "rename the local variable originalName to renamedName in the current file",
                page = PageState(pageId = "editor_idle", description = "Editor", elements = emptyList(), rawHtml = ""),
                history =
                    listOf(
                        GraphAgent.ActionRecord(
                            actionType = "press_key",
                            params = mapOf("key" to "ENTER"),
                            pageBefore = "inline_widget",
                            pageAfter = "editor_idle",
                            reasoning = "Confirm the inline rename widget.",
                            success = true,
                        ),
                    ),
                graph = KnowledgeGraph(),
                currentPageId = null,
            )

        assertEquals("complete", result.decision.action)
        assertTrue(delegate.recordedTask.contains("The rename has already been confirmed."))
        assertTrue(delegate.recordedTask.contains("Use complete now"))
    }

    private class RecordingDecisionEngine(
        private val response: GraphDecisionResult,
    ) : GraphDecisionEngine {
        var recordedTask: String = ""
            private set

        override fun decide(
            task: String,
            page: PageState,
            history: List<GraphAgent.ActionRecord>,
            graph: KnowledgeGraph,
            currentPageId: String?,
        ): GraphDecisionResult {
            recordedTask = task
            return response
        }
    }
}
