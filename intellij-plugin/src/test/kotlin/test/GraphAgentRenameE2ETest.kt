package test

import executor.UiExecutor
import graph.GraphActionExecutorAdapter
import graph.GraphAgent
import graph.GraphDecision
import graph.GraphDecisionEngine
import graph.GraphDecisionResult
import graph.KnowledgeGraph
import graph.PageState
import graph.telemetry.GraphTelemetryFactory
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import parser.HtmlUiTreeProvider
import parser.UiTreeParser
import java.nio.file.Files
import java.nio.file.Path

class GraphAgentRenameE2ETest : BaseTest() {
    @Test
    @DisplayName("Stage 5 - GraphAgent completes the canonical rename flow with traces and artifacts")
    fun canonicalRenameFlowEmitsTracesAndArtifacts() {
        val executor = UiExecutor(robot)
        executor.openFile("src/test/kotlin/fixtures/GraphAgentRenameFixture.kt")

        val documentBefore = executor.getDocumentText()
        check(documentBefore != null) { "Expected document text after opening canonical fixture" }
        check(documentBefore.contains("val originalName = \"Ada\"")) {
            "Canonical fixture did not open or contents were unexpected"
        }

        executor.focusEditor()
        executor.moveCaret("originalName")

        val exporter = InMemorySpanExporter.create()
        val telemetry = GraphTelemetryFactory.createForTests(exporter)
        val artifactRoot = Path.of("build", "reports", "graph-agent", "tests")
        val graphPath = artifactRoot.resolve("rename-e2e-graph.json")

        try {
            val agent =
                GraphAgent(
                    provider = HtmlUiTreeProvider(robotUrl),
                    parser = UiTreeParser,
                    decisionEngine = CanonicalRenameDecisionEngine(),
                    actionHandler = GraphActionExecutorAdapter(executor),
                    graphPath = graphPath.toString(),
                    maxIterations = 8,
                    telemetry = telemetry,
                    artifactRootDir = artifactRoot,
                )

            val result = agent.execute("rename the local variable originalName to renamedName in the current file")

            assertTrue(result.success, "GraphAgent should report rename success")
            assertTrue(result.actionHistory.isNotEmpty(), "GraphAgent should record the rename actions")
            assertEquals("open_context_menu", result.actionHistory.first().actionType)
            assertTrue(result.artifactPaths.isNotEmpty(), "GraphAgent should record observed HTML artifact paths")
            result.artifactPaths.forEach { artifactPath ->
                assertTrue(Files.exists(Path.of(artifactPath)), "Expected artifact to exist: $artifactPath")
            }

            val documentAfter = executor.getDocumentText()
            check(documentAfter != null) { "Expected document text after rename" }
            assertTrue(documentAfter.contains("renamedName"))
            assertFalse(documentAfter.contains("originalName"))

            val spans = exporter.finishedSpanItems
            assertTrue(spans.any { it.name == "graph_agent.rename_local_variable" })
            assertTrue(spans.any { it.name == "observe.fetch_html" })
            assertTrue(spans.any { it.name == "act.execute" })
        } finally {
            telemetry.close()
            Files.deleteIfExists(graphPath)
        }
    }

    private class CanonicalRenameDecisionEngine : GraphDecisionEngine {
        override fun decide(
            task: String,
            page: PageState,
            history: List<GraphAgent.ActionRecord>,
            graph: KnowledgeGraph,
            currentPageId: String?,
        ): GraphDecisionResult =
            when {
                history.isEmpty() && page.pageId == "editor_idle" ->
                    GraphDecisionResult(
                        reasoning = "Open the context menu over the selected local variable.",
                        decision = GraphDecision(action = "open_context_menu"),
                    )

                history.lastOrNull()?.actionType == "open_context_menu" && page.pageId == "context_menu" ->
                    GraphDecisionResult(
                        reasoning = "Choose Rename from the context menu.",
                        decision = GraphDecision(action = "click_menu_item", params = mapOf("label" to "Rename")),
                    )

                history.lastOrNull()?.actionType == "click_menu_item" && page.pageId == "inline_widget" ->
                    GraphDecisionResult(
                        reasoning = "Type the replacement name into the inline rename widget.",
                        decision = GraphDecision(action = "type", params = mapOf("text" to "renamedName")),
                    )

                history.lastOrNull()?.actionType == "type" && page.pageId == "inline_widget" ->
                    GraphDecisionResult(
                        reasoning = "Confirm the inline rename widget.",
                        decision = GraphDecision(action = "press_key", params = mapOf("key" to "ENTER")),
                    )

                history.lastOrNull()?.actionType == "press_key" && page.pageId == "editor_idle" ->
                    GraphDecisionResult(
                        reasoning = "The editor returned to idle after the rename.",
                        decision = GraphDecision(action = "complete"),
                    )

                else ->
                    GraphDecisionResult(
                        reasoning = "The observed rename flow diverged from the canonical path at page ${page.pageId}.",
                        decision = GraphDecision(action = "fail"),
                    )
            }
    }
}
