package evaluation

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import test.BaseTest

/**
 * Tests for evaluating and comparing UI Tree vs Vision approaches.
 *
 * BEFORE RUNNING:
 *   Terminal 1 -> ./gradlew runIdeForUiTests
 *   Terminal 2 -> ./gradlew test --tests "evaluation.ComparisonTest"
 *
 * EXPLORATION (AppAgent approach):
 *   Vision Agent needs exploration to learn UI elements.
 *   Set RUN_EXPLORATION = true to run exploration before tasks.
 *   Exploration saves labeled screenshots to build/reports/exploration-trace.json
 */
class ComparisonTest : BaseTest() {
    private lateinit var runner: EvaluationRunner

    // Set to true to run exploration before evaluation (recommended per AppAgent paper)
    private val runExploration = true

    // Exploration task - what the agent should learn during exploration
    private val explorationTask =
        "Explore IntelliJ IDE features: learn how to use menus, tool buttons, and common UI elements"

    @BeforeEach
    fun setup() {
        runner = EvaluationRunner.createDefault()
    }

    @Test
    @DisplayName("Run quick evaluation comparing both approaches")
    fun testQuickEvaluation() {
        println("\n=== COMPARISON TEST ===\n")

        val (uiTreeMetrics, visionMetrics) = runner.runQuickEvaluation(runExploration)

        // Both approaches should complete at least some tasks
        println("\nFinal Results:")
        println("UI Tree: ${uiTreeMetrics.successfulTasks}/${uiTreeMetrics.totalTasks} successful")
        println("Vision: ${visionMetrics.successfulTasks}/${visionMetrics.totalTasks} successful")
    }

    @Test
    @DisplayName("Run full evaluation on all benchmark tasks")
    fun testFullEvaluation() {
        println("\n=== FULL EVALUATION ===\n")

        val (uiTreeMetrics, visionMetrics) = runner.runFullEvaluation(runExploration)

        println("\nFinal Results:")
        println("UI Tree Success Rate: ${"%.1f".format(uiTreeMetrics.successRate * 100)}%")
        println("Vision Success Rate: ${"%.1f".format(visionMetrics.successRate * 100)}%")
    }

    @Test
    @DisplayName("Test single task with both approaches")
    fun testSingleTask() {
        println("\n=== SINGLE TASK TEST ===\n")

        // Run exploration first if needed (AppAgent approach)
        if (runExploration) {
            runner.runExploration(explorationTask = explorationTask)
        }

        val task = BenchmarkTasks.allTasks.first { it.id == "navigate_file_1" }
        println("Task: ${task.intent}")

        // Test UI Tree approach
        val uiTreeResult = runner.runUiTreeApproach(task)
        println("\nUI Tree Result: success=${uiTreeResult.success}, steps=${uiTreeResult.steps}")

        Thread.sleep(1000)

        // Test Vision approach
        val visionResult = runner.runVisionApproach(task)
        println("Vision Result: success=${visionResult.success}, steps=${visionResult.steps}")
    }

    @Test
    @DisplayName("Run exploration phase only (to inspect trace)")
    fun testExplorationOnly() {
        println("\n=== EXPLORATION TEST ===\n")
        println("Task: $explorationTask")

        val docsLearned = runner.runExploration(explorationTask = explorationTask)

        println("\nLearned about $docsLearned elements")
        println("Check build/reports/exploration-trace.json for labeled screenshots")
    }
}
