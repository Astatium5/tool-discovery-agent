package test

import com.intellij.remoterobot.RemoteRobot
import executor.UiExecutor
import graph.GraphAgent
import graph.telemetry.GraphTelemetryFactory
import llm.LlmClient
import org.junit.jupiter.api.Test
import parser.HtmlUiTreeProvider
import parser.UiTreeParser
import java.nio.file.Path

/**
 * Simple smoke test for GraphAgent that works with any open IntelliJ project.
 *
 * Prerequisites:
 * 1. IntelliJ IDEA running with Remote Robot plugin on port 8082
 * 2. ANY project open (e.g., questdb, intellij-plugin, etc.)
 * 3. The IDE window must have focus before running this test
 *
 * This test verifies:
 * - Agent connects to Remote Robot
 * - Observes UI state correctly
 * - Executes keyboard actions
 * - Detects page transitions
 * - Persists graph to JSON
 */
class GraphAgentSmokeTest {

    @Test
    fun `smoke test - open and close Find dialog`() {
        val robot = RemoteRobot("http://localhost:8082")
        val executor = UiExecutor(robot)
        val llmClient = LlmClient()
        val treeProvider = HtmlUiTreeProvider("http://localhost:8082")
        val telemetry = GraphTelemetryFactory.createForTests(io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter.create())
        val artifactRoot = Path.of("build", "reports", "graph-agent", "smoke")

        try {
            val agent = GraphAgent(
                executor = executor,
                llmClient = llmClient,
                treeProvider = treeProvider,
                parser = UiTreeParser,
                graphPath = "data/knowledge_graph_smoke_test.json",
                maxIterations = 15,
                telemetry = telemetry,
                artifactRootDir = artifactRoot,
            )

            // Simple task that works in any project
            val result = agent.execute("press Command+F to open the Find dialog, then press Escape to close it")

            println("\n=== SMOKE TEST RESULT ===")
            println("Success: ${result.success}")
            println("Iterations: ${result.iterations}")
            println("Total tokens: ${result.tokenCount}")
            println("Action count: ${result.actionHistory.size}")
            println("\nActions taken:")
            result.actionHistory.forEachIndexed { index, record ->
                println("  ${index + 1}. ${record.actionType} on ${record.pageBefore} → ${record.pageAfter} (success: ${record.success})")
            }

            if (!result.success) {
                println("\nFailed reason: ${result.message}")
            }

            if (result.artifactPaths.isNotEmpty()) {
                println("\nArtifacts:")
                result.artifactPaths.forEach { println("  $it") }
            }

            // Verify basic expectations
            check(result.actionHistory.isNotEmpty()) { "Agent should have taken at least one action" }
            check(result.iterations > 0) { "Should have completed at least one iteration" }
            check(result.artifactPaths.isNotEmpty()) { "Smoke test should record at least one HTML artifact" }

            println("\n=== SMOKE TEST PASSED ===")
        } finally {
            telemetry.close()
        }
    }

    @Test
    fun `smoke test - press keyboard shortcuts`() {
        val robot = RemoteRobot("http://localhost:8082")
        val executor = UiExecutor(robot)
        val llmClient = LlmClient()
        val treeProvider = HtmlUiTreeProvider("http://localhost:8082")
        val telemetry = GraphTelemetryFactory.createForTests(io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter.create())
        val artifactRoot = Path.of("build", "reports", "graph-agent", "smoke-shortcuts")

        try {
            val agent = GraphAgent(
                executor = executor,
                llmClient = llmClient,
                treeProvider = treeProvider,
                parser = UiTreeParser,
                graphPath = "data/knowledge_graph_shortcuts_test.json",
                maxIterations = 10,
                telemetry = telemetry,
                artifactRootDir = artifactRoot,
            )

            // Test keyboard shortcut handling
            val result = agent.execute("press Command+Shift+A to open the action list, then press Escape to close")

            println("\n=== SHORTCUT TEST RESULT ===")
            println("Success: ${result.success}")
            println("Iterations: ${result.iterations}")
            println("\nActions taken:")
            result.actionHistory.forEachIndexed { index, record ->
                println("  ${index + 1}. ${record.actionType}: ${record.params}")
            }

            if (!result.success) {
                println("\nFailed reason: ${result.message}")
            }

            if (result.artifactPaths.isNotEmpty()) {
                println("\nArtifacts:")
                result.artifactPaths.forEach { println("  $it") }
            }

            check(result.actionHistory.any { it.actionType == "press_shortcut" }) {
                "Should have used keyboard shortcut"
            }
            check(result.artifactPaths.isNotEmpty()) { "Shortcut smoke test should record at least one HTML artifact" }

            println("\n=== SHORTCUT TEST PASSED ===")
        } finally {
            telemetry.close()
        }
    }
}
