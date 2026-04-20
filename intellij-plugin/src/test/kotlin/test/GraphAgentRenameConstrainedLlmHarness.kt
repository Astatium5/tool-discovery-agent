package test

import com.intellij.remoterobot.RemoteRobot
import executor.UiExecutor
import graph.GraphActionExecutorAdapter
import graph.GraphAgent
import graph.GraphDecisionPolicies
import graph.LlmDecisionEngine
import graph.PolicyConstrainedDecisionEngine
import graph.telemetry.GraphTelemetryFactory
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import llm.LlmClient
import org.junit.jupiter.api.Assertions.assertTrue
import parser.HtmlUiTreeProvider
import parser.UiTreeParser
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

internal object GraphAgentRenameConstrainedLlmHarness {
    const val originalName = "originalName"
    const val renamedName = "renamedName"
    const val canonicalTask = "rename the local variable originalName to renamedName in the current file"

    private val allowedActionTypes = setOf("open_context_menu", "click_menu_item", "type", "press_key", "complete")
    private val json = Json { prettyPrint = true }

    data class RunExecution(
        val fixture: GraphAgentRenameFixtureScenario,
        val result: GraphAgent.AgentResult,
        val spanNames: List<String>,
        val documentAfter: String?,
    )

    @Serializable
    data class ReliabilityRunRecord(
        val runNumber: Int,
        val success: Boolean,
        val iterations: Int,
        val tokenCount: Int,
        val failureSeam: String,
        val artifactDir: String,
        val artifactPaths: List<String>,
        val actionHistory: List<GraphAgent.ActionRecord>,
        val spanNames: List<String>,
        val message: String,
        val durationMillis: Long,
    )

    @Serializable
    data class ReliabilityReport(
        val generatedAt: String,
        val task: String,
        val requestedRuns: Int,
        val passedRuns: Int,
        val failedRuns: Int,
        val jsonReportPath: String,
        val markdownReportPath: String,
        val runs: List<ReliabilityRunRecord>,
    )

    fun executeOnce(
        robot: RemoteRobot,
        robotUrl: String,
        fixture: GraphAgentRenameFixtureScenario = GraphAgentRenameFixtureScenario.canonical,
        openFixture: (UiExecutor) -> String,
    ): RunExecution {
        val executor = UiExecutor(robot)
        openFixture(executor)

        val documentBefore = executor.getDocumentText()
        check(documentBefore != null) { "Expected document text after opening fixture ${fixture.id}" }
        fixture.assertExpectedBefore(documentBefore)

        executor.focusEditor()
        executor.moveCaret(fixture.originalName)

        val exporter = InMemorySpanExporter.create()
        val telemetry = GraphTelemetryFactory.createForTests(exporter)
        val artifactRoot = Path.of("build", "reports", "graph-agent", "tests")
        val graphPath = artifactRoot.resolve("rename-constrained-llm-graph-${UUID.randomUUID()}.json")

        return try {
            val policy = GraphDecisionPolicies.renameViaContextMenu(expectedReplacementText = fixture.renamedName)
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

            val result = agent.execute(fixture.task)
            val documentAfter = executor.getDocumentText()
            RunExecution(
                fixture = fixture,
                result = result,
                spanNames = exporter.finishedSpanItems.map { it.name },
                documentAfter = documentAfter,
            )
        } finally {
            telemetry.close()
            Files.deleteIfExists(graphPath)
        }
    }

    fun assertSuccessfulRename(run: RunExecution) {
        val result = run.result
        val fixture = run.fixture
        assertTrue(result.success, "Constrained LLM GraphAgent should report rename success: ${result.message}")
        assertTrue(result.actionHistory.isNotEmpty(), "Constrained LLM GraphAgent should record actions")
        assertTrue(
            result.actionHistory.all { it.actionType in allowedActionTypes },
            "Constrained policy should keep action history inside the rename sandbox: ${result.actionHistory.map { it.actionType }}",
        )
        assertTrue(result.artifactDir.isNotBlank(), "Constrained LLM run should record the artifact directory")
        assertTrue(result.artifactPaths.isNotEmpty(), "Constrained LLM test should record HTML artifacts")
        result.artifactPaths.forEach { artifactPath ->
            assertTrue(Files.exists(Path.of(artifactPath)), "Expected artifact to exist: $artifactPath")
        }

        val documentAfter = run.documentAfter
        check(documentAfter != null) { "Expected document text after rename" }
        fixture.assertExpectedAfter(documentAfter, "Constrained LLM GraphAgent rename")

        assertTrue(run.spanNames.any { it == "graph_agent.rename_local_variable" })
        assertTrue(run.spanNames.any { it == "decide.next_action" })
        assertTrue(run.spanNames.any { it == "act.execute" })
    }

