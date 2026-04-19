package graph

import llm.LlmClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LlmDecisionEnginePolicyTest {
    @Test
    fun `policy-aware prompt only advertises rename sandbox actions`() {
        var recordedSystemPrompt = ""
        var recordedUserPrompt = ""

        val engine =
            LlmDecisionEngine(
                llmClient = LlmClient(baseUrl = "https://example.com/v1", model = "test-model", apiKey = "test-key"),
                policy = GraphDecisionPolicies.renameViaContextMenu(expectedReplacementText = "renamedName"),
                chatCompletion = { systemPrompt, userPrompt ->
                    recordedSystemPrompt = systemPrompt
                    recordedUserPrompt = userPrompt
                    """
                    {
                      "reasoning": "Open the context menu first.",
                      "decision": {
                        "action": "open_context_menu",
                        "params": {}
                      }
                    }
                    """.trimIndent()
                },
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
        assertTrue(recordedSystemPrompt.contains("Allowed actions for this policy"))
        assertTrue(recordedSystemPrompt.contains("open_context_menu"))
        assertTrue(recordedSystemPrompt.contains("click_menu_item"))
        assertFalse(recordedSystemPrompt.contains("  - click: Click a UI element by its label"))
        assertFalse(recordedSystemPrompt.contains("  - press_shortcut:"))
        assertTrue(recordedSystemPrompt.contains("Do not use keyboard shortcuts"))
        assertTrue(recordedUserPrompt.contains("rename the local variable originalName to renamedName in the current file"))
    }

    @Test
    fun `parses minimax tool call responses for allowed actions`() {
        val engine =
            LlmDecisionEngine(
                llmClient = LlmClient(baseUrl = "https://example.com/v1", model = "test-model", apiKey = "test-key"),
                policy = GraphDecisionPolicies.renameViaContextMenu(expectedReplacementText = "renamedName"),
                chatCompletion = { _, _ ->
                    "<minimax:tool_call> <invoke name=\"open_context_menu\"> </invoke>"
                },
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
    }
}
