package graph

import graph.telemetry.GraphTelemetryFactory
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import llm.LlmClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

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
        assertTrue(recordedSystemPrompt.contains("observe"))
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

    @Test
    fun `parses json responses with trailing wrapper noise`() {
        val engine =
            LlmDecisionEngine(
                llmClient = LlmClient(baseUrl = "https://example.com/v1", model = "test-model", apiKey = "test-key"),
                policy = GraphDecisionPolicies.renameViaContextMenu(expectedReplacementText = "renamedName"),
                chatCompletion = { _, _ ->
                    """{"reasoning":"Type the replacement text.","decision":{"action":"type","params":{"text":"renamedName"}}}"}"""
                },
            )

        val result =
            engine.decide(
                task = "rename the local variable originalName to renamedName in the current file",
                page = PageState(pageId = "inline_widget", description = "Rename widget", elements = emptyList(), rawHtml = ""),
                history =
                    listOf(
                        GraphAgent.ActionRecord(
                            actionType = "click_menu_item",
                            params = mapOf("label" to "Rename"),
                            pageBefore = "refactor_submenu",
                            pageAfter = "inline_widget",
                            reasoning = "Choose Rename from the refactor submenu.",
                            success = true,
                        ),
                    ),
                graph = KnowledgeGraph(),
                currentPageId = null,
            )

        assertEquals("type", result.decision.action)
        assertEquals("renamedName", result.decision.params["text"])
    }

    @Test
    fun `records decision spans and persists policy and raw response artifacts`() {
        val exporter = InMemorySpanExporter.create()
        val telemetry = GraphTelemetryFactory.createForTests(exporter)
        val artifactDir = Files.createTempDirectory("llm-decision-engine-artifacts")
        val policy = GraphDecisionPolicies.renameViaContextMenu(expectedReplacementText = "renamedName")
        val page = PageState(pageId = "editor_idle", description = "Editor", elements = emptyList(), rawHtml = "")
        val delegatedTask = policy.applyToTask("rename the local variable originalName to renamedName in the current file", page, emptyList())

        try {
            val engine =
                LlmDecisionEngine(
                    llmClient = LlmClient(baseUrl = "https://example.com/v1", model = "test-model", apiKey = "test-key"),
                    policy = policy,
                    chatCompletion = { _, _ ->
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
            engine.attachRuntimeContext(telemetry) { artifactDir }

            val result =
                telemetry.rootSpan("decide.next_action") {
                    engine.decide(
                        task = delegatedTask,
                        page = page,
                        history = emptyList(),
                        graph = KnowledgeGraph(),
                        currentPageId = null,
                    )
                }

            assertEquals("open_context_menu", result.decision.action)

            val spans = exporter.finishedSpanItems.map { it.name }
            assertTrue(spans.contains("decide.build_prompt"))
            assertTrue(spans.contains("decide.llm_call"))
            assertTrue(spans.contains("decide.parse_response"))

            val effectivePolicyArtifact = artifactDir.resolve("decision-01-effective-policy.txt")
            val rawResponseArtifact = artifactDir.resolve("decision-01-raw-response.txt")
            assertTrue(Files.exists(effectivePolicyArtifact), "Expected effective policy artifact at $effectivePolicyArtifact")
            assertTrue(Files.exists(rawResponseArtifact), "Expected raw response artifact at $rawResponseArtifact")
            assertTrue(Files.readString(effectivePolicyArtifact).contains("Do not use keyboard shortcuts"))
            assertTrue(Files.readString(rawResponseArtifact).contains("\"action\": \"open_context_menu\""))
        } finally {
            telemetry.close()
            artifactDir.toFile().deleteRecursively()
        }
    }
}