    fun runReliabilitySuite(
        runCount: Int,
        reportRoot: Path,
        robot: RemoteRobot,
        robotUrl: String,
        fixture: GraphAgentRenameFixtureScenario = GraphAgentRenameFixtureScenario.canonical,
        openFixture: (UiExecutor) -> String,
    ): ReliabilityReport {
        Files.createDirectories(reportRoot)

        val runs =
            (1..runCount).map { runNumber ->
                val startedAt = Instant.now()
                try {
                    val execution = executeOnce(robot, robotUrl, fixture, openFixture)
                    val success =
                        execution.result.success &&
                            execution.documentAfter != null &&
                            fixture.requiredAfter.all(execution.documentAfter::contains) &&
                            fixture.forbiddenAfter.none(execution.documentAfter::contains)

                    ReliabilityRunRecord(
                        runNumber = runNumber,
                        success = success,
                        iterations = execution.result.iterations,
                        tokenCount = execution.result.tokenCount,
                        failureSeam = classifyFailureSeam(execution.result, success),
                        artifactDir = execution.result.artifactDir,
                        artifactPaths = execution.result.artifactPaths,
                        actionHistory = execution.result.actionHistory,
                        spanNames = execution.spanNames,
                        message = execution.result.message,
                        durationMillis = java.time.Duration.between(startedAt, Instant.now()).toMillis(),
                    )
                } catch (t: Throwable) {
                    ReliabilityRunRecord(
                        runNumber = runNumber,
                        success = false,
                        iterations = 0,
                        tokenCount = 0,
                        failureSeam = classifyThrowableSeam(t),
                        artifactDir = "",
                        artifactPaths = emptyList(),
                        actionHistory = emptyList(),
                        spanNames = emptyList(),
                        message = t.message ?: t::class.simpleName.orEmpty(),
                        durationMillis = java.time.Duration.between(startedAt, Instant.now()).toMillis(),
                    )
                }
            }

        val jsonReportPath = reportRoot.resolve("rename-constrained-llm-reliability.json")
        val markdownReportPath = reportRoot.resolve("rename-constrained-llm-reliability.md")
        val report =
            ReliabilityReport(
                generatedAt = Instant.now().toString(),
                task = fixture.task,
                requestedRuns = runCount,
                passedRuns = runs.count { it.success },
                failedRuns = runs.count { !it.success },
                jsonReportPath = jsonReportPath.toAbsolutePath().normalize().toString(),
                markdownReportPath = markdownReportPath.toAbsolutePath().normalize().toString(),
                runs = runs,
            )

        Files.writeString(jsonReportPath, json.encodeToString(report))
        Files.writeString(markdownReportPath, renderMarkdown(report))
        return report
    }

    private fun classifyFailureSeam(
        result: GraphAgent.AgentResult,
        success: Boolean,
    ): String {
        if (success) {
            return "none"
        }
        if (result.message.contains("Policy violation", ignoreCase = true)) {
            return "decision_policy"
        }
        if (result.message.contains("Execution error", ignoreCase = true)) {
            return "executor"
        }
        if (result.message.contains("Max iterations", ignoreCase = true)) {
            return "completion_detection"
        }
        if (result.actionHistory.lastOrNull()?.actionType == "fail") {
            return "orchestration"
        }
        return "unknown"
    }

    private fun classifyThrowableSeam(t: Throwable): String {
        val message = t.message.orEmpty()
        return when {
            message.contains("Stage 0 harness failure", ignoreCase = true) -> "harness"
            message.contains("document text", ignoreCase = true) -> "executor"
            else -> "unknown"
        }
    }

    private fun renderMarkdown(report: ReliabilityReport): String =
        buildString {
            appendLine("# Constrained LLM Rename Reliability Report")
            appendLine()
            appendLine("- Generated at: ${report.generatedAt}")
            appendLine("- Task: ${report.task}")
            appendLine("- Requested runs: ${report.requestedRuns}")
            appendLine("- Passed runs: ${report.passedRuns}")
            appendLine("- Failed runs: ${report.failedRuns}")
            appendLine()
            appendLine("| Run | Status | Iterations | Failure seam | Actions | Artifact dir |")
            appendLine("| --- | --- | --- | --- | --- | --- |")
            report.runs.forEach { run ->
                val status = if (run.success) "PASS" else "FAIL"
                val actions = run.actionHistory.joinToString(" -> ") { it.actionType }.ifBlank { "-" }
                val artifactDir = run.artifactDir.ifBlank { "-" }
                appendLine("| ${run.runNumber} | $status | ${run.iterations} | ${run.failureSeam} | $actions | $artifactDir |")
            }
        }
}
