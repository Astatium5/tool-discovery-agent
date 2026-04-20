package test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class GraphAgentRenameConstrainedLlmReliabilityTest : BaseTest() {
    @Test
    @DisplayName("Phase A - constrained LLM rename repeated runs emit a reliability report")
    fun repeatedRunsEmitReliabilityReport() {
        val runCount = System.getenv("GRAPH_AGENT_RELIABILITY_RUNS")?.toIntOrNull() ?: 10
        val reportRoot = Path.of("build", "reports", "graph-agent", "reliability")
        val jsonReportPath = reportRoot.resolve("rename-constrained-llm-reliability.json")
        val reportPath = reportRoot.resolve("rename-constrained-llm-reliability.md")

        Files.createDirectories(reportRoot)
        Files.deleteIfExists(jsonReportPath)
        Files.deleteIfExists(reportPath)

        val report =
            GraphAgentRenameConstrainedLlmHarness.runReliabilitySuite(
                runCount = runCount,
                reportRoot = reportRoot,
                robot = robot,
                robotUrl = robotUrl,
            ) { executor ->
                openFreshCanonicalRenameFixture(executor)
            }

        assertEquals(runCount, report.runs.size)
        assertEquals(runCount, report.passedRuns, "Expected all constrained rename runs to pass")
        assertEquals(0, report.failedRuns, "Expected no constrained rename failures in the reliability harness")
        assertTrue(report.runs.all { it.artifactDir.isNotBlank() }, "Each repeated run should record an artifact directory")
        assertTrue(report.runs.all { it.failureSeam == "none" }, "Successful repeated runs should not report failure seams")
        assertTrue(Files.exists(jsonReportPath), "Expected repeated constrained rename runs to emit a JSON reliability report")
        assertTrue(Files.exists(reportPath), "Expected repeated constrained rename runs to emit a reliability report")
    }
}
