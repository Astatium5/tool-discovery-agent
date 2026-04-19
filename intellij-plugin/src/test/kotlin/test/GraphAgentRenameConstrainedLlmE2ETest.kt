package test

import executor.UiExecutor
import graph.GraphActionExecutorAdapter
import graph.GraphAgent
import graph.LlmDecisionEngine
import graph.PolicyConstrainedDecisionEngine
import graph.GraphDecisionPolicies
import graph.telemetry.GraphTelemetryFactory
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import llm.LlmClient
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import parser.HtmlUiTreeProvider
import parser.UiTreeParser
import java.nio.file.Files
import java.nio.file.Path

class GraphAgentRenameConstrainedLlmE2ETest : BaseTest() {
    @Test
    fun `constrained LLM GraphAgent renames the local variable through Rename menu`() {
        val executor = UiExecutor(robot)
        openFreshCanonicalRenameFixture(executor)

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
        val graphPath = artifactRoot.resolve("rename-constrained-llm-graph.json")

        try {
            val policy = GraphDecisionPolicies.renameViaContextMenu(expectedReplacementText = "renamedName")
            val decisionEngine =
                PolicyConstrainedDecisionEngine(
                    delegate = LlmDecisionEngine(llmClient = LlmClient(), policy = policy),
                    policy = policy,
                )

            val agent =
                GraphAgent(
                    provider = HtmlUiTreeProvider(robotUrl),
                    parser = UiTreeParser,
                    decisionEngine = decisionEngine,
                    actionHandler = GraphActionExecutorAdapter(executor),
                    graphPath = graphPath.toString(),
                    maxIterations = 10,
                    telemetry = telemetry,
                    artifactRootDir = artifactRoot,
                )

            val result = agent.execute("rename the local variable originalName to renamedName in the current file")

            assertTrue(result.success, "Constrained LLM GraphAgent should report rename success: ${result.message}")
            assertTrue(result.actionHistory.isNotEmpty(), "Constrained LLM GraphAgent should record actions")
            assertTrue(
                result.actionHistory.all { it.actionType in setOf("open_context_menu", "click_menu_item", "type", "press_key", "complete") },
                "Constrained policy should keep action history inside the rename sandbox: ${result.actionHistory.map { it.actionType }}",
            )
            assertTrue(result.artifactPaths.isNotEmpty(), "Constrained LLM test should record HTML artifacts")
            result.artifactPaths.forEach { artifactPath ->
                assertTrue(Files.exists(Path.of(artifactPath)), "Expected artifact to exist: $artifactPath")
            }

            val documentAfter = executor.getDocumentText()
            check(documentAfter != null) { "Expected document text after rename" }
            assertTrue(documentAfter.contains("renamedName"))
            assertTrue(!documentAfter.contains("originalName"))

            val spans = exporter.finishedSpanItems
            assertTrue(spans.any { it.name == "graph_agent.rename_local_variable" })
            assertTrue(spans.any { it.name == "decide.next_action" })
            assertTrue(spans.any { it.name == "act.execute" })
        } finally {
            telemetry.close()
            Files.deleteIfExists(graphPath)
        }
    }
}
